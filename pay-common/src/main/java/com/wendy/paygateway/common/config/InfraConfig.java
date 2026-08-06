package com.wendy.paygateway.common.config;

import com.wendy.paygateway.common.infra.DistributedLock;
import com.wendy.paygateway.common.infra.IdempotentStore;
import com.wendy.paygateway.common.infra.SlidingWindowRateLimiter;
import com.wendy.paygateway.common.infra.memory.InMemoryDistributedLock;
import com.wendy.paygateway.common.infra.memory.InMemoryIdempotentStore;
import com.wendy.paygateway.common.infra.memory.InMemorySlidingWindowRateLimiter;
import com.wendy.paygateway.common.infra.redis.RedisIdempotentStore;
import com.wendy.paygateway.common.infra.redis.RedisSlidingWindowRateLimiter;
import com.wendy.paygateway.common.infra.redis.RedissonDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Picks the implementation for the three infrastructure primitives: idempotency store,
 * distributed lock and rate limiter.
 *
 * <p>{@code pay.infra.type=memory} (the default): fully in-memory, so the local sandbox runs the
 * whole flow without Redis.
 * <br>{@code pay.infra.type=redis}: build a RedissonClient by hand and switch to the Redis-backed
 * implementations.
 *
 * <p>redisson-spring-boot-starter is deliberately not on the classpath: its auto-configuration
 * would connect to Redis on startup and break the zero-dependency sandbox.
 */
@Slf4j
@Configuration
public class InfraConfig {

    @Configuration
    @ConditionalOnProperty(name = "pay.infra.type", havingValue = "memory", matchIfMissing = true)
    static class MemoryInfraConfig {

        @Bean
        public IdempotentStore idempotentStore() {
            log.info("[Infra] idempotency store = in-memory (local sandbox mode)");
            return new InMemoryIdempotentStore();
        }

        @Bean
        public DistributedLock distributedLock() {
            log.info("[Infra] distributed lock = in-memory (local sandbox mode)");
            return new InMemoryDistributedLock();
        }

        @Bean
        public SlidingWindowRateLimiter slidingWindowRateLimiter() {
            log.info("[Infra] rate limiter = in-memory sliding window (local sandbox mode)");
            return new InMemorySlidingWindowRateLimiter();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "pay.infra.type", havingValue = "redis")
    static class RedisInfraConfig {

        @Bean(destroyMethod = "shutdown")
        public RedissonClient redissonClient(PayProperties properties) {
            PayProperties.Redis redis = properties.getInfra().getRedis();
            Config config = new Config();
            SingleServerConfig server = config.useSingleServer()
                    .setAddress(redis.getAddress())
                    .setDatabase(redis.getDatabase());
            if (StringUtils.hasText(redis.getPassword())) {
                server.setPassword(redis.getPassword());
            }
            log.info("[Infra] connecting to Redis {}", redis.getAddress());
            return Redisson.create(config);
        }

        @Bean
        public IdempotentStore idempotentStore(RedissonClient redissonClient) {
            return new RedisIdempotentStore(redissonClient);
        }

        @Bean
        public DistributedLock distributedLock(RedissonClient redissonClient) {
            return new RedissonDistributedLock(redissonClient);
        }

        @Bean
        public SlidingWindowRateLimiter slidingWindowRateLimiter(RedissonClient redissonClient) {
            return new RedisSlidingWindowRateLimiter(redissonClient);
        }
    }
}
