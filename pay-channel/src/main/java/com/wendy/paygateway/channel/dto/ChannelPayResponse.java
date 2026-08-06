package com.wendy.paygateway.channel.dto;

import lombok.Builder;
import lombok.Data;

/** Channel place-order response. */
@Data
@Builder
public class ChannelPayResponse {

    /** Whether the channel accepted the order (acceptance does not mean the user has paid). */
    private boolean accepted;
    /** Channel trade number. */
    private String channelTradeNo;
    /** Checkout URL or QR code payload. */
    private String payUrl;
    /** True when payment succeeded synchronously (balance payments); third parties are always false and use async webhooks. */
    private boolean syncPaid;
    private String errorCode;
    private String errorMsg;

    public static ChannelPayResponse fail(String code, String msg) {
        return ChannelPayResponse.builder().accepted(false).errorCode(code).errorMsg(msg).build();
    }
}
