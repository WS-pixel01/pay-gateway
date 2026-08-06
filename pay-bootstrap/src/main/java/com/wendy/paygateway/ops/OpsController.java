package com.wendy.paygateway.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.account.service.AccountService;
import com.wendy.paygateway.webhook.entity.WebhookLog;
import com.wendy.paygateway.webhook.mapper.WebhookLogMapper;
import com.wendy.paygateway.channel.dto.ChannelVO;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.mock.MockChannelServer;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.api.R;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.message.consumer.MockBusinessSystemClient;
import com.wendy.paygateway.message.dto.PayResultMessage;
import com.wendy.paygateway.message.entity.LocalMessage;
import com.wendy.paygateway.message.service.LocalMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ops and demo endpoints: inspect channels, balances, webhook logs and message delivery, and
 * re-push a webhook by hand.
 */
@Tag(name = "05-Ops and demo",
        description = "Channel management, balance lookup, webhook logs and message tracing")
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final PayChannelConfigService payChannelConfigService;
    private final AccountService accountService;
    private final WebhookLogMapper webhookLogMapper;
    private final LocalMessageService localMessageService;
    private final MockBusinessSystemClient businessSystemClient;
    private final MockChannelServer mockChannelServer;

    @Operation(summary = "List channels (secrets masked)")
    @GetMapping("/channels")
    public R<List<ChannelVO>> channels() {
        return R.ok(payChannelConfigService.listAll().stream().map(ChannelVO::from).toList());
    }

    @Operation(summary = "Enable or disable a channel",
            description = "A disabled channel drops out of routing, which demonstrates channel failover")
    @PostMapping("/channels/{channelType}/switch")
    public R<Void> switchChannel(@PathVariable ChannelType channelType, @RequestParam boolean enabled) {
        payChannelConfigService.switchChannel(channelType, enabled);
        return R.ok();
    }

    @Operation(summary = "Look up an account balance")
    @GetMapping("/account/{userId}/balance")
    public R<BigDecimal> balance(@PathVariable String userId) {
        return R.ok(accountService.getBalance(userId));
    }

    @Operation(summary = "Webhook logs",
            description = "Shows duplicate webhooks that were classified as DUPLICATE")
    @GetMapping("/webhooks")
    public R<List<WebhookLog>> webhooks(@RequestParam(required = false) String outTradeNo,
                                          @RequestParam(defaultValue = "50") int limit) {
        return R.ok(webhookLogMapper.selectList(Wrappers.<WebhookLog>lambdaQuery()
                .eq(outTradeNo != null, WebhookLog::getOutTradeNo, outTradeNo)
                .orderByDesc(WebhookLog::getId)
                .last("limit " + limit)));
    }

    @Operation(summary = "Outbox rows",
            description = "Follow a payment result message through NEW -> SENT -> CONSUMED")
    @GetMapping("/messages")
    public R<List<LocalMessage>> messages(@RequestParam String bizKey) {
        return R.ok(localMessageService.listByBizKey(bizKey));
    }

    @Operation(summary = "Notifications the upstream system received",
            description = "Proves payment results are always delivered in the end")
    @GetMapping("/notifications")
    public R<List<PayResultMessage>> notifications() {
        return R.ok(businessSystemClient.receivedNotifications());
    }

    @Operation(summary = "Re-push a channel webhook by hand",
            description = "Simulates a channel re-push, used to demonstrate gateway idempotency")
    @PostMapping("/mock/resend-webhook")
    public R<Void> resendWebhook(@RequestParam String payNo, @RequestParam ChannelType channelType) {
        PayChannelConfig config = payChannelConfigService.getEnabled(channelType);
        mockChannelServer.resendPayWebhook(config, payNo);
        return R.ok();
    }
}
