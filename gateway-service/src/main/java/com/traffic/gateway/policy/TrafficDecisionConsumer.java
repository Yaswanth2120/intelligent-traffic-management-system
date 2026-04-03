package com.traffic.gateway.policy;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TrafficDecisionConsumer {

    private final ActivePolicyStore activePolicyStore;
    private final GatewayPolicyMetricsRecorder metricsRecorder;

    public TrafficDecisionConsumer(ActivePolicyStore activePolicyStore,
                                   GatewayPolicyMetricsRecorder metricsRecorder) {
        this.activePolicyStore = activePolicyStore;
        this.metricsRecorder = metricsRecorder;
    }

    @KafkaListener(topics = "${traffic.topics.traffic-decisions}", groupId = "gateway-service")
    public void consume(TrafficDecisionEvent event) {
        metricsRecorder.recordPolicyUpdate(event);
        activePolicyStore.upsert(event);
    }
}
