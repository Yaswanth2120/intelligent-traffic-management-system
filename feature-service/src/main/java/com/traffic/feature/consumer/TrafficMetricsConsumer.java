package com.traffic.feature.consumer;

import com.traffic.feature.aggregation.TrafficAggregationService;
import com.traffic.feature.metrics.FeatureMetricsRecorder;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import com.traffic.feature.model.TrafficMetricEvent;
import com.traffic.feature.persistence.RedisFeatureWindowRepository;
import com.traffic.feature.persistence.TrafficHistoryRepository;
import com.traffic.feature.publisher.AggregatedFeaturesPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TrafficMetricsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrafficMetricsConsumer.class);

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

        // Redis is a hot-path cache the gateway/dashboards read for recent
        // state; it is NOT the durable record. Per docs/architecture.md's
        // failure handling contract ("Redis unavailable: feature service
        // continues durable writes to PostgreSQL and rebuilds hot state
        // later"), a Redis failure must not stop this method from persisting
        // to Postgres or publishing downstream -- confirmed by chaos testing
        // (docs/chaos-testing.md) that, before this fix, a Redis outage
        // silently dropped every event's Postgres row and Kafka publish too.
        try {
            redisRepository.save(aggregated);
        } catch (RuntimeException ex) {
            log.warn("Continuing without Redis cache update for route {} (durable write still proceeds)",
                    aggregated.route(), ex);
        }

        trafficHistoryRepository.save(aggregated);
        publisher.publish(aggregated);
    }
}
