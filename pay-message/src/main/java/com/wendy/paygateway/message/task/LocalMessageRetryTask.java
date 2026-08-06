package com.wendy.paygateway.message.task;

import com.wendy.paygateway.message.entity.LocalMessage;
import com.wendy.paygateway.message.service.LocalMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox compensation task: republishes messages that either committed but never got published,
 * or got published but keep failing on the consumer side.
 *
 * <p>This closes the eventual-consistency loop, and it is the answer to the interview question
 * "what happens when the MQ goes down?".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRetryTask {

    private static final int BATCH_SIZE = 50;

    private final LocalMessageService localMessageService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retry() {
        List<LocalMessage> messages = localMessageService.listRetryable(BATCH_SIZE);
        if (messages.isEmpty()) {
            return;
        }
        log.info("[Outbox compensation] found {} message(s) to republish", messages.size());
        for (LocalMessage message : messages) {
            try {
                localMessageService.send(message);
            } catch (Exception e) {
                log.error("[Outbox compensation] republish error messageId={}", message.getMessageId(), e);
                localMessageService.markFailed(message.getMessageId(), e.getMessage());
            }
        }
    }
}
