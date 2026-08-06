package com.wendy.paygateway.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business number generator: prefix + timestamp + machine id + sequence. Monotonically
 * increasing on a single node and easy to read at a glance.
 *
 * <p>For a distributed deployment, turn MACHINE_ID into a config property or swap the whole
 * thing for a snowflake generator.
 */
public final class IdGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong SEQ = new AtomicLong(0);
    private static final String MACHINE_ID = "01";

    private IdGenerator() {
    }

    /** Pay order number. */
    public static String payNo() {
        return next("P");
    }

    /** Refund order number. */
    public static String refundNo() {
        return next("R");
    }

    /** Channel trade number (produced by the simulated third party). */
    public static String channelTradeNo(String channel) {
        return next(channel.substring(0, 2).toUpperCase());
    }

    /** Message id. */
    public static String messageId() {
        return "MSG" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String next(String prefix) {
        long seq = SEQ.incrementAndGet() % 1000000L;
        return prefix + LocalDateTime.now().format(FMT) + MACHINE_ID + String.format("%06d", seq);
    }
}
