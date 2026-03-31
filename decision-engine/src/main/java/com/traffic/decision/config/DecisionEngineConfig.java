package com.traffic.decision.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DecisionEngineProperties.class)
public class DecisionEngineConfig {
}
