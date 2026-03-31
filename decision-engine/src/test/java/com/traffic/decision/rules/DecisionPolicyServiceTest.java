package com.traffic.decision.rules;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPolicyServiceTest {

    private final DecisionPolicyService service = new DecisionPolicyService(
            new DecisionEngineProperties(
                    new DecisionEngineProperties.Policy(250, 120, 175, 220),
                    new DecisionEngineProperties.Topics("ml_predictions", "traffic_decisions")
            )
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
}
