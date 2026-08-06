package com.wendy.paygateway.pay.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Checkout response. The idempotency aspect replays it, so it has to be Jackson-deserializable
 * (no-args constructor plus setters).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create pay order response")
public class CreatePayResponse {

    @Schema(description = "Gateway pay order number")
    private String payNo;
    @Schema(description = "Upstream business order number")
    private String bizOrderNo;
    @Schema(description = "Channel actually used")
    private ChannelType channelType;
    @Schema(description = "Payment amount")
    private BigDecimal amount;
    @Schema(description = "Pay order status")
    private PayOrderStatus status;
    @Schema(description = "Checkout URL or QR code payload; empty for balance payments")
    private String payUrl;
    @Schema(description = "Pay order expiry time")
    private LocalDateTime expireTime;
    @Schema(description = "Whether payment already succeeded synchronously (balance payments)")
    private boolean syncPaid;
}
