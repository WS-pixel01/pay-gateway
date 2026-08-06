package com.wendy.paygateway.message.consumer;

import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.message.dto.PayResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simulated upstream business system (mall, membership, ...). In production this would be an
 * HTTP/RPC call; here the notifications are simply kept in memory and exposed through
 * /api/ops/notifications, so you can verify that payment results always get delivered in the end.
 *
 * <p>{@code pay.mock.notify-fail-times} makes it fail the first N attempts on purpose, which
 * demonstrates MQ redelivery and outbox compensation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockBusinessSystemClient {

    private final PayProperties payProperties;
    private final List<PayResultMessage> received = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public void notifyPayResult(PayResultMessage message) {
        String key = message.getPayNo() + ":" + message.getStatus()
                + (message.getRefundNo() == null ? "" : ":" + message.getRefundNo());
        int attempt = attempts.merge(key, 1, Integer::sum);
        if (attempt <= payProperties.getMock().getNotifyFailTimes()) {
            throw new IllegalStateException(
                    "Upstream business system temporarily unavailable (simulated failure #" + attempt + ")");
        }
        received.add(message);
        log.info("[Upstream system] received payment result bizOrderNo={} status={} amount={}",
                message.getBizOrderNo(), message.getStatus(), message.getAmount());
    }

    public List<PayResultMessage> receivedNotifications() {
        return List.copyOf(received);
    }
}
