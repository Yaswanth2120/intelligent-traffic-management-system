package com.traffic.decision.metrics;

import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class DecisionMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger latestRateLimit = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicReference<Double>> scalingPressureByRoute =
            new ConcurrentHashMap<>();

    public DecisionMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("decision_latest_rate_limit_rps", latestRateLimit, AtomicInteger::get)
                .description("Latest rate limit issued by the decision engine")
                .register(meterRegistry);
    }

    /**
     * predictedRps / serviceCapacityRps for the given route -- the same ratio
     * DecisionPolicyService uses to trigger a SCALE_SIGNAL decision (>= 0.85).
     * Recorded on every prediction (not just when SCALE_SIGNAL fires) so it's
     * a continuous signal KEDA's Prometheus scaler can poll, per
     * docs/autoscaling.md.
     */
    public void recordScalingPressure(String route, double pressureRatio) {
        scalingPressureByRoute.computeIfAbsent(route, r -> {
            AtomicReference<Double> ref = new AtomicReference<>(pressureRatio);
            Gauge.builder("decision_scaling_pressure_ratio", ref, AtomicReference::get)
                    .description("Predicted RPS divided by configured service capacity RPS for this route; "
                            + ">=0.85 is the same threshold that triggers a SCALE_SIGNAL decision")
                    .tag("route", r)
                    .register(meterRegistry);
            return ref;
        }).set(pressureRatio);
    }

    public void recordPredictionConsumed(MlPredictionEvent prediction) {
        Counter.builder("decision_predictions_consumed_total")
                .description("ML predictions consumed by the decision engine")
                .tag("route", prediction.route())
                .tag("risk_level", prediction.riskLevel())
                .register(meterRegistry)
                .increment();
    }

    public void recordDecision(TrafficDecisionEvent decision) {
        if (decision.rateLimitRps() != null) {
            latestRateLimit.set(decision.rateLimitRps());
        }

        Counter.builder("decision_policies_issued_total")
                .description("Traffic policies issued by the decision engine")
                .tag("route", decision.route())
                .tag("policy_type", decision.policyType())
                .register(meterRegistry)
                .increment();
    }

    public void recordStorageResult(String route, boolean success) {
        Counter.builder("decision_storage_operations_total")
                .description("Decision storage operations")
                .tag("route", route)
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }

    public void recordPublishResult(TrafficDecisionEvent event, boolean success) {
        Counter.builder("decision_publish_total")
                .description("Traffic decision publish attempts")
                .tag("route", event.route())
                .tag("policy_type", event.policyType())
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }
}
