package com.traffic.gateway.policy;

public record TrafficDecisionEvent(
        String route,
        long decisionTime,
        String policyType,
        Integer rateLimitRps,
        String reason,
        int ttlSec
) {
}
