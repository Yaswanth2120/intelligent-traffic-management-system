package com.traffic.gateway.config;

import com.traffic.gateway.metrics.TrafficTopicsProperties;
import com.traffic.gateway.policy.GatewayTrafficProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TrafficTopicsProperties.class, GatewayTrafficProperties.class})
public class GatewayConfig {
}
