package com.wendy.paygateway.common.infra.memory;

import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.infra.DistributedLock;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory distributed lock for the single-node sandbox. Same semantics as a Redisson RLock:
 * reentrant, with a bounded wait.
 */
@Slf4j
public class InMemoryDistributedLock implements DistributedLock {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("[Distributed lock] acquire failed key={} wait={}ms", key, waitTime.toMillis());
                throw BizException.of(ErrorCode.LOCK_FAILED, key);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.LOCK_FAILED, key);
        } finally {
            if (acquired) {
                lock.unlock();
                // Reclaim the entry once nobody holds it and nobody is queued, so the map cannot grow forever
                if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                    locks.remove(key, lock);
                }
            }
        }
    }
}
