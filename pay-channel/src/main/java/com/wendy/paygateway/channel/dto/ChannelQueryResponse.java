package com.wendy.paygateway.channel.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Channel order query response, used to reconcile actively when a webhook is lost. */
@Data
@Builder
public class ChannelQueryResponse {

    private boolean success;
    /** WAIT_PAY / SUCCESS / FAIL / CLOSED / NOT_EXIST。 */
    private String tradeStatus;
    private String channelTradeNo;
    private BigDecimal amount;
    private String errorMsg;
}
