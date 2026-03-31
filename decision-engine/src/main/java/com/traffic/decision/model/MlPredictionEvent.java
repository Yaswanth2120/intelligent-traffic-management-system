package com.traffic.decision.model;

public record MlPredictionEvent(
        String route,
        String modelVersion,
        double predictedRps,
        double spikeProbability,
        String riskLevel,
        int predictionHorizonSec
) {
}
