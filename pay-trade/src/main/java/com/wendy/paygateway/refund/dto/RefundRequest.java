package com.wendy.paygateway.refund.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Refund request, supporting both full and partial refunds. */
@Data
@Schema(description = "Refund request")
public class RefundRequest {

    @Schema(description = "Gateway pay order number", example = "P2026072812000001000001")
    @NotBlank(message = "must not be blank")
    private String payNo;

    @Schema(description = "Upstream refund request number; the gateway keys idempotency on it", example = "MALLREF20260728001")
    @NotBlank(message = "must not be blank")
    @Size(max = 64)
    private String bizRefundNo;

    @Schema(description = "Refund amount in yuan; less than the paid amount makes it a partial refund", example = "10.00")
    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.01", message = "must be at least 0.01")
    private BigDecimal amount;

    @Schema(description = "Refund reason", example = "user cancelled the order")
    @Size(max = 255)
    private String reason;
}
