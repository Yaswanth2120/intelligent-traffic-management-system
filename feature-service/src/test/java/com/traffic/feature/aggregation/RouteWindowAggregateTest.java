package com.traffic.feature.aggregation;

import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteWindowAggregateTest {

    @Test
    void shouldComputeRollingWindowMetrics() {
        RouteWindowAggregate aggregate = new RouteWindowAggregate(60);

        aggregate.add(new TrafficMetricEvent("orders-route", 100, "GET", 200, 100, "client-a", "10.0.0.1"));
        aggregate.add(new TrafficMetricEvent("orders-route", 120, "GET", 503, 200, "client-b", "10.0.0.2"));
        AggregatedFeaturesEvent result =
                aggregate.add(new TrafficMetricEvent("orders-route", 159, "GET", 200, 300, "client-a", "10.0.0.1"));

        assertEquals("orders-route", result.route());
        assertEquals(100, result.windowStart());
        assertEquals(60, result.windowSizeSec());
        assertEquals(0.05, result.requestsPerSec(), 0.0001);
        assertEquals(1.0 / 3.0, result.errorRate(), 0.0001);
        assertEquals(200.0, result.avgLatencyMs(), 0.0001);
        assertEquals(300.0, result.p95LatencyMs(), 0.0001);
        assertEquals(2, result.uniqueClients());
    }

    @Test
    void shouldDropEventsOutsideWindow() {
        RouteWindowAggregate aggregate = new RouteWindowAggregate(60);

        aggregate.add(new TrafficMetricEvent("orders-route", 100, "GET", 200, 100, "client-a", "10.0.0.1"));
        AggregatedFeaturesEvent result =
                aggregate.add(new TrafficMetricEvent("orders-route", 170, "GET", 200, 300, "client-b", "10.0.0.2"));

        assertEquals(1.0 / 60.0, result.requestsPerSec(), 0.0001);
        assertEquals(170, result.windowStart());
        assertEquals(1, result.uniqueClients());
    }
}
