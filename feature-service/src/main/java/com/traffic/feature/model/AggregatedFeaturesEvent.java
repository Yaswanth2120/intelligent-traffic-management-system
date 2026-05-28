package com.traffic.feature.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AggregatedFeaturesEvent(
        String route,
        @JsonProperty("window_start") long windowStart,
        @JsonProperty("window_size_sec") int windowSizeSec,
        @JsonProperty("requests_per_sec") double requestsPerSec,
        @JsonProperty("error_rate") double errorRate,
        @JsonProperty("avg_latency_ms") double avgLatencyMs,
        @JsonProperty("p95_latency_ms") double p95LatencyMs,
        @JsonProperty("unique_clients") int uniqueClients
) {
}
