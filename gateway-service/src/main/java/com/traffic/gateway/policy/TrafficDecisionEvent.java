package com.traffic.gateway.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrafficDecisionEvent(
        String route,
        @JsonProperty("decision_time") long decisionTime,
        @JsonProperty("policy_type") String policyType,
        @JsonProperty("rate_limit_rps") Integer rateLimitRps,
        String reason,
        @JsonProperty("ttl_sec") int ttlSec
) {
}
