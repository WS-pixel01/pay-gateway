package com.wendy.paygateway.channel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wendy.paygateway.common.enums.ChannelType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Payment channel configuration: merchant id, keys, webhook URL, on/off switch and routing rules. */
@Data
@TableName("pay_channel_config")
public class PayChannelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private ChannelType channelType;
    private String channelName;
    private String merchantId;
    private String appId;

    /** Merchant private key for RSA2 signing; generated automatically on sandbox startup. */
    private String privateKey;
    /** Channel public key for RSA2 verification. */
    private String publicKey;
    /** MD5 signing secret. */
    private String apiKey;
    /** AES-GCM payload encryption key (Base64). */
    private String aesKey;

    /** MD5 or RSA2. */
    private String signType;
    private String gatewayUrl;
    private String notifyUrl;

    /** 1 = enabled, 0 = disabled. */
    private Integer enabled;
    /** Lower value means higher priority. */
    private Integer priority;

    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    /** Supported regions, comma separated; ALL means no restriction. */
    private String supportRegions;
    private BigDecimal feeRate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * Whether the channel is usable. The name deliberately breaks the getter convention so Jackson
     * does not see it as a second "enabled" property alongside getEnabled().
     */
    public boolean available() {
        return enabled != null && enabled == 1;
    }

    /** Whether the amount falls inside this channel's allowed range. */
    public boolean supportAmount(BigDecimal amount) {
        return amount.compareTo(minAmount) >= 0 && amount.compareTo(maxAmount) <= 0;
    }

    /** Whether this channel serves the given region. */
    public boolean supportRegion(String region) {
        if (supportRegions == null || supportRegions.isBlank() || "ALL".equalsIgnoreCase(supportRegions)) {
            return true;
        }
        for (String r : supportRegions.split(",")) {
            if (r.trim().equalsIgnoreCase(region)) {
                return true;
            }
        }
        return false;
    }
}
