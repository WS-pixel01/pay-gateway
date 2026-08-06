package com.wendy.paygateway.channel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.mapper.PayChannelConfigMapper;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Channel configuration lookup with a local cache.
 *
 * <p>Channel configuration is the textbook read-mostly dataset, so it is cached locally instead of
 * hitting the database on every checkout. After an admin change, call {@link #refresh()} — in a
 * distributed deployment that would be triggered by a config-centre or MQ broadcast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayChannelConfigService {

    private final PayChannelConfigMapper channelConfigMapper;
    private final Map<ChannelType, PayChannelConfig> cache = new ConcurrentHashMap<>();

    /** Reload the whole local cache. */
    public void refresh() {
        List<PayChannelConfig> list = channelConfigMapper.selectList(Wrappers.emptyWrapper());
        cache.clear();
        list.forEach(c -> cache.put(c.getChannelType(), c));
        log.info("[Channel config] cache refreshed, {} channel(s): {}", list.size(),
                list.stream().map(c -> c.getChannelType() + "(" + (c.available() ? "enabled" : "disabled") + ")")
                        .collect(Collectors.joining(", ")));
    }

    /** Look up a channel; throws if it does not exist or is disabled. */
    public PayChannelConfig getEnabled(ChannelType channelType) {
        PayChannelConfig config = cache.get(channelType);
        if (config == null || !config.available()) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_FOUND, String.valueOf(channelType));
        }
        return config;
    }

    public PayChannelConfig get(ChannelType channelType) {
        return cache.get(channelType);
    }

    /** All enabled channels, ordered by ascending priority. */
    public List<PayChannelConfig> listEnabled() {
        return cache.values().stream()
                .filter(PayChannelConfig::available)
                .sorted(Comparator.comparing(PayChannelConfig::getPriority))
                .toList();
    }

    public List<PayChannelConfig> listAll() {
        return cache.values().stream()
                .sorted(Comparator.comparing(PayChannelConfig::getPriority))
                .toList();
    }

    /** Enable or disable a channel (ops endpoint). */
    public void switchChannel(ChannelType channelType, boolean enabled) {
        PayChannelConfig config = cache.get(channelType);
        if (config == null) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_FOUND, String.valueOf(channelType));
        }
        PayChannelConfig update = new PayChannelConfig();
        update.setId(config.getId());
        update.setEnabled(enabled ? 1 : 0);
        channelConfigMapper.updateById(update);
        refresh();
        log.info("[Channel config] {} is now {}", channelType, enabled ? "enabled" : "disabled");
    }

    void updateKeyPair(Long id, String privateKey, String publicKey) {
        PayChannelConfig update = new PayChannelConfig();
        update.setId(id);
        update.setPrivateKey(privateKey);
        update.setPublicKey(publicKey);
        channelConfigMapper.updateById(update);
    }
}
