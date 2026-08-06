package com.wendy.paygateway.channel.adapter;

import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelPayResponse;
import com.wendy.paygateway.channel.dto.ChannelQueryResponse;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.mock.MockChannelServer;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.util.RsaUtils;
import com.wendy.paygateway.common.util.SignUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Alipay channel adapter: RSA2 signing plus form-encoded webhook verification. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayChannelAdapter implements PayChannelAdapter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MockChannelServer mockChannelServer;

    @Override
    public ChannelType channelType() {
        return ChannelType.ALIPAY;
    }

    @Override
    public ChannelPayResponse pay(ChannelPayRequest request, PayChannelConfig config) {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", config.getAppId());
        params.put("method", "alipay.trade.page.pay");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now().format(TIME_FMT));
        params.put("version", "1.0");
        params.put("notify_url", request.getNotifyUrl());
        params.put("out_trade_no", request.getPayNo());
        params.put("total_amount", request.getAmount().toPlainString());
        params.put("subject", request.getSubject());
        params.put("timeout_express", "30m");
        // Sign with the merchant private key; Alipay verifies with the merchant public key
        String sign = RsaUtils.sign(params, config.getPrivateKey());

        String channelTradeNo = mockChannelServer.acceptOrder(config, request);
        String payUrl = config.getGatewayUrl()
                + "?out_trade_no=" + request.getPayNo()
                + "&trade_no=" + channelTradeNo
                + "&total_amount=" + request.getAmount().toPlainString()
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);

        return ChannelPayResponse.builder()
                .accepted(true)
                .channelTradeNo(channelTradeNo)
                .payUrl(payUrl)
                .syncPaid(false)
                .build();
    }

    @Override
    public ChannelRefundResponse refund(ChannelRefundRequest request, PayChannelConfig config) {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", config.getAppId());
        params.put("method", "alipay.trade.refund");
        params.put("out_trade_no", request.getPayNo());
        params.put("trade_no", request.getChannelTradeNo());
        params.put("out_request_no", request.getRefundNo());
        params.put("refund_amount", request.getRefundAmount().toPlainString());
        params.put("refund_reason", request.getReason());
        params.put("sign", RsaUtils.sign(params, config.getPrivateKey()));

        String channelRefundNo = mockChannelServer.acceptRefund(config, request);
        return ChannelRefundResponse.builder()
                .accepted(true)
                .channelRefundNo(channelRefundNo)
                .syncRefunded(false)
                .build();
    }

    @Override
    public ChannelQueryResponse query(String payNo, PayChannelConfig config) {
        MockChannelServer.MockTrade trade = mockChannelServer.queryTrade(payNo);
        if (trade == null) {
            return ChannelQueryResponse.builder().success(true).tradeStatus("NOT_EXIST").build();
        }
        return ChannelQueryResponse.builder()
                .success(true)
                .tradeStatus(trade.getStatus())
                .channelTradeNo(trade.getChannelTradeNo())
                .amount(trade.getAmount())
                .build();
    }

    @Override
    public boolean verifyWebhook(Map<String, String> params, PayChannelConfig config) {
        String sign = params.get(SignUtils.SIGN_FIELD);
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        boolean ok = RsaUtils.verify(params, sign, config.getPublicKey());
        if (!ok) {
            log.warn("[Alipay webhook] RSA2 verification failed out_trade_no={}", params.get("out_trade_no"));
        }
        return ok;
    }

    @Override
    public String downloadBill(LocalDate billDate, PayChannelConfig config) {
        // Real implementation: alipay.data.dataservice.bill.downloadurl.query -> download zip -> parse CSV
        return mockChannelServer.generateBillContent(ChannelType.ALIPAY, billDate);
    }
}
