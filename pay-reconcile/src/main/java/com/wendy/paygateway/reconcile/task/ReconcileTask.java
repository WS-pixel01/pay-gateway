package com.wendy.paygateway.reconcile.task;

import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.reconcile.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily reconciliation job: pulls yesterday's channel bill in the small hours and compares it with
 * our own records.
 *
 * <p>With multiple instances this kind of job needs distributed mutual exclusion (a Redisson lock or
 * XXL-JOB sharding), otherwise discrepancy records get created twice. Here the writes clear any
 * existing rows for the same (date, channel) first, so the job is naturally idempotent and a re-run
 * cannot leave dirty data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileTask {

    private static final List<ChannelType> RECONCILE_CHANNELS = List.of(ChannelType.ALIPAY, ChannelType.WECHAT);

    private final ReconcileService reconcileService;

    @Scheduled(cron = "${pay.reconcile.cron:0 0 2 * * ?}")
    public void dailyReconcile() {
        LocalDate billDate = LocalDate.now().minusDays(1);
        log.info("[Reconcile job] starting reconciliation for {}", billDate);
        reconcileService.reconcileAll(billDate, RECONCILE_CHANNELS);
    }
}
