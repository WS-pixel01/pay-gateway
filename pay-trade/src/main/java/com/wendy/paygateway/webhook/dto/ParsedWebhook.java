package com.wendy.paygateway.webhook.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Unified shape of a webhook payload, whichever channel it came from. */
@Data
@Builder
public class ParsedWebhook {

    /** Our pay order number or refund order number. */
    private String outTradeNo;
    /** Channel trade number or channel refund number. */
    private String channelTradeNo;
    private BigDecimal amount;
    /** True when the payment (or refund) succeeded. */
    private boolean success;
    private String rawStatus;
}
