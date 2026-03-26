package com.traffic.feature.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "traffic")
public record FeatureServiceProperties(
        Aggregation aggregation,
        Topics topics
) {
    public record Aggregation(int windowSizeSec) {
    }

    public record Topics(String trafficMetrics, String aggregatedFeatures) {
    }
}
