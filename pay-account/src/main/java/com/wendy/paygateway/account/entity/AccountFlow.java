package com.wendy.paygateway.account.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money ledger entry. There is a unique index on (biz_no, direction), so even if every
 * application-level idempotency guard fails, the database still prevents charging the same
 * payment twice.
 */
@Data
@TableName("account_flow")
public class AccountFlow {

    public static final String OUT = "OUT";
    public static final String IN = "IN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    /** Pay order number or refund order number. */
    private String bizNo;
    /** OUT = debit, IN = credit. */
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
