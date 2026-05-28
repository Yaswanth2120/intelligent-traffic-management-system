package com.traffic.decision.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MlPredictionEvent(
        String route,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("predicted_rps") double predictedRps,
        @JsonProperty("spike_probability") double spikeProbability,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("prediction_horizon_sec") int predictionHorizonSec
) {
}
