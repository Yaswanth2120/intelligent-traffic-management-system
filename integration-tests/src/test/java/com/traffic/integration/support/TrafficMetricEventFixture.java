package com.traffic.integration.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds `traffic_metrics` payloads matching the contract in docs/architecture.md
 * / feature-service's TrafficMetricEvent record, without depending on that
 * module's classes (keeps this test module decoupled and honest about testing
 * over the wire, the way the real gateway does).
 */
public final class TrafficMetricEventFixture {

    private String route = "orders-route";
    private long timestamp = System.currentTimeMillis() / 1000;
    private String method = "GET";
    private int status = 200;
    private long latencyMs = 50;
    private String clientId = "client-1";
    private String clientIp = "10.0.0.1";

    public static TrafficMetricEventFixture aTrafficMetric() {
        return new TrafficMetricEventFixture();
    }

    public TrafficMetricEventFixture route(String route) {
        this.route = route;
        return this;
    }

    public TrafficMetricEventFixture timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public TrafficMetricEventFixture status(int status) {
        this.status = status;
        return this;
    }

    public TrafficMetricEventFixture latencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
        return this;
    }

    public TrafficMetricEventFixture clientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("route", route);
        payload.put("timestamp", timestamp);
        payload.put("method", method);
        payload.put("status", status);
        payload.put("latency_ms", latencyMs);
        payload.put("client_id", clientId);
        payload.put("client_ip", clientIp);
        return payload;
    }
}
