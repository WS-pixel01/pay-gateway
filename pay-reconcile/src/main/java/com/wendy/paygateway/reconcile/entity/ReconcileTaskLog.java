package com.wendy.paygateway.reconcile.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.ChannelType;
import lombok.Data;

import java.time.LocalDateTime;

/** Execution record of a reconciliation run. */
@Data
@TableName("reconcile_task_log")
public class ReconcileTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billDate;
    private ChannelType channelType;
    private Integer localCount;
    private Integer channelCount;
    private Integer diffCount;
    /** SUCCESS / FAIL。 */
    private String status;
    private Long costMs;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
