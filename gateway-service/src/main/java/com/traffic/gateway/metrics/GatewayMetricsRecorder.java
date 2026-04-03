package com.traffic.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public GatewayMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(TrafficMetricEvent event) {
        Counter.builder("gateway_requests_total")
                .description("Total gateway requests observed")
                .tag("route", event.route())
                .tag("status", Integer.toString(event.status()))
                .tag("method", event.method())
                .register(meterRegistry)
                .increment();

        Timer.builder("gateway_request_latency_ms")
                .description("Gateway request latency in milliseconds")
                .tag("route", event.route())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(event.latencyMs(), TimeUnit.MILLISECONDS);
    }

    public void recordMetricPublish(TrafficMetricEvent event, boolean success) {
        Counter.builder("gateway_metric_publish_total")
                .description("Traffic metric publish attempts")
                .tag("route", event.route())
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }
}
