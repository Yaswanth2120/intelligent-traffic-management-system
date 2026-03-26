package com.traffic.feature.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeatureServiceProperties.class)
public class FeatureServiceConfig {
}
