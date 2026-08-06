package com.wendy.paygateway.message.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.common.enums.MessageStatus;
import com.wendy.paygateway.common.util.IdGenerator;
import com.wendy.paygateway.common.util.JsonUtils;
import com.wendy.paygateway.message.dto.MessageEnvelope;
import com.wendy.paygateway.message.entity.LocalMessage;
import com.wendy.paygateway.message.mapper.LocalMessageMapper;
import com.wendy.paygateway.message.mq.MqTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox service — the heart of eventually-consistent payment result delivery.
 *
 * <p>Sequence: insert a NEW message inside the business transaction -&gt; publish to MQ only
 * <b>after</b> the commit -&gt; the consumer acknowledges with CONSUMED. If any step dies, the row
 * is left in NEW/SENT/FAILED and {@code LocalMessageRetryTask} picks it up and republishes it, so
 * no message is ever lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalMessageService {

    private static final int DEFAULT_MAX_RETRY = 5;

    private final LocalMessageMapper localMessageMapper;
    private final MqTemplate mqTemplate;

    /**
     * Call this inside the business transaction: the message lands in the same database and the
     * same transaction as the business data, and is only published once that transaction commits.
     *
     * @return messageId
     */
    public String saveAndSendAfterCommit(String topic, String bizKey, Object payload) {
        LocalMessage message = new LocalMessage();
        message.setMessageId(IdGenerator.messageId());
        message.setTopic(topic);
        message.setBizKey(bizKey);
        message.setPayload(JsonUtils.toJson(payload));
        message.setStatus(MessageStatus.NEW);
        message.setRetryCount(0);
        message.setMaxRetry(DEFAULT_MAX_RETRY);
        localMessageMapper.insert(message);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(message);
                }
            });
        } else {
            // Not inside a transaction (e.g. called directly by the compensation task): publish now
            send(message);
        }
        return message.getMessageId();
    }

    /** Publish to MQ and mark SENT; on failure mark FAILED and let the compensation task retry. */
    public void send(LocalMessage message) {
        try {
            MessageEnvelope envelope = new MessageEnvelope(
                    message.getMessageId(), message.getTopic(), message.getBizKey(),
                    message.getPayload(), System.currentTimeMillis());
            mqTemplate.send(message.getTopic(), JsonUtils.toJson(envelope));
            updateStatus(message.getMessageId(), MessageStatus.SENT, null);
            log.info("[Outbox] published messageId={} topic={} bizKey={}",
                    message.getMessageId(), message.getTopic(), message.getBizKey());
        } catch (Exception e) {
            log.error("[Outbox] publish failed messageId={}: {}", message.getMessageId(), e.getMessage());
            markFailed(message.getMessageId(), e.getMessage());
        }
    }

    /** Consumer acknowledgement. */
    public void markConsumed(String messageId) {
        updateStatus(messageId, MessageStatus.CONSUMED, null);
    }

    /** Publish/consume failed: bump the retry counter and schedule the next attempt with exponential backoff. */
    public void markFailed(String messageId, String error) {
        LocalMessage message = getByMessageId(messageId);
        if (message == null) {
            return;
        }
        int retry = message.getRetryCount() == null ? 0 : message.getRetryCount() + 1;
        LocalMessage update = new LocalMessage();
        update.setId(message.getId());
        update.setRetryCount(retry);
        update.setLastError(truncate(error));
        if (retry >= message.getMaxRetry()) {
            update.setStatus(MessageStatus.DEAD);
            log.error("[Outbox] still failing after {} retries, dead-lettering messageId={}", retry, messageId);
        } else {
            update.setStatus(MessageStatus.FAILED);
            update.setNextRetryTime(LocalDateTime.now().plusSeconds((long) Math.pow(2, retry) * 5));
        }
        localMessageMapper.updateById(update);
    }

    /** Scan messages needing compensation: NEW (publish failed after commit), or FAILED and due for retry. */
    public List<LocalMessage> listRetryable(int limit) {
        return localMessageMapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
                .and(w -> w.eq(LocalMessage::getStatus, MessageStatus.NEW)
                        .or(q -> q.eq(LocalMessage::getStatus, MessageStatus.FAILED)
                                .le(LocalMessage::getNextRetryTime, LocalDateTime.now())))
                .lt(LocalMessage::getCreateTime, LocalDateTime.now().minusSeconds(10))
                .orderByAsc(LocalMessage::getId)
                .last("limit " + limit));
    }

    public LocalMessage getByMessageId(String messageId) {
        return localMessageMapper.selectOne(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getMessageId, messageId));
    }

    public List<LocalMessage> listByBizKey(String bizKey) {
        return localMessageMapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getBizKey, bizKey)
                .orderByDesc(LocalMessage::getId));
    }

    private void updateStatus(String messageId, MessageStatus status, String error) {
        LocalMessage message = getByMessageId(messageId);
        if (message == null) {
            return;
        }
        LocalMessage update = new LocalMessage();
        update.setId(message.getId());
        update.setStatus(status);
        update.setLastError(truncate(error));
        localMessageMapper.updateById(update);
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
