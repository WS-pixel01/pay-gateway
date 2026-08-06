package com.wendy.paygateway.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API idempotency annotation: guards against duplicate submissions using a unique business number
 * plus a distributed key-value store.
 *
 * <p>Typical usage — placing an order twice with the same business order number returns the first
 * result instead of creating a second pay order:
 * <pre>{@code
 * @PayIdempotent(prefix = "pay:create", key = "#request.bizSystem + ':' + #request.bizOrderNo")
 * public R<CreatePayResponse> create(CreatePayRequest request) { ... }
 * }</pre>
 *
 * <p>Three outcomes: the first request executes normally and its return value is cached; a
 * duplicate that arrives while the first one is still running gets {@code PROCESSING}; a duplicate
 * that arrives after completion replays the first response. That is real idempotency — "same call,
 * same result" — rather than a blunt rejection.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PayIdempotent {

    /** Key prefix, used to separate business scenarios. */
    String prefix();

    /** SpEL expression that extracts the unique business number from the method arguments. */
    String key();

    /** Idempotency window, in seconds. */
    int ttlSeconds() default 300;

    /** Whether to replay the first result; false makes duplicate requests fail outright. */
    boolean replayResult() default true;
}
