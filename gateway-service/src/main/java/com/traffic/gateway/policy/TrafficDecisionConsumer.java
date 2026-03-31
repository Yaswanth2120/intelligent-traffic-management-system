package com.traffic.gateway.policy;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TrafficDecisionConsumer {

    private final ActivePolicyStore activePolicyStore;

    public TrafficDecisionConsumer(ActivePolicyStore activePolicyStore) {
        this.activePolicyStore = activePolicyStore;
    }

    @KafkaListener(topics = "${traffic.topics.traffic-decisions}", groupId = "gateway-service")
    public void consume(TrafficDecisionEvent event) {
        activePolicyStore.upsert(event);
    }
}
