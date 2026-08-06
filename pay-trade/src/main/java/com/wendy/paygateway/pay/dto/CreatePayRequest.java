package com.wendy.paygateway.pay.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Unified checkout request. */
@Data
@Schema(description = "Create pay order request")
public class CreatePayRequest {

    @Schema(description = "Upstream business system identifier", example = "mall")
    @NotBlank(message = "must not be blank")
    @Size(max = 32)
    private String bizSystem;

    @Schema(description = "Upstream business order number; the gateway keys idempotency on it", example = "MALL20260728001")
    @NotBlank(message = "must not be blank")
    @Size(max = 64)
    private String bizOrderNo;

    @Schema(description = "Paying user id", example = "U10001")
    @NotBlank(message = "must not be blank")
    private String userId;

    @Schema(description = "Payment amount in yuan", example = "99.90")
    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.01", message = "must be at least 0.01")
    private BigDecimal amount;

    @Schema(description = "Product title", example = "iPhone case")
    @NotBlank(message = "must not be blank")
    @Size(max = 128)
    private String subject;

    @Schema(description = "Product description")
    @Size(max = 512)
    private String body;

    @Schema(description = "Preferred channel; leave blank to let the gateway route by amount and region", example = "ALIPAY")
    private ChannelType channelType;

    @Schema(description = "User region, which influences channel routing", example = "CN")
    private String region = "CN";

    @Schema(description = "Pay order lifetime in minutes; defaults to the global 30 minutes when blank")
    private Integer expireMinutes;
}
