package com.wendy.paygateway.common.enums;

import lombok.Getter;

/** Payment channel type. */
@Getter
public enum ChannelType {

    ALIPAY("Alipay"),
    WECHAT("WeChat Pay"),
    BALANCE("Account balance");

    private final String desc;

    ChannelType(String desc) {
        this.desc = desc;
    }

    public static ChannelType of(String name) {
        for (ChannelType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }
}
