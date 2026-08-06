package com.wendy.paygateway.common.infra;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Distributed lock abstraction: concurrent refunds, concurrent webhooks and concurrent balance
 * deductions are all serialised through it.
 */
public interface DistributedLock {

    /**
     * Run the action while holding the lock; throws {@code BizException(LOCK_FAILED)} if the lock
     * cannot be acquired.
     *
     * @param key       lock key, e.g. pay:lock:refund:P2026...
     * @param waitTime  maximum time to wait for the lock
     * @param leaseTime lease after which the lock auto-releases, so a crashed node cannot deadlock others
     */
    <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action);

    default <T> T executeWithLock(String key, Supplier<T> action) {
        return executeWithLock(key, Duration.ofSeconds(3), Duration.ofSeconds(30), action);
    }

    /** Void variant; deliberately named differently to avoid Runnable/Supplier overload ambiguity. */
    default void runWithLock(String key, Runnable action) {
        executeWithLock(key, Duration.ofSeconds(3), Duration.ofSeconds(30), () -> {
            action.run();
            return null;
        });
    }
}
