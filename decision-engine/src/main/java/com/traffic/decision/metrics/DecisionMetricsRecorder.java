package com.traffic.decision.metrics;

import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.model.TrafficDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class DecisionMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger latestRateLimit = new AtomicInteger();

    public DecisionMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("decision_latest_rate_limit_rps", latestRateLimit, AtomicInteger::get)
                .description("Latest rate limit issued by the decision engine")
                .register(meterRegistry);
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
