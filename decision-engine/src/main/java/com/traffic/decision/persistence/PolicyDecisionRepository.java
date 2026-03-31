package com.traffic.decision.persistence;

import com.traffic.decision.model.TrafficDecisionEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyDecisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PolicyDecisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TrafficDecisionEvent event) {
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
    }
}
