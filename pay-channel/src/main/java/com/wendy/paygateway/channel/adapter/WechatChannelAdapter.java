package com.wendy.paygateway.channel.adapter;

import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelPayResponse;
import com.wendy.paygateway.channel.dto.ChannelQueryResponse;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.mock.MockChannelServer;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.common.util.SignUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** WeChat Pay channel adapter: MD5 signing plus AES-GCM decryption of the webhook payload. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatChannelAdapter implements PayChannelAdapter {

    private final MockChannelServer mockChannelServer;

    @Override
    public ChannelType channelType() {
        return ChannelType.WECHAT;
    }

    @Override
    public ChannelPayResponse pay(ChannelPayRequest request, PayChannelConfig config) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", config.getAppId());
        params.put("mch_id", config.getMerchantId());
        params.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        params.put("body", request.getSubject());
        params.put("out_trade_no", request.getPayNo());
        // WeChat amounts are in cents; always convert via MoneyUtil rather than hand-writing * 100
        params.put("total_fee", String.valueOf(MoneyUtil.toCent(request.getAmount())));
        params.put("spbill_create_ip", request.getClientIp());
        params.put("notify_url", request.getNotifyUrl());
        params.put("trade_type", "NATIVE");
        params.put("sign", SignUtils.md5Sign(params, config.getApiKey()));

        String channelTradeNo = mockChannelServer.acceptOrder(config, request);
        // NATIVE payments return a QR code URL
        String codeUrl = "weixin://wxpay/bizpayurl?pr=" + channelTradeNo;

        return ChannelPayResponse.builder()
                .accepted(true)
                .channelTradeNo(channelTradeNo)
                .payUrl(codeUrl)
                .syncPaid(false)
                .build();
    }

    @Override
    public ChannelRefundResponse refund(ChannelRefundRequest request, PayChannelConfig config) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", config.getAppId());
        params.put("mch_id", config.getMerchantId());
        params.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        params.put("out_trade_no", request.getPayNo());
        params.put("transaction_id", request.getChannelTradeNo());
        params.put("out_refund_no", request.getRefundNo());
        params.put("total_fee", String.valueOf(MoneyUtil.toCent(request.getTotalAmount())));
        params.put("refund_fee", String.valueOf(MoneyUtil.toCent(request.getRefundAmount())));
        params.put("sign", SignUtils.md5Sign(params, config.getApiKey()));

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
        boolean ok = SignUtils.verifyMd5(params, config.getApiKey());
        if (!ok) {
            log.warn("[WeChat webhook] MD5 verification failed nonce_str={}", params.get("nonce_str"));
        }
        return ok;
    }

    @Override
    public String downloadBill(LocalDate billDate, PayChannelConfig config) {
        // Real implementation: pay/downloadbill returns a text bill; handle gzip and the "no bill" response
        return mockChannelServer.generateBillContent(ChannelType.WECHAT, billDate);
    }
}
