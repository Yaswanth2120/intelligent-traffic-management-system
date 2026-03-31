package com.traffic.decision.rules;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DecisionPolicyService {

    private final DecisionEngineProperties properties;

    public DecisionPolicyService(DecisionEngineProperties properties) {
        this.properties = properties;
    }

    public Optional<TrafficDecisionEvent> evaluate(MlPredictionEvent prediction) {
        int capacity = properties.policy().serviceCapacityRps();
        int ttl = Math.max(prediction.predictionHorizonSec(), properties.policy().defaultTtlSec());
        long now = Instant.now().getEpochSecond();

        if (prediction.predictedRps() > capacity || "high".equalsIgnoreCase(prediction.riskLevel())
                || prediction.spikeProbability() >= 0.80) {
            return Optional.of(new TrafficDecisionEvent(
                    prediction.route(),
                    now,
                    "RATE_LIMIT",
                    properties.policy().highRiskRateLimitRps(),
                    "predicted_overload_or_high_spike_risk",
                    ttl
            ));
        }

        if ("medium".equalsIgnoreCase(prediction.riskLevel()) || prediction.spikeProbability() >= 0.50) {
            return Optional.of(new TrafficDecisionEvent(
                    prediction.route(),
                    now,
                    "RATE_LIMIT",
                    properties.policy().mediumRiskRateLimitRps(),
                    "moderate_spike_risk",
                    ttl
            ));
        }

        if (prediction.predictedRps() >= capacity * 0.85) {
            return Optional.of(new TrafficDecisionEvent(
                    prediction.route(),
                    now,
                    "SCALE_SIGNAL",
                    null,
                    "approaching_capacity_threshold",
                    ttl
            ));
        }

        return Optional.empty();
    }
}
