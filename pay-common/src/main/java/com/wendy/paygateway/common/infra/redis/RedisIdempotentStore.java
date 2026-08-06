package com.wendy.paygateway.common.infra.redis;

import com.wendy.paygateway.common.infra.IdempotentStore;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;

/** Redis-backed idempotency store, the production implementation (pay.infra.type=redis). */
@RequiredArgsConstructor
public class RedisIdempotentStore implements IdempotentStore {

    private final RedissonClient redissonClient;

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        // Equivalent to SET key value NX PX ttl; a single Redis command, so it is atomic
        return bucket(key).setIfAbsent(value, ttl);
    }

    @Override
    public String get(String key) {
        return bucket(key).get();
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        bucket(key).set(value, ttl);
    }

    @Override
    public void delete(String key) {
        bucket(key).delete();
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
