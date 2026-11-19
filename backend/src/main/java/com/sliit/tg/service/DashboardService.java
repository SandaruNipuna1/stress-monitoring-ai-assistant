package com.sliit.tg.service;

import com.sliit.tg.dto.DashboardResponse;
import com.sliit.tg.dto.MoodEntryResponse;
import com.sliit.tg.dto.RecommendationBundle;
import com.sliit.tg.dto.RecommendationResponse;
import com.sliit.tg.model.ChatMessage;
import com.sliit.tg.model.ChatSession;
import com.sliit.tg.model.GuidedSession;
import com.sliit.tg.model.MoodEntry;
import com.sliit.tg.model.RecommendationStatus;
import com.sliit.tg.model.SessionOutcome;
import com.sliit.tg.model.SessionStatus;
import com.sliit.tg.repo.ChatMessageRepository;
import com.sliit.tg.repo.ChatSessionRepository;
import com.sliit.tg.repo.GuidedSessionRepository;
import com.sliit.tg.repo.MoodEntryRepository;
import com.sliit.tg.repo.RecommendationLogRepository;
import com.sliit.tg.repo.SessionOutcomeRepository;
import com.sliit.tg.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DashboardService {

    private final MoodEntryRepository moodEntryRepository;
    private final RecommendationLogRepository recommendationLogRepository;
    private final RecommendationService recommendationService;
    private final UserRepository userRepository;
    private final GuidedSessionRepository guidedSessionRepository;
    private final SessionOutcomeRepository sessionOutcomeRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    public DashboardService(
            MoodEntryRepository moodEntryRepository,
            RecommendationLogRepository recommendationLogRepository,
            RecommendationService recommendationService,
            UserRepository userRepository,
            GuidedSessionRepository guidedSessionRepository,
            SessionOutcomeRepository sessionOutcomeRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository
    ) {
        this.moodEntryRepository = moodEntryRepository;
        this.recommendationLogRepository = recommendationLogRepository;
        this.recommendationService = recommendationService;
        this.userRepository = userRepository;
        this.guidedSessionRepository = guidedSessionRepository;
        this.sessionOutcomeRepository = sessionOutcomeRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    public DashboardResponse getDashboard(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found."));

        List<MoodEntry> entries = moodEntryRepository.findByUser_IdOrderByDateDesc(userId);
        List<MoodEntryResponse> recentMoods = entries.stream()
                .limit(7)
                .map(MoodEntryResponse::from)
                .toList();

        double avgStress = entries.stream().limit(10).mapToInt(MoodEntry::getStressLevel).average().orElse(0.0);
        double avgEnergy = entries.stream().limit(10).mapToInt(MoodEntry::getEnergyLevel).average().orElse(0.0);
        double avgSleep = entries.stream().limit(10).mapToInt(MoodEntry::getSleepQuality).average().orElse(0.0);
        double wellbeingScore = entries.isEmpty() ? 0.0 : ((6.0 - avgStress) + avgEnergy + avgSleep) / 3.0;
        SupportSnapshot supportSnapshot = buildSupportSnapshot(userId, wellbeingScore, entries);

        long doneCount = recommendationLogRepository.countByUser_IdAndStatus(userId, RecommendationStatus.DONE);
        long savedCount = recommendationLogRepository.countByUser_IdAndStatus(userId, RecommendationStatus.SAVED);
        RecommendationBundle recommendationBundle = recommendationService.getRecommendationBundle(userId);
        List<RecommendationResponse> recommendations = recommendationBundle.recommendations();

        return new DashboardResponse(
                roundToOneDecimal(wellbeingScore),
                supportSnapshot.score(),
                supportSnapshot.label(),
                supportSnapshot.summary(),
                roundToOneDecimal(avgStress),
                roundToOneDecimal(avgEnergy),
                roundToOneDecimal(avgSleep),
                doneCount,
                savedCount,
                recommendationBundle.source(),
                recentMoods,
                recommendations
        );
    }

    private double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private SupportSnapshot buildSupportSnapshot(Long userId, double wellbeingScore, List<MoodEntry> entries) {
        List<Double> componentScores = new ArrayList<>();
        List<String> componentLabels = new ArrayList<>();

        if (!entries.isEmpty()) {
            componentScores.add(wellbeingScore);
            componentLabels.add("daily check-ins");
        }

        List<GuidedSession> guidedSessions = guidedSessionRepository.findAllByUserIdAndStatusOrderByStartedAtDesc(
                String.valueOf(userId),
                SessionStatus.COMPLETED
        );
        guidedSessions.stream().findFirst()
                .flatMap(session -> sessionOutcomeRepository.findById(session.getId()))
                .ifPresent(outcome -> {
                    componentScores.add(scoreFromImpact(outcome));
                    componentLabels.add("guided support");
                });

        Double freeChatScore = buildFreeChatScore();
        if (freeChatScore != null) {
            componentScores.add(freeChatScore);
            componentLabels.add("free chat");
        }

        double overall = componentScores.isEmpty()
                ? 0.0
                : componentScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        String label = labelForScore(overall);
        String sources = componentLabels.isEmpty() ? "no recent support signals" : String.join(", ", componentLabels);
        String summary;

        if (componentLabels.isEmpty()) {
            summary = "Start with a daily check-in, guided support topic, or free chat message to build a fuller support snapshot.";
        } else {
            summary = "This snapshot blends signals from " + sources + " into one simple scale so the home dashboard can show your overall support picture at a glance.";
        }

        return new SupportSnapshot(roundToOneDecimal(overall), label, summary);
    }

    private double scoreFromImpact(SessionOutcome outcome) {
        String impact = outcome.getImpactLevel() == null ? "" : outcome.getImpactLevel().toLowerCase(Locale.ROOT);
        if (impact.contains("high")) {
            return 1.8;
        }
        if (impact.contains("moderate") || impact.contains("medium")) {
            return 3.0;
        }
        return 4.2;
    }

    private Double buildFreeChatScore() {
        List<ChatSession> recentSessions = chatSessionRepository.findAllByOrderByCreatedAtDesc();
        int sessionLimit = Math.min(2, recentSessions.size());
        if (sessionLimit == 0) {
            return null;
        }

        int signal = 0;
        boolean hasUserMessage = false;

        for (int i = 0; i < sessionLimit; i++) {
            ChatSession session = recentSessions.get(i);
            signal += topicSignal(session.getTopicCode());

            for (ChatMessage message : chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())) {
                if (!"user".equalsIgnoreCase(message.getSender())) {
                    continue;
                }
                hasUserMessage = true;
                signal += messageSignal(message.getMessage());
            }
        }

        if (!hasUserMessage) {
            return null;
        }

        double base = 3.2 - Math.max(-4, Math.min(4, signal)) * 0.32;
        return Math.max(1.0, Math.min(5.0, base));
    }

    private int topicSignal(String topicCode) {
        if (topicCode == null) {
            return 0;
        }
        String normalized = topicCode.toLowerCase(Locale.ROOT);
        if (normalized.contains("anxiety")) {
            return 2;
        }
        if (normalized.contains("academic")) {
            return 1;
        }
        if (normalized.contains("relationship")) {
            return 1;
        }
        return 0;
    }

    private int messageSignal(String message) {
        if (message == null || message.isBlank()) {
            return 0;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsAny(normalized, "stress", "stressed", "overwhelm", "anx", "panic", "worried", "scared")) {
            score += 2;
        }
        if (containsAny(normalized, "sad", "down", "depress", "tired", "exhaust", "bad", "hopeless")) {
            score += 2;
        }
        if (containsAny(normalized, "good", "better", "calm", "okay", "fine", "motivated", "happy")) {
            score -= 2;
        }

        return score;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String labelForScore(double score) {
        if (score >= 4.0) {
            return "Steady";
        }
        if (score >= 3.0) {
            return "Mixed";
        }
        if (score >= 2.0) {
            return "Needs Attention";
        }
        return "High Support Needed";
    }

    private record SupportSnapshot(double score, String label, String summary) {
    }
}
