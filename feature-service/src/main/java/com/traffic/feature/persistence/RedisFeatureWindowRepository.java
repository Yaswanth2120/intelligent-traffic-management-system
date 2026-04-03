package com.traffic.feature.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.feature.metrics.FeatureMetricsRecorder;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisFeatureWindowRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FeatureMetricsRecorder metricsRecorder;

    public RedisFeatureWindowRepository(StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper,
                                        FeatureMetricsRecorder metricsRecorder) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metricsRecorder = metricsRecorder;
    }

    public void save(AggregatedFeaturesEvent event) {
        try {
            redisTemplate.opsForValue().set(redisKey(event.route()), objectMapper.writeValueAsString(event));
            metricsRecorder.recordStorageResult("redis", event.route(), true);
        } catch (JsonProcessingException ex) {
            metricsRecorder.recordStorageResult("redis", event.route(), false);
            throw new IllegalStateException("Failed to serialize aggregated feature event", ex);
        } catch (RuntimeException ex) {
            metricsRecorder.recordStorageResult("redis", event.route(), false);
            throw ex;
        }
    }

    private String redisKey(String route) {
        return "traffic_window:" + route;
    }
}
