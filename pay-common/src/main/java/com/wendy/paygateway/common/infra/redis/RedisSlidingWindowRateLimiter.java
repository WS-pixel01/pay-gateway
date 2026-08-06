package com.wendy.paygateway.common.infra.redis;

import com.wendy.paygateway.common.infra.SlidingWindowRateLimiter;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Redis ZSET sliding-window rate limiter. The whole sequence runs inside a Lua script so that
 * "evict + count + insert" is atomic.
 */
@RequiredArgsConstructor
public class RedisSlidingWindowRateLimiter implements SlidingWindowRateLimiter {

    private static final String LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMs)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, windowMs)
            return 1
            """;

    private final RedissonClient redissonClient;

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Long allowed = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA,
                RScript.ReturnType.INTEGER,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return allowed != null && allowed == 1L;
    }
}
