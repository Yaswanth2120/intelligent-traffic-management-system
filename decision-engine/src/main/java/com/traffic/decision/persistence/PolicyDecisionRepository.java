package com.traffic.decision.persistence;

import com.traffic.decision.model.TrafficDecisionEvent;
import com.traffic.decision.metrics.DecisionMetricsRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyDecisionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DecisionMetricsRecorder metricsRecorder;

    public PolicyDecisionRepository(JdbcTemplate jdbcTemplate, DecisionMetricsRecorder metricsRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsRecorder = metricsRecorder;
    }

    public void save(TrafficDecisionEvent event) {
        try {
            jdbcTemplate.update("""
                    insert into policy_decisions (route, decision_time, policy_type, rate_limit_rps, reason, ttl_sec)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    event.route(),
                    event.decisionTime(),
                    event.policyType(),
                    event.rateLimitRps(),
                    event.reason(),
                    event.ttlSec()
            );
            metricsRecorder.recordStorageResult(event.route(), true);
        } catch (RuntimeException ex) {
            metricsRecorder.recordStorageResult(event.route(), false);
            throw ex;
        }
    }
}
