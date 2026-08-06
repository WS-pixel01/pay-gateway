package com.wendy.paygateway.message.mq;

/** MQ topic constants. */
public final class MqTopics {

    /** Payment result pushed to the upstream business system. */
    public static final String PAY_RESULT = "pay.result";
    /** Refund result pushed to the upstream business system. */
    public static final String REFUND_RESULT = "refund.result";
    /** Pay order closed; upstream releases the reserved stock on this event. */
    public static final String PAY_CLOSED = "pay.closed";

    private MqTopics() {
    }
}
