package com.traffic.gateway.policy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ActivePolicyStore {

    private final Map<String, ActiveRateLimitPolicy> policies = new ConcurrentHashMap<>();
    private final GatewayPolicyMetricsRecorder metricsRecorder;

    public ActivePolicyStore(GatewayPolicyMetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
    }

    public void upsert(TrafficDecisionEvent event) {
        if (!"RATE_LIMIT".equalsIgnoreCase(event.policyType()) || event.rateLimitRps() == null) {
            return;
        }

        long expiresAt = Instant.now().getEpochSecond() + event.ttlSec();
        policies.put(event.route(), new ActiveRateLimitPolicy(event.rateLimitRps(), expiresAt, event.reason()));
        metricsRecorder.updateActivePolicies(policies.size());
    }

    public ActiveRateLimitPolicy get(String route) {
        ActiveRateLimitPolicy policy = policies.get(route);
        if (policy == null) {
            return null;
        }

        long now = Instant.now().getEpochSecond();
        if (policy.isExpired(now)) {
            policies.remove(route);
            metricsRecorder.updateActivePolicies(policies.size());
            return null;
        }
        return policy;
    }
}
