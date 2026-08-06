package com.wendy.paygateway.webhook.controller;

import com.wendy.paygateway.webhook.service.PayWebhookService;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Entry points for third-party channel async webhooks.
 *
 * <p>Note that these endpoints do not use JWT authentication — a third-party channel has no token
 * of ours — so their security rests entirely on channel signature verification. The response body
 * must also match each channel's agreed format, otherwise the channel treats the notification as
 * failed and keeps re-pushing.
 */
@Slf4j
@Tag(name = "03-Channel webhooks",
        description = "Alipay form webhook / WeChat JSON webhook / refund webhook")
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class PayWebhookController {

    private final PayWebhookService payWebhookService;

    /** Alipay: application/x-www-form-urlencoded, and success must be answered with literal "success". */
    @Operation(summary = "Alipay payment result webhook")
    @PostMapping("/alipay")
    public String alipayWebhook(@RequestParam Map<String, String> params) {
        log.info("[Webhook] received Alipay webhook out_trade_no={}", params.get("out_trade_no"));
        return payWebhookService.handlePayWebhook(ChannelType.ALIPAY, params) ? "success" : "failure";
    }

    /** WeChat: application/json, and success is answered with {"code":"SUCCESS"}. */
    @Operation(summary = "WeChat payment result webhook")
    @PostMapping("/wechat")
    public Map<String, String> wechatWebhook(@RequestBody Map<String, String> params) {
        log.info("[Webhook] received WeChat webhook nonce_str={}", params.get("nonce_str"));
        boolean ok = payWebhookService.handlePayWebhook(ChannelType.WECHAT, params);
        Map<String, String> result = new HashMap<>();
        result.put("code", ok ? "SUCCESS" : "FAIL");
        result.put("message", ok ? "OK" : "processing failed, please re-push");
        return result;
    }

    /**
     * Refund webhook. Both channels share this entry point; the adapter tells their signature
     * schemes apart.
     */
    @Operation(summary = "Refund result webhook")
    @PostMapping("/{channel}/refund")
    public Map<String, String> refundWebhook(@PathVariable String channel,
                                              @RequestBody Map<String, String> params) {
        ChannelType channelType = ChannelType.of(channel);
        if (channelType == null) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_FOUND, channel);
        }
        log.info("[Webhook] received {} refund webhook out_refund_no={}",
                channelType.getDesc(), params.get("out_refund_no"));
        boolean ok = payWebhookService.handleRefundWebhook(channelType, params);
        Map<String, String> result = new HashMap<>();
        result.put("code", ok ? "SUCCESS" : "FAIL");
        return result;
    }
}
