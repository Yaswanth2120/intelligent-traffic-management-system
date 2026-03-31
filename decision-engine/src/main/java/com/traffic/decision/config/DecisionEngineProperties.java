package com.traffic.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "traffic")
public record DecisionEngineProperties(
        Policy policy,
        Topics topics
) {
    public record Policy(
            int serviceCapacityRps,
            int defaultTtlSec,
            int highRiskRateLimitRps,
            int mediumRiskRateLimitRps
    ) {
    }

    public record Topics(String mlPredictions, String trafficDecisions) {
    }
}
