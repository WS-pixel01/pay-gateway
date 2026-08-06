package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Webhook handling outcome, persisted to webhook_log for investigating production money issues. */
@Getter
public enum WebhookResult {

    PROCESSED("Processed successfully"),
    DUPLICATE("Duplicate webhook, ignored idempotently"),
    VERIFY_FAIL("Signature verification failed"),
    BIZ_FAIL("Business processing failed");

    private final String desc;

    WebhookResult(String desc) {
        this.desc = desc;
    }
}
