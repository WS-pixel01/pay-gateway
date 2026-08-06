package com.wendy.paygateway.channel.dto;

import lombok.Builder;
import lombok.Data;

/** Channel refund acceptance response. */
@Data
@Builder
public class ChannelRefundResponse {

    private boolean accepted;
    private String channelRefundNo;
    /** True when the refund completed synchronously (balance refunds). */
    private boolean syncRefunded;
    private String errorCode;
    private String errorMsg;

    public static ChannelRefundResponse fail(String code, String msg) {
        return ChannelRefundResponse.builder().accepted(false).errorCode(code).errorMsg(msg).build();
    }
}
