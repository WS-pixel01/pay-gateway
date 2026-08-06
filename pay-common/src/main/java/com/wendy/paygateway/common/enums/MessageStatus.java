package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Outbox message status: NEW -&gt; SENT -&gt; CONSUMED; failures go to FAILED, and to DEAD once max retries are exhausted. */
@Getter
public enum MessageStatus {

    NEW("Pending dispatch"),
    SENT("Published to MQ"),
    CONSUMED("Acknowledged by consumer"),
    FAILED("Publish/consume failed, awaiting retry"),
    DEAD("Dead-lettered, needs manual intervention");

    private final String desc;

    MessageStatus(String desc) {
        this.desc = desc;
    }
}
