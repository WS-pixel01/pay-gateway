package com.wendy.paygateway.webhook.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.WebhookResult;
import com.wendy.paygateway.common.enums.ChannelType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Webhook log — the first-hand evidence when investigating a production money issue: the raw
 * payload, the signature verification result, whether it was a duplicate, and how long it took.
 */
@Data
@TableName("webhook_log")
public class WebhookLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private ChannelType channelType;
    /** PAY / REFUND。 */
    private String webhookType;
    private String outTradeNo;
    private String channelTradeNo;
    private String rawBody;
    private Integer signVerified;
    private WebhookResult result;
    private String remark;
    private Long costMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
