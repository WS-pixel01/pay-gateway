package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Pay order status. */
@Getter
public enum PayOrderStatus {

    WAIT_PAY("Awaiting payment"),
    SUCCESS("Paid"),
    FAIL("Payment failed"),
    PART_REFUNDED("Partially refunded"),
    REFUNDED("Fully refunded"),
    CLOSED("Closed");

    private final String desc;

    PayOrderStatus(String desc) {
        this.desc = desc;
    }

    /** Whether money has been collected, i.e. the order is refundable. */
    public boolean isPaid() {
        return this == SUCCESS || this == PART_REFUNDED;
    }

    /** Whether this is a terminal state (no further transitions apart from refunds). */
    public boolean isFinal() {
        return this == FAIL || this == REFUNDED || this == CLOSED;
    }
}
