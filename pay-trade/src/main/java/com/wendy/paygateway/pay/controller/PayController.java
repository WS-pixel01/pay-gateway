package com.wendy.paygateway.pay.controller;

import com.wendy.paygateway.common.api.R;
import com.wendy.paygateway.pay.dto.CreatePayRequest;
import com.wendy.paygateway.pay.dto.CreatePayResponse;
import com.wendy.paygateway.pay.dto.PayOrderVO;
import com.wendy.paygateway.pay.service.PayOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Checkout endpoints, called by upstream systems such as the mall or membership service. */
@Tag(name = "01-Checkout", description = "Create pay orders, query, close and re-sync from the channel")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayController {

    private final PayOrderService payOrderService;

    @Operation(summary = "Create a pay order",
            description = "Repeating the same bizSystem + bizOrderNo replays the first result (idempotent)")
    @PostMapping("/create")
    public R<CreatePayResponse> create(@Valid @RequestBody CreatePayRequest request) {
        return R.ok(payOrderService.createPay(request));
    }

    @Operation(summary = "Query by pay order number")
    @GetMapping("/{payNo}")
    public R<PayOrderVO> getByPayNo(@PathVariable String payNo) {
        return R.ok(payOrderService.queryByPayNo(payNo));
    }

    @Operation(summary = "Query by business order number")
    @GetMapping("/biz")
    public R<PayOrderVO> getByBizOrderNo(@RequestParam String bizSystem, @RequestParam String bizOrderNo) {
        return R.ok(payOrderService.queryByBizOrderNo(bizSystem, bizOrderNo));
    }

    @Operation(summary = "List recent pay orders")
    @GetMapping("/list")
    public R<List<PayOrderVO>> list(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(payOrderService.listRecent(limit));
    }

    @Operation(summary = "Close a pay order",
            description = "Called when upstream cancels the order; the gateway publishes an MQ event to release stock")
    @PostMapping("/{payNo}/close")
    public R<Boolean> close(@PathVariable String payNo,
                            @RequestParam(defaultValue = "closed by upstream") String reason) {
        return R.ok(payOrderService.close(payNo, reason));
    }

    @Operation(summary = "Compensating channel query",
            description = "Corrects the local pay order from the channel's status when a webhook was lost")
    @PostMapping("/{payNo}/sync")
    public R<PayOrderVO> sync(@PathVariable String payNo) {
        return R.ok(payOrderService.syncFromChannel(payNo));
    }
}
