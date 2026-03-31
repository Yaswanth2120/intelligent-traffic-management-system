package com.traffic.decision.publisher;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.model.TrafficDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TrafficDecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(TrafficDecisionPublisher.class);

    private final KafkaTemplate<String, TrafficDecisionEvent> kafkaTemplate;
    private final DecisionEngineProperties properties;

    public TrafficDecisionPublisher(KafkaTemplate<String, TrafficDecisionEvent> kafkaTemplate,
                                    DecisionEngineProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void publish(TrafficDecisionEvent event) {
        try {
            kafkaTemplate.send(properties.topics().trafficDecisions(), event.route(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Failed to publish traffic decision for route {}", event.route(), error);
                        }
                    });
        } catch (Exception ex) {
            log.warn("Traffic decision publish skipped for route {}", event.route(), ex);
        }
    }
}
