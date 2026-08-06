package com.wendy.paygateway.channel.adapter;

import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelPayResponse;
import com.wendy.paygateway.channel.dto.ChannelQueryResponse;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.common.enums.ChannelType;

import java.time.LocalDate;
import java.util.Map;

/**
 * Channel adapter: collapses wildly different vendor SDKs into four operations — place order,
 * refund, query and verify webhook.
 *
 * <p>Onboarding a new channel means adding one implementation class; business code above does not
 * change at all.
 */
public interface PayChannelAdapter {

    ChannelType channelType();

    /** Place an order. */
    ChannelPayResponse pay(ChannelPayRequest request, PayChannelConfig config);

    /** Request a refund. */
    ChannelRefundResponse refund(ChannelRefundRequest request, PayChannelConfig config);

    /** Query an order actively, to compensate for a lost webhook. */
    ChannelQueryResponse query(String payNo, PayChannelConfig config);

    /** Verify the signature of an async webhook. */
    boolean verifyWebhook(Map<String, String> params, PayChannelConfig config);

    /**
     * Download the daily bill and return the raw CSV (header plus rows).
     *
     * <p>A real implementation would call alipay.data.dataservice.bill.downloadurl.query or the
     * WeChat downloadbill API and gunzip the result. The reconciliation module only knows this
     * interface, so swapping in a real channel does not change a single line of reconciliation code.
     *
     * @return null when the channel publishes no bill at all (e.g. the internal balance channel)
     */
    String downloadBill(LocalDate billDate, PayChannelConfig config);
}
