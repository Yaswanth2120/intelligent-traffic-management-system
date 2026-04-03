package com.traffic.gateway.metrics;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TrafficMetricsFilter implements GlobalFilter, Ordered {

    private final TrafficMetricPublisher publisher;
    private final GatewayMetricsRecorder metricsRecorder;

    public TrafficMetricsFilter(TrafficMetricPublisher publisher,
                                GatewayMetricsRecorder metricsRecorder) {
        this.publisher = publisher;
        this.metricsRecorder = metricsRecorder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signalType -> {
                    TrafficMetricEvent event = buildEvent(exchange, startNanos);
                    metricsRecorder.recordRequest(event);
                    publisher.publish(event);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private TrafficMetricEvent buildEvent(ServerWebExchange exchange, long startNanos) {
        ServerHttpRequest request = exchange.getRequest();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        int status = Optional.ofNullable(exchange.getResponse().getStatusCode())
                .map(httpStatus -> httpStatus.value())
                .orElse(200);

        return new TrafficMetricEvent(
                route != null ? route.getId() : request.getPath().value(),
                Instant.now().getEpochSecond(),
                request.getMethod() != null ? request.getMethod().name() : "UNKNOWN",
                status,
                (System.nanoTime() - startNanos) / 1_000_000,
                resolveClientId(request.getHeaders()),
                resolveClientIp(request)
        );
    }

    private String resolveClientId(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst("X-Client-Id"))
                .filter(value -> !value.isBlank())
                .orElse("anonymous");
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
    }
}
