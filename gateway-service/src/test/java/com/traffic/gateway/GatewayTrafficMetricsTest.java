package com.traffic.gateway;

import com.traffic.gateway.metrics.TrafficMetricEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTrafficMetricsTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private KafkaTemplate<String, TrafficMetricEvent> kafkaTemplate;

    @Test
    void shouldRouteOrdersRequestAndPublishMetric() {
        webTestClient.get()
                .uri("/api/orders")
                .header("X-Client-Id", "test-client")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("orders");

        verify(kafkaTemplate, timeout(1000))
                .send(anyString(), anyString(), any(TrafficMetricEvent.class));
    }
}
