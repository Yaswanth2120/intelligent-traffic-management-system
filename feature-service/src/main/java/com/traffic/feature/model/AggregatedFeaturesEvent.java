package com.traffic.feature.model;

public record AggregatedFeaturesEvent(
        String route,
        long windowStart,
        int windowSizeSec,
        double requestsPerSec,
        double errorRate,
        double avgLatencyMs,
        double p95LatencyMs,
        int uniqueClients
) {
}
