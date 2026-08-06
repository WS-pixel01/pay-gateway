package com.wendy.paygateway.refund.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.RefundStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Refund order. The unique index on biz_refund_no stops duplicate refunds at the database level. */
@Data
@TableName("refund_order")
public class RefundOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;
    private String payNo;
    /** Upstream refund request number; the idempotency key. */
    private String bizRefundNo;
    private ChannelType channelType;
    private BigDecimal amount;
    private RefundStatus status;
    private String reason;
    private String channelRefundNo;
    private LocalDateTime refundTime;
    private String failReason;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
