package com.traffic.decision.rules;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.metrics.DecisionMetricsRecorder;
import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPolicyServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DecisionPolicyService service = new DecisionPolicyService(
            new DecisionEngineProperties(
                    new DecisionEngineProperties.Policy(250, 120, 175, 220),
                    new DecisionEngineProperties.Topics("ml_predictions", "traffic_decisions")
            ),
            new DecisionMetricsRecorder(meterRegistry)
    );

    @Test
    void shouldRateLimitHighRiskPrediction() {
        MlPredictionEvent prediction = new MlPredictionEvent("orders-route", "baseline-v2", 330, 0.87, "high", 60);

        Optional<TrafficDecisionEvent> result = service.evaluate(prediction);

        assertTrue(result.isPresent());
        assertEquals("RATE_LIMIT", result.get().policyType());
        assertEquals(175, result.get().rateLimitRps());
    }

    @Test
    void shouldEmitScaleSignalNearCapacity() {
        MlPredictionEvent prediction = new MlPredictionEvent("orders-route", "baseline-v2", 220, 0.25, "low", 60);

        Optional<TrafficDecisionEvent> result = service.evaluate(prediction);

        assertTrue(result.isPresent());
        assertEquals("SCALE_SIGNAL", result.get().policyType());
    }

    @Test
    void shouldExportScalingPressureRatioForEveryPrediction() {
        MlPredictionEvent prediction = new MlPredictionEvent("orders-route", "baseline-v2", 125, 0.10, "low", 60);

        service.evaluate(prediction);

        double pressureRatio = meterRegistry.get("decision_scaling_pressure_ratio")
                .tag("route", "orders-route")
                .gauge()
                .value();
        assertEquals(0.5, pressureRatio, 1e-9);
    }
}
