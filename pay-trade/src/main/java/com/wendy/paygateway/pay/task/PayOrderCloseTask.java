package com.wendy.paygateway.pay.task;

import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.repository.PayOrderRepository;
import com.wendy.paygateway.pay.service.PayOrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Auto-closes pay orders that have expired.
 *
 * <p>Why scan the table instead of listening for Redis key expiry: keyspace notifications are
 * <b>not reliably delivered</b> — a Redis restart or a dropped subscriber loses events — and a
 * money flow cannot depend on that alone. The usual production shape is "Redis or a delay queue as
 * the fast path, a scheduled table scan as the backstop". This implements the backstop; scanning
 * once a minute is plenty for the sandbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayOrderCloseTask {

    private static final int BATCH_SIZE = 200;

    private final PayOrderRepository payOrderRepository;
    private final PayOrderStatusService payOrderStatusService;

    @Scheduled(cron = "${pay.order.close-task-cron:0 */1 * * * ?}")
    public void closeExpiredOrders() {
        List<PayOrder> expired = payOrderRepository.listExpiredWaitPay(BATCH_SIZE);
        if (expired.isEmpty()) {
            return;
        }
        int closed = 0;
        for (PayOrder order : expired) {
            try {
                if (payOrderStatusService.closeOrder(order.getPayNo(), "auto-closed: expired unpaid")) {
                    closed++;
                }
            } catch (Exception e) {
                log.error("[Close task] failed to close payNo={}", order.getPayNo(), e);
            }
        }
        log.info("[Close task] scanned {} expired pay order(s), closed {} (MQ event sent so upstream releases stock)",
                expired.size(), closed);
    }
}
