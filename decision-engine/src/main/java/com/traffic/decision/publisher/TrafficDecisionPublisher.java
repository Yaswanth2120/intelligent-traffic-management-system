package com.traffic.decision.publisher;

import com.traffic.decision.config.DecisionEngineProperties;
import com.traffic.decision.metrics.DecisionMetricsRecorder;
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
    private final DecisionMetricsRecorder metricsRecorder;

    public TrafficDecisionPublisher(KafkaTemplate<String, TrafficDecisionEvent> kafkaTemplate,
                                    DecisionEngineProperties properties,
                                    DecisionMetricsRecorder metricsRecorder) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
    }

    public void publish(TrafficDecisionEvent event) {
        try {
            kafkaTemplate.send(properties.topics().trafficDecisions(), event.route(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            metricsRecorder.recordPublishResult(event, false);
                            log.warn("Failed to publish traffic decision for route {}", event.route(), error);
                        } else {
                            metricsRecorder.recordPublishResult(event, true);
                        }
                    });
        } catch (Exception ex) {
            metricsRecorder.recordPublishResult(event, false);
            log.warn("Traffic decision publish skipped for route {}", event.route(), ex);
        }
    }
}
