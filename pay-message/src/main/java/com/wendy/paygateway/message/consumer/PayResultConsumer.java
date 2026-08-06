package com.wendy.paygateway.message.consumer;

import com.wendy.paygateway.common.infra.IdempotentStore;
import com.wendy.paygateway.common.util.JsonUtils;
import com.wendy.paygateway.message.dto.MessageEnvelope;
import com.wendy.paygateway.message.dto.PayResultMessage;
import com.wendy.paygateway.message.mq.MqTemplate;
import com.wendy.paygateway.message.mq.MqTopics;
import com.wendy.paygateway.message.service.LocalMessageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Payment/refund result consumer: forwards the gateway's money events to the upstream business
 * system.
 *
 * <p>The consumer has to be idempotent too — MQ delivers at least once and the outbox
 * compensation task may republish — so it dedupes on messageId in the idempotency store.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayResultConsumer {

    private static final Duration CONSUMED_TTL = Duration.ofHours(6);

    private final MqTemplate mqTemplate;
    private final LocalMessageService localMessageService;
    private final MockBusinessSystemClient businessSystemClient;
    private final IdempotentStore idempotentStore;

    @PostConstruct
    public void subscribe() {
        mqTemplate.subscribe(MqTopics.PAY_RESULT, this::onMessage);
        mqTemplate.subscribe(MqTopics.REFUND_RESULT, this::onMessage);
        mqTemplate.subscribe(MqTopics.PAY_CLOSED, this::onMessage);
    }

    private void onMessage(String body) {
        MessageEnvelope envelope = JsonUtils.parse(body, MessageEnvelope.class);
        String dedupKey = "pay:mq:consumed:" + envelope.getMessageId();
        if (!idempotentStore.setIfAbsent(dedupKey, "1", CONSUMED_TTL)) {
            log.info("[MQ consume] duplicate message, ignored idempotently messageId={}", envelope.getMessageId());
            localMessageService.markConsumed(envelope.getMessageId());
            return;
        }
        try {
            PayResultMessage payload = JsonUtils.parse(envelope.getPayload(), PayResultMessage.class);
            businessSystemClient.notifyPayResult(payload);
            localMessageService.markConsumed(envelope.getMessageId());
        } catch (RuntimeException e) {
            // Release the dedupe key on failure, otherwise the MQ redelivery would be mistaken for
            // a duplicate; rethrow so the broker nacks and redelivers
            idempotentStore.delete(dedupKey);
            localMessageService.markFailed(envelope.getMessageId(), e.getMessage());
            throw e;
        }
    }
}
