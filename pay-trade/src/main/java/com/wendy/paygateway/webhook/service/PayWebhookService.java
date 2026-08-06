package com.wendy.paygateway.webhook.service;

import com.wendy.paygateway.webhook.dto.ParsedWebhook;
import com.wendy.paygateway.webhook.entity.WebhookLog;
import com.wendy.paygateway.webhook.mapper.WebhookLogMapper;
import com.wendy.paygateway.channel.adapter.ChannelAdapterFactory;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.enums.WebhookResult;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.infra.DistributedLock;
import com.wendy.paygateway.common.infra.IdempotentStore;
import com.wendy.paygateway.common.util.AesUtils;
import com.wendy.paygateway.common.util.JsonUtils;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.pay.service.PayOrderStatusService;
import com.wendy.paygateway.refund.service.RefundOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Unified handling of third-party async payment webhooks — the hardest and most important module
 * in this project.
 *
 * <p>Every webhook has to clear five gates:
 * <ol>
 *   <li><b>Signature</b>: RSA2 for Alipay, MD5 for WeChat. A failed check is rejected outright, so a
 *       forged webhook cannot fake a successful payment.</li>
 *   <li><b>Parsing</b>: WeChat v3 hides the business fields inside an AES-GCM encrypted
 *       {@code resource}, which must be decrypted first.</li>
 *   <li><b>Deduplication</b>: channels re-push webhooks (the simulator here does it deliberately),
 *       so "channel + pay order number + channel trade number" claims a slot in the idempotency store.</li>
 *   <li><b>Serialisation</b>: a distributed lock on the pay order number stops concurrent webhooks
 *       from corrupting amounts.</li>
 *   <li><b>State machine + amount check</b>: only a WAIT_PAY order with a matching amount may be
 *       marked successful.</li>
 * </ol>
 *
 * <p>Success or failure, a webhook_log row is always written — it is what production money
 * investigations rely on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayWebhookService {

    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String LOCK_PREFIX = "pay:lock:webhook:";

    private final PayChannelConfigService payChannelConfigService;
    private final ChannelAdapterFactory channelAdapterFactory;
    private final PayOrderStatusService payOrderStatusService;
    private final RefundOrderService refundOrderService;
    private final IdempotentStore idempotentStore;
    private final DistributedLock distributedLock;
    private final WebhookLogMapper webhookLogMapper;

    /**
     * Handle a payment result webhook.
     *
     * @return true when the gateway accepted it (safe to answer the channel with success), false when
     *         the channel should re-push
     */
    public boolean handlePayWebhook(ChannelType channelType, Map<String, String> params) {
        long start = System.currentTimeMillis();
        String rawBody = JsonUtils.toJson(params);
        PayChannelConfig config = payChannelConfigService.get(channelType);
        if (config == null) {
            saveLog(channelType, "PAY", null, null, rawBody, false,
                    WebhookResult.BIZ_FAIL, "channel not configured", start);
            return false;
        }

        // 1. verify the signature
        if (!channelAdapterFactory.get(channelType).verifyWebhook(params, config)) {
            saveLog(channelType, "PAY", params.get("out_trade_no"), null, rawBody, false,
                    WebhookResult.VERIFY_FAIL, "signature verification failed", start);
            return false;
        }

        // 2. parse the payload
        ParsedWebhook parsed;
        try {
            parsed = parsePayWebhook(channelType, params, config);
        } catch (Exception e) {
            saveLog(channelType, "PAY", params.get("out_trade_no"), null, rawBody, true,
                    WebhookResult.BIZ_FAIL, "payload parsing failed: " + e.getMessage(), start);
            return false;
        }

        // 3. guard against duplicate webhooks
        String dedupKey = "pay:wh:pay:" + channelType + ":" + parsed.getOutTradeNo()
                + ":" + parsed.getChannelTradeNo();
        if (!idempotentStore.setIfAbsent(dedupKey, "1", DEDUP_TTL)) {
            log.info("[Webhook] duplicate webhook, ignored idempotently channel={} payNo={}",
                    channelType, parsed.getOutTradeNo());
            saveLog(channelType, "PAY", parsed.getOutTradeNo(), parsed.getChannelTradeNo(), rawBody, true,
                    WebhookResult.DUPLICATE, "duplicate webhook", start);
            return true;
        }

        // 4. serialise under the lock, then 5. drive the state machine
        try {
            distributedLock.runWithLock(LOCK_PREFIX + parsed.getOutTradeNo(), () -> {
                if (parsed.isSuccess()) {
                    payOrderStatusService.markPaySuccess(
                            parsed.getOutTradeNo(), parsed.getChannelTradeNo(), parsed.getAmount());
                } else {
                    payOrderStatusService.markPayFail(parsed.getOutTradeNo(),
                            "channel webhook reported failure: " + parsed.getRawStatus());
                }
            });
        } catch (Exception e) {
            // Release the dedupe key on failure so the channel's re-push can be processed again
            idempotentStore.delete(dedupKey);
            log.error("[Webhook] business processing failed payNo={}", parsed.getOutTradeNo(), e);
            saveLog(channelType, "PAY", parsed.getOutTradeNo(), parsed.getChannelTradeNo(), rawBody, true,
                    WebhookResult.BIZ_FAIL, e.getMessage(), start);
            return false;
        }

        saveLog(channelType, "PAY", parsed.getOutTradeNo(), parsed.getChannelTradeNo(), rawBody, true,
                WebhookResult.PROCESSED, null, start);
        return true;
    }

    /** Handle a refund result webhook. */
    public boolean handleRefundWebhook(ChannelType channelType, Map<String, String> params) {
        long start = System.currentTimeMillis();
        String rawBody = JsonUtils.toJson(params);
        PayChannelConfig config = payChannelConfigService.get(channelType);
        if (config == null) {
            saveLog(channelType, "REFUND", null, null, rawBody, false,
                    WebhookResult.BIZ_FAIL, "channel not configured", start);
            return false;
        }
        if (!channelAdapterFactory.get(channelType).verifyWebhook(params, config)) {
            saveLog(channelType, "REFUND", params.get("out_refund_no"), null, rawBody, false,
                    WebhookResult.VERIFY_FAIL, "signature verification failed", start);
            return false;
        }

        String refundNo = params.get("out_refund_no");
        String channelRefundNo = params.get("refund_id");
        boolean success = "SUCCESS".equalsIgnoreCase(params.get("refund_status"));

        String dedupKey = "pay:wh:refund:" + channelType + ":" + refundNo + ":" + channelRefundNo;
        if (!idempotentStore.setIfAbsent(dedupKey, "1", DEDUP_TTL)) {
            saveLog(channelType, "REFUND", refundNo, channelRefundNo, rawBody, true,
                    WebhookResult.DUPLICATE, "duplicate webhook", start);
            return true;
        }

        try {
            if (success) {
                refundOrderService.handleRefundSuccess(refundNo, channelRefundNo);
            } else {
                refundOrderService.handleRefundFail(refundNo, params.get("refund_status"));
            }
        } catch (Exception e) {
            idempotentStore.delete(dedupKey);
            log.error("[Refund webhook] business processing failed refundNo={}", refundNo, e);
            saveLog(channelType, "REFUND", refundNo, channelRefundNo, rawBody, true,
                    WebhookResult.BIZ_FAIL, e.getMessage(), start);
            return false;
        }

        saveLog(channelType, "REFUND", refundNo, channelRefundNo, rawBody, true,
                WebhookResult.PROCESSED, null, start);
        return true;
    }

    /** All per-channel payload differences are absorbed here. */
    private ParsedWebhook parsePayWebhook(ChannelType channelType, Map<String, String> params,
                                            PayChannelConfig config) {
        if (channelType == ChannelType.WECHAT) {
            // WeChat v3: the business fields live inside the AES-GCM encrypted "resource" field
            String plain = AesUtils.decrypt(params.get("resource"), config.getAesKey());
            Map<String, Object> resource = JsonUtils.toMap(plain);
            long totalFee = Long.parseLong(String.valueOf(resource.get("total_fee")));
            return ParsedWebhook.builder()
                    .outTradeNo(String.valueOf(resource.get("out_trade_no")))
                    .channelTradeNo(String.valueOf(resource.get("transaction_id")))
                    .amount(MoneyUtil.fromCent(totalFee))
                    .success("SUCCESS".equals(resource.get("result_code")))
                    .rawStatus(String.valueOf(resource.get("result_code")))
                    .build();
        }
        // Alipay: form parameters, amounts denominated in yuan
        String tradeStatus = params.get("trade_status");
        return ParsedWebhook.builder()
                .outTradeNo(params.get("out_trade_no"))
                .channelTradeNo(params.get("trade_no"))
                .amount(new BigDecimal(params.get("total_amount")))
                .success("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus))
                .rawStatus(tradeStatus)
                .build();
    }

    private void saveLog(ChannelType channelType, String webhookType, String outTradeNo,
                         String channelTradeNo, String rawBody, boolean signVerified,
                         WebhookResult result, String remark, long start) {
        try {
            WebhookLog webhookLog = new WebhookLog();
            webhookLog.setChannelType(channelType);
            webhookLog.setWebhookType(webhookType);
            webhookLog.setOutTradeNo(outTradeNo);
            webhookLog.setChannelTradeNo(channelTradeNo);
            webhookLog.setRawBody(rawBody != null && rawBody.length() > 3900
                    ? rawBody.substring(0, 3900) : rawBody);
            webhookLog.setSignVerified(signVerified ? 1 : 0);
            webhookLog.setResult(result);
            webhookLog.setRemark(remark != null && remark.length() > 500 ? remark.substring(0, 500) : remark);
            webhookLog.setCostMs(System.currentTimeMillis() - start);
            webhookLogMapper.insert(webhookLog);
        } catch (Exception e) {
            log.error("[Webhook] failed to write the webhook log", e);
        }
    }
}
