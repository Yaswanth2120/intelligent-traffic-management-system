package com.traffic.feature.persistence;

import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.metrics.FeatureMetricsRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrafficHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FeatureMetricsRecorder metricsRecorder;

    public TrafficHistoryRepository(JdbcTemplate jdbcTemplate, FeatureMetricsRecorder metricsRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsRecorder = metricsRecorder;
    }

    public void save(AggregatedFeaturesEvent event) {
        try {
            jdbcTemplate.update("""
                    insert into traffic_history (route, timestamp, request_count, avg_latency_ms, error_rate)
                    values (?, ?, ?, ?, ?)
                    """,
                    event.route(),
                    event.windowStart(),
                    Math.round(event.requestsPerSec() * event.windowSizeSec()),
                    event.avgLatencyMs(),
                    event.errorRate()
            );
            metricsRecorder.recordStorageResult("postgres", event.route(), true);
        } catch (RuntimeException ex) {
            metricsRecorder.recordStorageResult("postgres", event.route(), false);
            throw ex;
        }
    }
}
