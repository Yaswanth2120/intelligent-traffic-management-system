package com.traffic.feature.consumer;

import com.traffic.feature.aggregation.TrafficAggregationService;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import com.traffic.feature.persistence.RedisFeatureWindowRepository;
import com.traffic.feature.persistence.TrafficHistoryRepository;
import com.traffic.feature.publisher.AggregatedFeaturesPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TrafficMetricsConsumer {

    private final TrafficAggregationService aggregationService;
    private final RedisFeatureWindowRepository redisRepository;
    private final TrafficHistoryRepository trafficHistoryRepository;
    private final AggregatedFeaturesPublisher publisher;

    public TrafficMetricsConsumer(TrafficAggregationService aggregationService,
                                  RedisFeatureWindowRepository redisRepository,
                                  TrafficHistoryRepository trafficHistoryRepository,
                                  AggregatedFeaturesPublisher publisher) {
        this.aggregationService = aggregationService;
        this.redisRepository = redisRepository;
        this.trafficHistoryRepository = trafficHistoryRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "${traffic.topics.traffic-metrics}", groupId = "feature-service")
    public void consume(TrafficMetricEvent event) {
        AggregatedFeaturesEvent aggregated = aggregationService.aggregate(event);
        redisRepository.save(aggregated);
        trafficHistoryRepository.save(aggregated);
        publisher.publish(aggregated);
    }
}
