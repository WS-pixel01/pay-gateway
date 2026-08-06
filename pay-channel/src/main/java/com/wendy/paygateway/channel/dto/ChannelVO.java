package com.wendy.paygateway.channel.dto;

import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.common.enums.ChannelType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Outward-facing view of a channel config; secrets are always masked and never leave the gateway. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payment channel configuration")
public class ChannelVO {

    private ChannelType channelType;
    private String channelName;
    private String merchantId;
    private String appId;
    private String signType;
    private String maskedKey;
    private Boolean enabled;
    private Integer priority;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String supportRegions;
    private BigDecimal feeRate;

    public static ChannelVO from(PayChannelConfig config) {
        return ChannelVO.builder()
                .channelType(config.getChannelType())
                .channelName(config.getChannelName())
                .merchantId(config.getMerchantId())
                .appId(config.getAppId())
                .signType(config.getSignType())
                .maskedKey(mask("RSA2".equalsIgnoreCase(config.getSignType())
                        ? config.getPrivateKey() : config.getApiKey()))
                .enabled(config.available())
                .priority(config.getPriority())
                .minAmount(config.getMinAmount())
                .maxAmount(config.getMaxAmount())
                .supportRegions(config.getSupportRegions())
                .feeRate(config.getFeeRate())
                .build();
    }

    private static String mask(String secret) {
        if (secret == null || secret.length() < 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
}
