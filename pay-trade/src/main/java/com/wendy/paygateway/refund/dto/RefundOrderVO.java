package com.wendy.paygateway.refund.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.RefundStatus;
import com.wendy.paygateway.refund.entity.RefundOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Refund order view object. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Refund order details")
public class RefundOrderVO {

    private String refundNo;
    private String payNo;
    private String bizRefundNo;
    private ChannelType channelType;
    private BigDecimal amount;
    private RefundStatus status;
    private String statusDesc;
    private String channelRefundNo;
    private String reason;
    private String failReason;
    private LocalDateTime refundTime;
    private LocalDateTime createTime;

    public static RefundOrderVO from(RefundOrder order) {
        return RefundOrderVO.builder()
                .refundNo(order.getRefundNo())
                .payNo(order.getPayNo())
                .bizRefundNo(order.getBizRefundNo())
                .channelType(order.getChannelType())
                .amount(order.getAmount())
                .status(order.getStatus())
                .statusDesc(order.getStatus() == null ? null : order.getStatus().getDesc())
                .channelRefundNo(order.getChannelRefundNo())
                .reason(order.getReason())
                .failReason(order.getFailReason())
                .refundTime(order.getRefundTime())
                .createTime(order.getCreateTime())
                .build();
    }
}
