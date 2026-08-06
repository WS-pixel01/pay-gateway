package com.wendy.paygateway.reconcile.controller;

import com.wendy.paygateway.common.api.R;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.reconcile.dto.ReconcileResult;
import com.wendy.paygateway.reconcile.entity.ReconcileDiff;
import com.wendy.paygateway.reconcile.service.ReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Reconciliation endpoints for the operations team. */
@Tag(name = "04-Reconciliation", description = "Trigger reconciliation manually and query discrepancies")
@RestController
@RequestMapping("/api/ops/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    @Operation(summary = "Trigger reconciliation manually",
            description = "Omitting the date reconciles today; omitting the channel reconciles both Alipay and WeChat")
    @PostMapping("/run")
    public R<List<ReconcileResult>> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam(required = false) ChannelType channelType) {
        LocalDate date = billDate == null ? LocalDate.now() : billDate;
        List<ChannelType> channels = channelType == null
                ? List.of(ChannelType.ALIPAY, ChannelType.WECHAT) : List.of(channelType);
        return R.ok(reconcileService.reconcileAll(date, channels));
    }

    @Operation(summary = "Query discrepancy records")
    @GetMapping("/diffs")
    public R<List<ReconcileDiff>> diffs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam(required = false) ChannelType channelType) {
        return R.ok(reconcileService.listDiffs(billDate == null ? LocalDate.now() : billDate, channelType));
    }

    @Operation(summary = "Mark a discrepancy as handled")
    @PostMapping("/diffs/{id}/handle")
    public R<Boolean> handle(@PathVariable Long id,
                             @RequestParam(defaultValue = "verified and corrected manually") String remark) {
        return R.ok(reconcileService.markHandled(id, remark));
    }
}
