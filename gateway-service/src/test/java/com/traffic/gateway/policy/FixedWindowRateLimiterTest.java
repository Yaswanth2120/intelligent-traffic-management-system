package com.traffic.gateway.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowRateLimiterTest {

    @Test
    void shouldRejectRequestsBeyondConfiguredLimit() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

        assertTrue(limiter.allow("orders-route", 2));
        assertTrue(limiter.allow("orders-route", 2));
        assertFalse(limiter.allow("orders-route", 2));
    }
}
