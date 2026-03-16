package com.traffic.gateway.config;

import com.traffic.gateway.metrics.TrafficTopicsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrafficTopicsProperties.class)
public class GatewayConfig {
}
