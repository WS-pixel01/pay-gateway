package com.wendy.paygateway.refund.controller;

import com.wendy.paygateway.common.api.R;
import com.wendy.paygateway.refund.dto.RefundOrderVO;
import com.wendy.paygateway.refund.dto.RefundRequest;
import com.wendy.paygateway.refund.service.RefundOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Refund endpoints. */
@Tag(name = "02-Refund", description = "Request and query full or partial refunds")
@RestController
@RequestMapping("/api/refund")
@RequiredArgsConstructor
public class RefundController {

    private final RefundOrderService refundOrderService;

    @Operation(summary = "Request a refund",
            description = "Repeating the same bizRefundNo is idempotent; concurrent refunds are serialised by a distributed lock")
    @PostMapping("/apply")
    public R<RefundOrderVO> apply(@Valid @RequestBody RefundRequest request) {
        return R.ok(refundOrderService.applyRefund(request));
    }

    @Operation(summary = "Query a refund order")
    @GetMapping("/{refundNo}")
    public R<RefundOrderVO> get(@PathVariable String refundNo) {
        return R.ok(refundOrderService.queryByRefundNo(refundNo));
    }

    @Operation(summary = "List every refund order for a pay order")
    @GetMapping("/pay/{payNo}")
    public R<List<RefundOrderVO>> listByPayNo(@PathVariable String payNo) {
        return R.ok(refundOrderService.listByPayNo(payNo));
    }
}
