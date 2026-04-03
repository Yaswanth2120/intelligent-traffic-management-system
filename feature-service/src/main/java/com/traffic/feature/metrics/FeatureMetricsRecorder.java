package com.traffic.feature.metrics;

import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class FeatureMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicReference<Double> latestRps = new AtomicReference<>(0.0);

    public FeatureMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("feature_latest_requests_per_sec", latestRps, AtomicReference::get)
                .description("Latest aggregated requests per second")
                .register(meterRegistry);
    }

    public void recordTrafficMetricConsumed(TrafficMetricEvent event) {
        Counter.builder("feature_traffic_metrics_consumed_total")
                .description("Traffic metrics consumed by feature service")
                .tag("route", event.route())
                .register(meterRegistry)
                .increment();
    }

    public void recordAggregation(AggregatedFeaturesEvent event) {
        latestRps.set(event.requestsPerSec());
        Counter.builder("feature_aggregations_total")
                .description("Aggregated feature windows produced")
                .tag("route", event.route())
                .register(meterRegistry)
                .increment();
    }

    public void recordStorageResult(String target, String route, boolean success) {
        Counter.builder("feature_storage_operations_total")
                .description("Feature storage operations")
                .tag("target", target)
                .tag("route", route)
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }

    public void recordFeaturePublish(String route, boolean success) {
        Counter.builder("feature_publish_total")
                .description("Aggregated feature publish attempts")
                .tag("route", route)
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }
}
