package com.traffic.decision.rules;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.metrics.DecisionMetricsRecorder;
import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DecisionPolicyService {

    private final DecisionEngineProperties properties;
    private final DecisionMetricsRecorder metricsRecorder;

    public DecisionPolicyService(DecisionEngineProperties properties, DecisionMetricsRecorder metricsRecorder) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
    }

    public Optional<TrafficDecisionEvent> evaluate(MlPredictionEvent prediction) {
        int capacity = properties.policy().serviceCapacityRps();
        int ttl = Math.max(prediction.predictionHorizonSec(), properties.policy().defaultTtlSec());
        long now = Instant.now().getEpochSecond();

        // Exported as decision_scaling_pressure_ratio for KEDA (see docs/autoscaling.md).
        // Recorded for every prediction, not just when SCALE_SIGNAL fires, so it's a
        // continuous signal rather than one that goes stale between threshold crossings.
        metricsRecorder.recordScalingPressure(prediction.route(), prediction.predictedRps() / (double) capacity);

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
