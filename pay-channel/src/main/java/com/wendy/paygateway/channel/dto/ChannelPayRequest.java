package com.wendy.paygateway.channel.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Unified request for placing an order with a channel. */
@Data
@Builder
public class ChannelPayRequest {

    private String payNo;
    private String userId;
    private BigDecimal amount;
    private String subject;
    private String body;
    private String clientIp;
    private LocalDateTime expireTime;
    /** Async webhook URL for the channel. */
    private String notifyUrl;
}
