package com.wendy.paygateway.channel.adapter;

import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Adapter factory: resolves an implementation by channel type. Adding a channel = adding a bean. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelAdapterFactory {

    private final List<PayChannelAdapter> adapters;
    private final Map<ChannelType, PayChannelAdapter> registry = new EnumMap<>(ChannelType.class);

    @PostConstruct
    public void init() {
        adapters.forEach(a -> registry.put(a.channelType(), a));
        log.info("[Channel adapters] registered {}: {}", registry.size(), registry.keySet());
    }

    public PayChannelAdapter get(ChannelType channelType) {
        PayChannelAdapter adapter = registry.get(channelType);
        if (adapter == null) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_FOUND, String.valueOf(channelType));
        }
        return adapter;
    }
}
