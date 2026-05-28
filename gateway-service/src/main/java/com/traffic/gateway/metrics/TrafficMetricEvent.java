package com.traffic.gateway.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrafficMetricEvent(
        String route,
        long timestamp,
        String method,
        int status,
        @JsonProperty("latency_ms") long latencyMs,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_ip") String clientIp
) {
}
