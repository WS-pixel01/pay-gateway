package com.wendy.paygateway.common.infra;

import java.time.Duration;

/** Sliding-window rate limiter abstraction. */
public interface SlidingWindowRateLimiter {

    /**
     * Try to admit one request.
     *
     * @param key    rate-limit dimension, e.g. pay:rl:createPay:U10001
     * @param limit  maximum number of requests allowed inside the window
     * @param window window length
     * @return true to admit, false when the limit is hit
     */
    boolean tryAcquire(String key, int limit, Duration window);
}
