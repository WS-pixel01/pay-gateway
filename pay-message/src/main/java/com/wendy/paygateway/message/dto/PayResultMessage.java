package com.wendy.paygateway.message.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Payment/refund result message pushed to the upstream business system. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResultMessage {

    private String payNo;
    private String bizSystem;
    private String bizOrderNo;
    private String userId;
    private ChannelType channelType;
    private String channelTradeNo;
    private BigDecimal amount;
    /** SUCCESS / FAIL / CLOSED / REFUNDED / PART_REFUNDED. */
    private String status;
    /** Refund scenarios also carry the refund order number and the amount refunded this time. */
    private String refundNo;
    private BigDecimal refundAmount;
    private LocalDateTime occurTime;
}
