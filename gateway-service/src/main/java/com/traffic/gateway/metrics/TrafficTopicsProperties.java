package com.traffic.gateway.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "traffic.topics")
public record TrafficTopicsProperties(String trafficMetrics) {
}
