package com.traffic.feature.aggregation;

import com.traffic.feature.config.FeatureServiceProperties;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class TrafficAggregationService {

    private final ConcurrentHashMap<String, RouteWindowAggregate> routeAggregates = new ConcurrentHashMap<>();
    private final int windowSizeSec;

    public TrafficAggregationService(FeatureServiceProperties properties) {
        this.windowSizeSec = properties.aggregation().windowSizeSec();
    }

    public AggregatedFeaturesEvent aggregate(TrafficMetricEvent event) {
        return routeAggregates
                .computeIfAbsent(event.route(), route -> new RouteWindowAggregate(windowSizeSec))
                .add(event);
    }
}
