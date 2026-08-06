package com.wendy.paygateway.reconcile.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.ChannelType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One parsed row from a channel bill file. */
@Data
@TableName("channel_bill")
public class ChannelBill {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** yyyy-MM-dd。 */
    private String billDate;
    private ChannelType channelType;
    /** Our own pay order number. */
    private String outTradeNo;
    private String channelTradeNo;
    private BigDecimal amount;
    private BigDecimal fee;
    /** SUCCESS / REFUND。 */
    private String tradeStatus;
    private LocalDateTime tradeTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
