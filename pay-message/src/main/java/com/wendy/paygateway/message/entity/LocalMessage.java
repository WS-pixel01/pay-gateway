package com.wendy.paygateway.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.MessageStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox (local message) table.
 *
 * <p>Once a payment succeeds, "update the pay order" and "notify the upstream business system"
 * must both happen. Publishing to MQ inside the transaction gives you either "transaction rolled
 * back but the message was already sent" or "transaction committed but MQ was down". So the
 * message is written in the same local transaction as the business data and only published after
 * the commit; failed publishes are retried by a scheduled compensation task. That is how eventual
 * consistency is achieved.
 */
@Data
@TableName("local_message")
public class LocalMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private String topic;
    /** Business key, e.g. the pay order number; makes troubleshooting easy. */
    private String bizKey;
    private String payload;

    private MessageStatus status;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
