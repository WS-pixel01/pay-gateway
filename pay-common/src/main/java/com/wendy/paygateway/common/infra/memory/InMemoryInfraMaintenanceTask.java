package com.wendy.paygateway.common.infra.memory;

import com.wendy.paygateway.common.infra.IdempotentStore;
import com.wendy.paygateway.common.infra.SlidingWindowRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Housekeeping for the in-memory infrastructure. The Redis implementations expire entries via TTL;
 * the in-memory ones have to reclaim them explicitly, otherwise a long-running process piles up
 * idempotency keys and rate-limit windows on the heap.
 */
@Component
@RequiredArgsConstructor
public class InMemoryInfraMaintenanceTask {

    private final IdempotentStore idempotentStore;
    private final SlidingWindowRateLimiter rateLimiter;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evict() {
        if (idempotentStore instanceof InMemoryIdempotentStore store) {
            store.evictExpired();
        }
        if (rateLimiter instanceof InMemorySlidingWindowRateLimiter limiter) {
            limiter.evictIdle(Duration.ofMinutes(10));
        }
    }
}
