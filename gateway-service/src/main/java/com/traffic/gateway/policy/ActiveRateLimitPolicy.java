package com.traffic.gateway.policy;

public record ActiveRateLimitPolicy(
        int rateLimitRps,
        long expiresAtEpochSec,
        String reason
) {
    public boolean isExpired(long nowEpochSec) {
        return nowEpochSec >= expiresAtEpochSec;
    }
}
