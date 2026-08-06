package com.wendy.paygateway.common.infra;

import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.infra.memory.InMemoryDistributedLock;
import com.wendy.paygateway.common.infra.memory.InMemoryIdempotentStore;
import com.wendy.paygateway.common.infra.memory.InMemorySlidingWindowRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryInfraTest {

    @Test
    @DisplayName("Only the first caller wins setIfAbsent")
    void setIfAbsentOnlyOnce() {
        InMemoryIdempotentStore store = new InMemoryIdempotentStore();
        assertTrue(store.setIfAbsent("k", "v1", Duration.ofSeconds(30)));
        assertFalse(store.setIfAbsent("k", "v2", Duration.ofSeconds(30)));
        assertEquals("v1", store.get("k"));
        store.delete("k");
        assertNull(store.get("k"));
    }

    @Test
    @DisplayName("Under heavy concurrency there is exactly one winner")
    void setIfAbsentIsThreadSafe() throws Exception {
        InMemoryIdempotentStore store = new InMemoryIdempotentStore();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (store.setIfAbsent("concurrent", "v", Duration.ofSeconds(30))) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, winners.get());
    }

    @Test
    @DisplayName("Sliding window rejects once the limit is exceeded")
    void rateLimiterRejectsOverLimit() {
        InMemorySlidingWindowRateLimiter limiter = new InMemorySlidingWindowRateLimiter();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("api", 3, Duration.ofSeconds(10)));
        }
        assertFalse(limiter.tryAcquire("api", 3, Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("The lock serialises concurrent increments, so no update is lost")
    void lockSerializesConcurrentUpdates() throws Exception {
        InMemoryDistributedLock lock = new InMemoryDistributedLock();
        AtomicInteger counter = new AtomicInteger();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    lock.executeWithLock("order:1", Duration.ofSeconds(5), Duration.ofSeconds(5), () -> {
                        int value = counter.get();
                        counter.set(value + 1);
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads, counter.get());
    }

    @Test
    @DisplayName("Failing to acquire the lock throws LOCK_FAILED")
    void lockTimeoutThrows() throws Exception {
        InMemoryDistributedLock lock = new InMemoryDistributedLock();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> lock.executeWithLock("k", Duration.ofSeconds(1), Duration.ofSeconds(5), () -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        try {
            org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                    () -> lock.executeWithLock("k", Duration.ofMillis(200), Duration.ofSeconds(5), () -> null));
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }
}
