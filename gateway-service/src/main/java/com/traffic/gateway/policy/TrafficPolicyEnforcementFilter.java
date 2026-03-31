package com.traffic.gateway.policy;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TrafficPolicyEnforcementFilter implements GlobalFilter, Ordered {

    private final ActivePolicyStore activePolicyStore;
    private final FixedWindowRateLimiter rateLimiter;
    private final GatewayTrafficProperties properties;

    public TrafficPolicyEnforcementFilter(ActivePolicyStore activePolicyStore,
                                          FixedWindowRateLimiter rateLimiter,
                                          GatewayTrafficProperties properties) {
        this.activePolicyStore = activePolicyStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : exchange.getRequest().getPath().value();
        ActiveRateLimitPolicy policy = activePolicyStore.get(routeId);
        int effectiveLimit = policy != null ? policy.rateLimitRps() : properties.enforcement().defaultLimitRps();

        if (effectiveLimit > 0 && !rateLimiter.allow(routeId, effectiveLimit)) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
