package com.traffic.feature.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.feature.model.AggregatedFeaturesEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisFeatureWindowRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisFeatureWindowRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(AggregatedFeaturesEvent event) {
        try {
            redisTemplate.opsForValue().set(redisKey(event.route()), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize aggregated feature event", ex);
        }
    }

    private String redisKey(String route) {
        return "traffic_window:" + route;
    }
}
