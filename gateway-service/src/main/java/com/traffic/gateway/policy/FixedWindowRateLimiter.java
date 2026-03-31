package com.traffic.gateway.policy;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class FixedWindowRateLimiter {

    private final ConcurrentHashMap<String, WindowCounter> routeCounters = new ConcurrentHashMap<>();

    public boolean allow(String route, int limitRps) {
        long currentSecond = Instant.now().getEpochSecond();
        WindowCounter counter = routeCounters.compute(route, (key, existing) -> {
            if (existing == null || existing.epochSecond != currentSecond) {
                return new WindowCounter(currentSecond);
            }
            return existing;
        });

        return counter.count.incrementAndGet() <= limitRps;
    }

    private static final class WindowCounter {
        private final long epochSecond;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long epochSecond) {
            this.epochSecond = epochSecond;
        }
    }
}
