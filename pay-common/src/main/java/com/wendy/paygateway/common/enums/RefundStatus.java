package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Refund order status. */
@Getter
public enum RefundStatus {

    PROCESSING("Refund in progress"),
    SUCCESS("Refunded"),
    FAIL("Refund failed");

    private final String desc;

    RefundStatus(String desc) {
        this.desc = desc;
    }
}
