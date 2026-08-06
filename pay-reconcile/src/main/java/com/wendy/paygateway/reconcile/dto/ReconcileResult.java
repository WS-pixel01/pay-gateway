package com.wendy.paygateway.reconcile.dto;

import com.wendy.paygateway.common.enums.ChannelType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary of one day's reconciliation for one channel. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reconciliation result")
public class ReconcileResult {

    private String billDate;
    private ChannelType channelType;
    @Schema(description = "Number of locally successful trades")
    private int localCount;
    @Schema(description = "Number of rows in the channel bill")
    private int channelCount;
    @Schema(description = "Total amount of locally successful trades")
    private BigDecimal localAmount;
    @Schema(description = "Total amount in the channel bill")
    private BigDecimal channelAmount;
    @Schema(description = "Number of discrepancy records")
    private int diffCount;
    @Schema(description = "Breakdown of discrepancies by type")
    private String diffSummary;
    private long costMs;
}
