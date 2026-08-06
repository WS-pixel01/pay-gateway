package com.wendy.paygateway.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sliding-window rate-limit annotation, used to stop bulk pay-order creation and refund-endpoint
 * abuse.
 *
 * <p>{@link #dimension()} picks the bucketing dimension: global, caller IP, or a business field
 * resolved via SpEL.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** Name of the rate-limited resource. */
    String name();

    /** Maximum number of requests allowed inside the window. */
    int limit() default 20;

    /** Window length, in seconds. */
    int windowSeconds() default 1;

    Dimension dimension() default Dimension.IP;

    /** Only used when dimension = SPEL, e.g. "#request.userId". */
    String key() default "";

    enum Dimension {
        /** One bucket for the whole endpoint. */
        GLOBAL,
        /** One bucket per caller IP. */
        IP,
        /** One bucket per business field (user, merchant, ...). */
        SPEL
    }
}
