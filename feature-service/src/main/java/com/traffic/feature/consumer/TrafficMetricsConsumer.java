package com.traffic.feature.consumer;

import com.traffic.feature.aggregation.TrafficAggregationService;
import com.traffic.feature.metrics.FeatureMetricsRecorder;
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
    private final FeatureMetricsRecorder metricsRecorder;

    public TrafficMetricsConsumer(TrafficAggregationService aggregationService,
                                  RedisFeatureWindowRepository redisRepository,
                                  TrafficHistoryRepository trafficHistoryRepository,
                                  AggregatedFeaturesPublisher publisher,
                                  FeatureMetricsRecorder metricsRecorder) {
        this.aggregationService = aggregationService;
        this.redisRepository = redisRepository;
        this.trafficHistoryRepository = trafficHistoryRepository;
        this.publisher = publisher;
        this.metricsRecorder = metricsRecorder;
    }

    @KafkaListener(topics = "${traffic.topics.traffic-metrics}", groupId = "feature-service")
    public void consume(TrafficMetricEvent event) {
        metricsRecorder.recordTrafficMetricConsumed(event);
        AggregatedFeaturesEvent aggregated = aggregationService.aggregate(event);
        metricsRecorder.recordAggregation(aggregated);
        redisRepository.save(aggregated);
        trafficHistoryRepository.save(aggregated);
        publisher.publish(aggregated);
    }
}
