package com.traffic.gateway.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "traffic")
public record GatewayTrafficProperties(
        Enforcement enforcement,
        Topics topics
) {
    public record Enforcement(int defaultLimitRps) {
    }

    public record Topics(String trafficMetrics, String trafficDecisions) {
    }
}
