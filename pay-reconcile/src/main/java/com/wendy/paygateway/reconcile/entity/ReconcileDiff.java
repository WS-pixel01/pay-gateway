package com.wendy.paygateway.reconcile.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.DiffType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Discrepancy record: missing entry, amount mismatch, status mismatch or one-sided entry. */
@Data
@TableName("reconcile_diff")
public class ReconcileDiff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billDate;
    private ChannelType channelType;
    private String outTradeNo;
    private String channelTradeNo;
    private BigDecimal localAmount;
    private BigDecimal channelAmount;
    private String localStatus;
    private String channelStatus;
    private DiffType diffType;
    /** 0 = open, 1 = handled. */
    private Integer handled;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
