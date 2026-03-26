package com.traffic.feature.aggregation;

import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RouteWindowAggregate {

    private final int windowSizeSec;
    private final Deque<TrafficMetricEvent> events = new ArrayDeque<>();

    public RouteWindowAggregate(int windowSizeSec) {
        this.windowSizeSec = windowSizeSec;
    }

    public synchronized AggregatedFeaturesEvent add(TrafficMetricEvent event) {
        events.addLast(event);
        prune(event.timestamp());
        return snapshot(event.route());
    }

    private void prune(long currentTimestamp) {
        long lowerBound = currentTimestamp - windowSizeSec + 1L;
        while (!events.isEmpty() && events.peekFirst().timestamp() < lowerBound) {
            events.removeFirst();
        }
    }

    private AggregatedFeaturesEvent snapshot(String route) {
        int requestCount = events.size();
        double avgLatency = events.stream()
                .mapToLong(TrafficMetricEvent::latencyMs)
                .average()
                .orElse(0);

        long errors = events.stream()
                .filter(event -> event.status() >= 500)
                .count();

        Set<String> uniqueClients = new HashSet<>();
        List<Long> latencies = new ArrayList<>(requestCount);
        for (TrafficMetricEvent event : events) {
            uniqueClients.add(event.clientId());
            latencies.add(event.latencyMs());
        }
        latencies.sort(Comparator.naturalOrder());

        double p95Latency = latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        long windowStart = events.isEmpty() ? 0 : events.peekFirst().timestamp();

        return new AggregatedFeaturesEvent(
                route,
                windowStart,
                windowSizeSec,
                requestCount / (double) windowSizeSec,
                requestCount == 0 ? 0 : errors / (double) requestCount,
                avgLatency,
                p95Latency,
                uniqueClients.size()
        );
    }
}
