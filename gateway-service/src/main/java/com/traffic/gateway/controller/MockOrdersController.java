package com.traffic.gateway.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MockOrdersController {

    @GetMapping("/mock/orders")
    public Map<String, Object> getOrders() {
        return Map.of(
                "service", "orders",
                "status", "ok",
                "source", "gateway-demo-backend"
        );
    }
}
