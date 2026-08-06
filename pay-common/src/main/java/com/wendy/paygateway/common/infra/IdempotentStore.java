package com.wendy.paygateway.common.infra;

import java.time.Duration;

/**
 * Idempotency store abstraction. The default is the in-memory implementation (zero dependencies
 * for the local sandbox); switching to Redis in production only takes
 * {@code pay.infra.type=redis} — business code never notices.
 */
public interface IdempotentStore {

    /** {@code SET key value NX EX ttl}; returns true when this caller won the slot. */
    boolean setIfAbsent(String key, String value, Duration ttl);

    String get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);
}
