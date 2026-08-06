package com.wendy.paygateway.channel.mock;

import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.util.AesUtils;
import com.wendy.paygateway.common.util.IdGenerator;
import com.wendy.paygateway.common.util.JsonUtils;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.common.util.RsaUtils;
import com.wendy.paygateway.common.util.SignUtils;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Third-party payment channel simulator, standing in for the Alipay/WeChat sandboxes locally.
 *
 * <p>It does the three things a real channel does:
 * <ol>
 *   <li>accept an order and return a channel trade number plus a checkout URL;</li>
 *   <li>after a delay, <b>deliver a webhook</b> with the payment result, signed exactly the
 *       way each channel signs (Alipay: RSA2 over a form; WeChat: MD5 plus an AES-GCM encrypted
 *       {@code resource});</li>
 *   <li>optionally <b>push the same webhook twice</b>, so the gateway's idempotency can be proven.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockChannelServer {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter COMPACT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter COMPACT_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Bill file header; the columns map one-to-one onto {@code ChannelBill} fields. */
    public static final String BILL_HEADER = "out_trade_no,channel_trade_no,amount,fee,trade_status,trade_time";

    private final PayProperties payProperties;
    private final RestTemplate restTemplate;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "mock-channel-" + UUID.randomUUID().toString().substring(0, 4));
        t.setDaemon(true);
        return t;
    });

    /** The channel-side ledger: payNo -&gt; trade. The bill file is generated from it. */
    private final Map<String, MockTrade> trades = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** Accept an order, return the channel trade number and schedule the async webhook. */
    public String acceptOrder(PayChannelConfig config, ChannelPayRequest request) {
        String channelTradeNo = IdGenerator.channelTradeNo(config.getChannelType().name());
        MockTrade trade = new MockTrade();
        trade.setPayNo(request.getPayNo());
        trade.setChannelTradeNo(channelTradeNo);
        trade.setAmount(MoneyUtil.scale(request.getAmount()));
        trade.setChannelType(config.getChannelType());
        trade.setStatus("WAIT_PAY");
        trade.setCreateTime(LocalDateTime.now());
        trades.put(request.getPayNo(), trade);

        int delay = Math.max(1, payProperties.getMock().getWebhookDelaySeconds());
        scheduler.schedule(() -> firePayWebhook(config, trade), delay, TimeUnit.SECONDS);
        log.info("[Mock channel] {} accepted order payNo={} channelTradeNo={}, will deliver the webhook in {}s",
                config.getChannelType(), request.getPayNo(), channelTradeNo, delay);
        return channelTradeNo;
    }

    /** Accept a refund, return the channel refund number and schedule the refund webhook. */
    public String acceptRefund(PayChannelConfig config, ChannelRefundRequest request) {
        String channelRefundNo = IdGenerator.channelTradeNo(config.getChannelType().name() + "R");
        MockTrade trade = trades.get(request.getPayNo());
        if (trade != null) {
            trade.setRefundedAmount(MoneyUtil.add(trade.getRefundedAmount(), request.getRefundAmount()));
        }
        int delay = Math.max(1, payProperties.getMock().getWebhookDelaySeconds());
        scheduler.schedule(() -> fireRefundWebhook(config, request, channelRefundNo), delay, TimeUnit.SECONDS);
        log.info("[Mock channel] {} accepted refund refundNo={} channelRefundNo={}",
                config.getChannelType(), request.getRefundNo(), channelRefundNo);
        return channelRefundNo;
    }

    /** Channel-side order lookup, used by the gateway's compensating query. */
    public MockTrade queryTrade(String payNo) {
        return trades.get(payNo);
    }

    /** All trades known to the channel. */
    public Map<String, MockTrade> allTrades() {
        return trades;
    }

    /**
     * Generate the channel's bill file for a given day (CSV).
     *
     * <p>Discrepancy injection deliberately lives on the <b>channel side</b> rather than in the
     * reconciliation module: in the real world discrepancies originate from the channel missing an
     * entry, disagreeing on an amount, or our webhook being lost. Reconciliation's only job is to
     * compare faithfully — it should not know how the discrepancies were manufactured.
     *
     * <p>Three kinds are injected: drop the last entry, shave one cent off the first entry, and
     * invent an entry we have no record of.
     */
    public String generateBillContent(ChannelType channelType, LocalDate billDate) {
        List<String> lines = new ArrayList<>();
        lines.add(BILL_HEADER);

        List<MockTrade> paid = trades.values().stream()
                .filter(t -> t.getChannelType() == channelType)
                .filter(t -> "SUCCESS".equals(t.getStatus()))
                .filter(t -> t.getPayTime() != null && t.getPayTime().toLocalDate().equals(billDate))
                .sorted(Comparator.comparing(MockTrade::getPayNo))
                .toList();

        boolean inject = payProperties.getReconcile().isInjectAnomaly();
        for (int i = 0; i < paid.size(); i++) {
            MockTrade trade = paid.get(i);
            if (inject && paid.size() > 1 && i == paid.size() - 1) {
                log.warn("[Mock channel] injected discrepancy: entry missing from bill payNo={}", trade.getPayNo());
                continue;
            }
            BigDecimal amount = trade.getAmount();
            if (inject && i == 0) {
                amount = MoneyUtil.subtract(amount, new BigDecimal("0.01"));
                log.warn("[Mock channel] injected discrepancy: amount mismatch payNo={} channelAmount={}",
                        trade.getPayNo(), amount);
            }
            lines.add(billLine(trade.getPayNo(), trade.getChannelTradeNo(), amount, "SUCCESS", trade.getPayTime()));
        }

        if (inject && !paid.isEmpty()) {
            String ghostNo = "GHOST" + billDate.format(COMPACT_DATE_FMT) + "0001";
            lines.add(billLine(ghostNo, "CH" + ghostNo, new BigDecimal("66.66"), "SUCCESS", LocalDateTime.now()));
            log.warn("[Mock channel] injected discrepancy: extra entry in bill {}", ghostNo);
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String billLine(String outTradeNo, String channelTradeNo, BigDecimal amount,
                            String status, LocalDateTime time) {
        BigDecimal fee = MoneyUtil.multiply(amount, new BigDecimal("0.006"));
        return String.join(",", outTradeNo, channelTradeNo, amount.toPlainString(),
                fee.toPlainString(), status, (time == null ? LocalDateTime.now() : time).format(TIME_FMT));
    }

    /** Fire a webhook by hand, to demo "the channel re-pushes after a lost webhook". */
    public void resendPayWebhook(PayChannelConfig config, String payNo) {
        MockTrade trade = trades.get(payNo);
        if (trade == null) {
            throw new IllegalArgumentException("No such trade on the channel side: " + payNo);
        }
        firePayWebhook(config, trade);
    }

    // ------------------------------------------------------------------ webhook dispatch

    private void firePayWebhook(PayChannelConfig config, MockTrade trade) {
        boolean success = ThreadLocalRandom.current().nextInt(100) >= payProperties.getMock().getFailRate();
        trade.setStatus(success ? "SUCCESS" : "FAIL");
        trade.setPayTime(LocalDateTime.now());
        doSendPayWebhook(config, trade);
        // Push the same webhook a second time on purpose, to exercise gateway idempotency
        if (success && payProperties.getMock().isDuplicateWebhook()) {
            scheduler.schedule(() -> {
                log.info("[Mock channel] re-pushing webhook payNo={} (idempotency check)", trade.getPayNo());
                doSendPayWebhook(config, trade);
            }, 1, TimeUnit.SECONDS);
        }
    }

    private void doSendPayWebhook(PayChannelConfig config, MockTrade trade) {
        try {
            String url = payProperties.getMock().getSelfBaseUrl() + config.getNotifyUrl();
            if (config.getChannelType() == ChannelType.ALIPAY) {
                postForm(url, buildAlipayNotify(config, trade));
            } else {
                postJson(url, buildWechatNotify(config, trade));
            }
        } catch (Exception e) {
            log.error("[Mock channel] failed to push webhook payNo={}: {}", trade.getPayNo(), e.getMessage());
        }
    }

    /** Alipay style: form parameters signed with RSA2. */
    private Map<String, String> buildAlipayNotify(PayChannelConfig config, MockTrade trade) {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", config.getAppId());
        params.put("notify_id", UUID.randomUUID().toString().replace("-", ""));
        params.put("notify_type", "trade_status_sync");
        params.put("out_trade_no", trade.getPayNo());
        params.put("trade_no", trade.getChannelTradeNo());
        params.put("trade_status", "SUCCESS".equals(trade.getStatus()) ? "TRADE_SUCCESS" : "TRADE_CLOSED");
        params.put("total_amount", trade.getAmount().toPlainString());
        params.put("gmt_payment", LocalDateTime.now().format(TIME_FMT));
        params.put("sign_type", "RSA2");
        params.put("sign", RsaUtils.sign(params, config.getPrivateKey()));
        return params;
    }

    /**
     * WeChat v3 style: MD5 signature on the outer envelope, business fields inside an AES-GCM
     * encrypted {@code resource}.
     */
    private Map<String, String> buildWechatNotify(PayChannelConfig config, MockTrade trade) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("out_trade_no", trade.getPayNo());
        resource.put("transaction_id", trade.getChannelTradeNo());
        resource.put("result_code", "SUCCESS".equals(trade.getStatus()) ? "SUCCESS" : "FAIL");
        resource.put("total_fee", MoneyUtil.toCent(trade.getAmount()));
        resource.put("time_end", LocalDateTime.now().format(COMPACT_FMT));

        Map<String, String> params = new HashMap<>();
        params.put("appid", config.getAppId());
        params.put("mch_id", config.getMerchantId());
        params.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        params.put("event_type", "TRANSACTION.SUCCESS");
        params.put("resource", AesUtils.encrypt(JsonUtils.toJson(resource), config.getAesKey()));
        params.put("sign", SignUtils.md5Sign(params, config.getApiKey()));
        return params;
    }

    private void fireRefundWebhook(PayChannelConfig config, ChannelRefundRequest request, String channelRefundNo) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("out_refund_no", request.getRefundNo());
            params.put("out_trade_no", request.getPayNo());
            params.put("refund_id", channelRefundNo);
            params.put("refund_status", "SUCCESS");
            params.put("refund_fee", request.getRefundAmount().toPlainString());
            params.put("success_time", LocalDateTime.now().format(TIME_FMT));
            if ("RSA2".equalsIgnoreCase(config.getSignType())) {
                params.put("sign_type", "RSA2");
                params.put("sign", RsaUtils.sign(params, config.getPrivateKey()));
            } else {
                params.put("sign", SignUtils.md5Sign(params, config.getApiKey()));
            }
            String url = payProperties.getMock().getSelfBaseUrl()
                    + "/api/webhooks/" + config.getChannelType().name().toLowerCase() + "/refund";
            postJson(url, params);
        } catch (Exception e) {
            log.error("[Mock channel] failed to push refund webhook refundNo={}: {}",
                    request.getRefundNo(), e.getMessage());
        }
    }

    private void postForm(String url, Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
    }

    private void postJson(String url, Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(url, new HttpEntity<>(JsonUtils.toJson(params), headers), String.class);
    }

    /** A single trade as the channel sees it. */
    @Data
    public static class MockTrade {
        private String payNo;
        private String channelTradeNo;
        private ChannelType channelType;
        private BigDecimal amount;
        private BigDecimal refundedAmount = MoneyUtil.ZERO;
        private String status;
        private LocalDateTime createTime;
        private LocalDateTime payTime;
    }
}
