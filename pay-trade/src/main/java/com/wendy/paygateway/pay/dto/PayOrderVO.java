package com.wendy.paygateway.pay.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.pay.entity.PayOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay order view object; internal fields such as {@code version} are not exposed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Pay order details")
public class PayOrderVO {

    private String payNo;
    private String bizSystem;
    private String bizOrderNo;
    private String userId;
    private ChannelType channelType;
    private String subject;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal refundedAmount;
    private BigDecimal refundableAmount;
    private PayOrderStatus status;
    @Schema(description = "Human-readable status description")
    private String statusDesc;
    private String channelTradeNo;
    private String payUrl;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime closeTime;
    private String failReason;
    private LocalDateTime createTime;

    public static PayOrderVO from(PayOrder order) {
        return PayOrderVO.builder()
                .payNo(order.getPayNo())
                .bizSystem(order.getBizSystem())
                .bizOrderNo(order.getBizOrderNo())
                .userId(order.getUserId())
                .channelType(order.getChannelType())
                .subject(order.getSubject())
                .amount(order.getAmount())
                .paidAmount(order.getPaidAmount())
                .refundedAmount(order.getRefundedAmount())
                .refundableAmount(order.refundableAmount())
                .status(order.getStatus())
                .statusDesc(order.getStatus() == null ? null : order.getStatus().getDesc())
                .channelTradeNo(order.getChannelTradeNo())
                .payUrl(order.getPayUrl())
                .expireTime(order.getExpireTime())
                .payTime(order.getPayTime())
                .closeTime(order.getCloseTime())
                .failReason(order.getFailReason())
                .createTime(order.getCreateTime())
                .build();
    }
}
