package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Reconciliation discrepancy type. */
@Getter
public enum DiffType {

    CHANNEL_MISS("Missing at channel: local order succeeded but the channel bill has no such entry (one-sided entry)"),
    LOCAL_MISS("Missing locally: the channel billed it but we have no successful record (one-sided entry)"),
    AMOUNT_MISMATCH("Amount mismatch"),
    STATUS_MISMATCH("Trade status mismatch");

    private final String desc;

    DiffType(String desc) {
        this.desc = desc;
    }
}
