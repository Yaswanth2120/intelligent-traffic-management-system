package com.traffic.gateway.policy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class GatewayPolicyMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activePoliciesGauge = new AtomicInteger();

    public GatewayPolicyMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("gateway_active_policies", activePoliciesGauge, AtomicInteger::get)
                .description("Number of active gateway traffic policies")
                .register(meterRegistry);
    }

    public void recordPolicyUpdate(TrafficDecisionEvent event) {
        Counter.builder("gateway_policy_updates_total")
                .description("Traffic decisions consumed by gateway")
                .tag("route", event.route())
                .tag("policy_type", event.policyType())
                .register(meterRegistry)
                .increment();
    }

    public void recordThrottle(String route, String reason) {
        Counter.builder("gateway_throttled_requests_total")
                .description("Requests rejected by active gateway policy")
                .tag("route", route)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    public void updateActivePolicies(int activePolicies) {
        activePoliciesGauge.set(activePolicies);
    }
}
