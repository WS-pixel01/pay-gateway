package com.wendy.paygateway.common.infra.memory;

import com.wendy.paygateway.common.infra.SlidingWindowRateLimiter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter: each key keeps a queue of timestamps; entries that fell
 * out of the window are dropped first, then the remaining count is compared against the limit.
 * This is the single-node equivalent of the Redis ZSET approach.
 */
public class InMemorySlidingWindowRateLimiter implements SlidingWindowRateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long start = now - window.toMillis();
        Deque<Long> queue = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() <= start) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                return false;
            }
            queue.offerLast(now);
            return true;
        }
    }

    /** Evict keys that have not seen traffic for a while. */
    public void evictIdle(Duration idle) {
        long threshold = System.currentTimeMillis() - idle.toMillis();
        windows.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                return q.isEmpty() || q.peekLast() < threshold;
            }
        });
    }
}
