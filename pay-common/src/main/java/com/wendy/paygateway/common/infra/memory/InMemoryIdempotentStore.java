package com.wendy.paygateway.common.infra.memory;

import com.wendy.paygateway.common.infra.IdempotentStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory idempotency store with the same semantics as Redis {@code SET NX EX}.
 * Single-node sandbox only.
 */
@Slf4j
public class InMemoryIdempotentStore implements IdempotentStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        store.compute(key, (k, old) -> {
            if (old != null && !old.expired()) {
                return old;
            }
            acquired.set(true);
            return new Entry(value, expireAt);
        });
        return acquired.get();
    }

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    /** Periodically evict expired keys so memory cannot grow without bound (Redis does this via TTL). */
    public void evictExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().expired());
        if (before != store.size()) {
            log.debug("[Idempotency store] evicted expired keys {} -> {}", before, store.size());
        }
    }

    private record Entry(String value, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
