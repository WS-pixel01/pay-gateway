package com.wendy.paygateway.pay.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.util.MoneyUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pay order. One pay order corresponds to one upstream business order, and its whole lifecycle is
 * driven by the state machine.
 */
@Data
@TableName("pay_order")
public class PayOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Gateway pay order number. */
    private String payNo;
    /** Upstream business system identifier, e.g. mall or member. */
    private String bizSystem;
    /** Upstream business order number; together with bizSystem it forms the unique key that stops duplicate orders at the DB level. */
    private String bizOrderNo;

    private String userId;
    private ChannelType channelType;
    private String subject;
    private String body;

    /** Amount due. */
    private BigDecimal amount;
    /** Amount actually paid, written once the webhook amount check passes. */
    private BigDecimal paidAmount;
    /** Amount refunded so far. */
    private BigDecimal refundedAmount;

    private PayOrderStatus status;
    private String region;
    private String clientIp;
    private String channelTradeNo;
    private String payUrl;

    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime closeTime;
    private String failReason;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** Refundable balance = paid - already refunded. */
    public BigDecimal refundableAmount() {
        return MoneyUtil.subtract(paidAmount, refundedAmount);
    }

    public boolean expired() {
        return expireTime != null && LocalDateTime.now().isAfter(expireTime);
    }
}
