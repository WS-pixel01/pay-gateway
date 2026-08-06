package com.wendy.paygateway.message.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** MQ envelope: consumers dedupe on messageId, while payload carries the actual business message. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageEnvelope {

    private String messageId;
    private String topic;
    private String bizKey;
    private String payload;
    private long timestamp;
}
