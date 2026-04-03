package com.traffic.gateway.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TrafficMetricPublisher {

    private static final Logger log = LoggerFactory.getLogger(TrafficMetricPublisher.class);

    private final KafkaTemplate<String, TrafficMetricEvent> kafkaTemplate;
    private final TrafficTopicsProperties topics;
    private final GatewayMetricsRecorder metricsRecorder;

    public TrafficMetricPublisher(KafkaTemplate<String, TrafficMetricEvent> kafkaTemplate,
                                  TrafficTopicsProperties topics,
                                  GatewayMetricsRecorder metricsRecorder) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.metricsRecorder = metricsRecorder;
    }

    public void publish(TrafficMetricEvent event) {
        try {
            kafkaTemplate.send(topics.trafficMetrics(), event.route(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            metricsRecorder.recordMetricPublish(event, false);
                            log.warn("Failed to publish traffic metric for route {}", event.route(), error);
                        } else {
                            metricsRecorder.recordMetricPublish(event, true);
                        }
                    });
        } catch (Exception ex) {
            metricsRecorder.recordMetricPublish(event, false);
            log.warn("Traffic metric publish skipped for route {}", event.route(), ex);
        }
    }
}
