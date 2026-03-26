package com.traffic.feature.publisher;

import com.traffic.feature.config.FeatureServiceProperties;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class AggregatedFeaturesPublisher {

    private static final Logger log = LoggerFactory.getLogger(AggregatedFeaturesPublisher.class);

    private final KafkaTemplate<String, AggregatedFeaturesEvent> kafkaTemplate;
    private final FeatureServiceProperties properties;

    public AggregatedFeaturesPublisher(KafkaTemplate<String, AggregatedFeaturesEvent> kafkaTemplate,
                                       FeatureServiceProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void publish(AggregatedFeaturesEvent event) {
        try {
            kafkaTemplate.send(properties.topics().aggregatedFeatures(), event.route(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Failed to publish aggregated feature event for route {}", event.route(), error);
                        }
                    });
        } catch (Exception ex) {
            log.warn("Aggregated feature publish skipped for route {}", event.route(), ex);
        }
    }
}
