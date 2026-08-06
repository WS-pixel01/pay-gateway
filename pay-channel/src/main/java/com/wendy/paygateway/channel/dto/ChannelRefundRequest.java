package com.wendy.paygateway.channel.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Unified request for issuing a refund through a channel. */
@Data
@Builder
public class ChannelRefundRequest {

    private String refundNo;
    private String payNo;
    private String channelTradeNo;
    private String userId;
    /** Amount refunded by this request. */
    private BigDecimal refundAmount;
    /** Total amount of the original pay order; required by the WeChat refund API. */
    private BigDecimal totalAmount;
    private String reason;
    private String notifyUrl;
}
