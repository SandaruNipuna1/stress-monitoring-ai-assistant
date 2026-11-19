package com.sliit.tg.dto;

import java.util.List;

public record DashboardResponse(
        double wellbeingScore,
        double overallSupportScore,
        String overallSupportLabel,
        String overallSupportSummary,
        double avgStress,
        double avgEnergy,
        double avgSleep,
        long doneCount,
        long savedCount,
        String recommendationSource,
        List<MoodEntryResponse> recentMoods,
        List<RecommendationResponse> recommendations
) {
}
