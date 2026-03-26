package com.traffic.feature.persistence;

import com.traffic.feature.model.AggregatedFeaturesEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrafficHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public TrafficHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AggregatedFeaturesEvent event) {
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
    }
}
