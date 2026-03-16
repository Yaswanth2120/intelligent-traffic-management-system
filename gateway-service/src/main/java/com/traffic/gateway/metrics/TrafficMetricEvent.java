package com.traffic.gateway.metrics;

public record TrafficMetricEvent(
        String route,
        long timestamp,
        String method,
        int status,
        long latencyMs,
        String clientId,
        String clientIp
) {
}
