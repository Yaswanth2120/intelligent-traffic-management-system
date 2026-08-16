package com.traffic.feature.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeatureServiceProperties.class)
public class FeatureServiceConfig {

    // feature-service has no web starter on its classpath, so Spring Boot's
    // JacksonAutoConfiguration never registers a default ObjectMapper bean.
    // RedisFeatureWindowRepository needs one to serialize AggregatedFeaturesEvent.
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
