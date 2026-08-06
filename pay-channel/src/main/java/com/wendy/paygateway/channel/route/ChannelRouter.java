package com.wendy.paygateway.channel.route;

import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Channel routing. The caller may name a channel explicitly, or let the gateway pick the best one
 * by amount range, user region, priority and fee rate.
 *
 * <p>An explicitly named channel still goes through the admission checks, so an upstream system
 * passing a disabled or over-limit channel fails fast instead of failing at the channel API call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRouter {

    private final PayChannelConfigService channelConfigService;

    /**
     * @param preferred channel named by the caller; null means let the gateway route automatically
     * @param amount    order amount
     * @param region    user region, e.g. CN or HK
     */
    public PayChannelConfig route(ChannelType preferred, BigDecimal amount, String region) {
        if (preferred != null) {
            PayChannelConfig config = channelConfigService.getEnabled(preferred);
            if (!config.supportAmount(amount)) {
                throw BizException.of(ErrorCode.CHANNEL_NOT_MATCH, preferred + " allows ["
                        + config.getMinAmount() + ", " + config.getMaxAmount() + "], this order is " + amount);
            }
            if (!config.supportRegion(region)) {
                throw BizException.of(ErrorCode.CHANNEL_NOT_MATCH, preferred + " does not serve region " + region);
            }
            return config;
        }
        return autoRoute(amount, region);
    }

    /**
     * Automatic routing. Balance is an internal funds account and must be chosen explicitly by the
     * user, so it never takes part in automatic selection.
     */
    private PayChannelConfig autoRoute(BigDecimal amount, String region) {
        List<PayChannelConfig> candidates = channelConfigService.listEnabled().stream()
                .filter(c -> c.getChannelType() != ChannelType.BALANCE)
                .filter(c -> c.supportAmount(amount))
                .filter(c -> c.supportRegion(region))
                .sorted(Comparator.<PayChannelConfig, Integer>comparing(PayChannelConfig::getPriority)
                        .thenComparing(PayChannelConfig::getFeeRate))
                .toList();
        if (candidates.isEmpty()) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_MATCH, "amount=" + amount + ", region=" + region);
        }
        PayChannelConfig chosen = candidates.get(0);
        log.info("[Channel routing] amount={} region={} selected {} ({} candidate(s))",
                amount, region, chosen.getChannelType(), candidates.size());
        return chosen;
    }
}
