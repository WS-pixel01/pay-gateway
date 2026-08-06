package com.wendy.paygateway.reconcile.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.DiffType;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.repository.PayOrderRepository;
import com.wendy.paygateway.reconcile.dto.ReconcileResult;
import com.wendy.paygateway.reconcile.entity.ChannelBill;
import com.wendy.paygateway.reconcile.entity.ReconcileDiff;
import com.wendy.paygateway.reconcile.entity.ReconcileTaskLog;
import com.wendy.paygateway.reconcile.mapper.ReconcileDiffMapper;
import com.wendy.paygateway.reconcile.mapper.ReconcileTaskLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily reconciliation: local trade records versus the channel bill, compared row by row into
 * discrepancy records.
 *
 * <p>Comparison rules:
 * <ul>
 *   <li>present locally, absent from the channel -&gt; {@code CHANNEL_MISS}: we booked income the
 *       channel has no record of, which may be a forged webhook or a channel omission and needs a
 *       human to check;</li>
 *   <li>present at the channel, absent locally -&gt; {@code LOCAL_MISS}: the money was collected but
 *       never booked — the textbook <b>one-sided entry</b>, usually a lost webhook, fixed by
 *       querying the channel and backfilling;</li>
 *   <li>present on both sides with different amounts -&gt; {@code AMOUNT_MISMATCH}: a tampered amount,
 *       or a partial refund that was never synced.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PayOrderRepository payOrderRepository;
    private final BillDownloadService billDownloadService;
    private final ReconcileDiffMapper reconcileDiffMapper;
    private final ReconcileTaskLogMapper reconcileTaskLogMapper;

    /** Run a full reconciliation for one channel on one day. */
    public ReconcileResult reconcile(LocalDate billDate, ChannelType channelType) {
        long start = System.currentTimeMillis();
        String dateStr = billDate.format(DATE_FMT);

        // 1. fetch and parse the channel bill
        billDownloadService.downloadAndParse(billDate, channelType);
        List<ChannelBill> channelBills = billDownloadService.listBills(billDate, channelType);
        Map<String, ChannelBill> channelMap = channelBills.stream()
                .collect(Collectors.toMap(ChannelBill::getOutTradeNo, b -> b, (a, b) -> a));

        // 2. load the day's locally successful trades
        List<PayOrder> localOrders = payOrderRepository
                .listPaidByDate(billDate.atStartOfDay(), billDate.plusDays(1).atStartOfDay())
                .stream().filter(o -> o.getChannelType() == channelType).toList();
        Map<String, PayOrder> localMap = localOrders.stream()
                .collect(Collectors.toMap(PayOrder::getPayNo, o -> o, (a, b) -> a));

        // 3. idempotent re-runs: clear this day's old discrepancy records for this channel
        reconcileDiffMapper.delete(Wrappers.<ReconcileDiff>lambdaQuery()
                .eq(ReconcileDiff::getBillDate, dateStr)
                .eq(ReconcileDiff::getChannelType, channelType));

        List<ReconcileDiff> diffs = new ArrayList<>();

        // 4. compare using our own records as the baseline
        for (PayOrder order : localOrders) {
            ChannelBill bill = channelMap.get(order.getPayNo());
            if (bill == null) {
                diffs.add(buildDiff(dateStr, channelType, order.getPayNo(), order.getChannelTradeNo(),
                        order.getPaidAmount(), null, order.getStatus().name(), null,
                        DiffType.CHANNEL_MISS,
                        "booked locally but absent from the channel bill; needs to be raised with the channel"));
                continue;
            }
            if (!MoneyUtil.eq(order.getPaidAmount(), bill.getAmount())) {
                diffs.add(buildDiff(dateStr, channelType, order.getPayNo(), bill.getChannelTradeNo(),
                        order.getPaidAmount(), bill.getAmount(), order.getStatus().name(), bill.getTradeStatus(),
                        DiffType.AMOUNT_MISMATCH,
                        "local " + order.getPaidAmount() + " vs channel " + bill.getAmount()));
            }
        }

        // 5. sweep the other way for trades the channel has and we do not
        for (ChannelBill bill : channelBills) {
            if (!localMap.containsKey(bill.getOutTradeNo())) {
                diffs.add(buildDiff(dateStr, channelType, bill.getOutTradeNo(), bill.getChannelTradeNo(),
                        null, bill.getAmount(), null, bill.getTradeStatus(),
                        DiffType.LOCAL_MISS,
                        "collected by the channel but no successful local record; likely a lost webhook "
                                + "leaving a one-sided entry"));
            }
        }

        diffs.forEach(reconcileDiffMapper::insert);

        long cost = System.currentTimeMillis() - start;
        String summary = summarize(diffs);
        saveTaskLog(dateStr, channelType, localOrders.size(), channelBills.size(), diffs.size(), cost, summary);

        log.info("[Reconcile] {} {} local {} / channel {} / discrepancies {} [{}] took {}ms",
                dateStr, channelType, localOrders.size(), channelBills.size(), diffs.size(), summary, cost);

        return ReconcileResult.builder()
                .billDate(dateStr)
                .channelType(channelType)
                .localCount(localOrders.size())
                .channelCount(channelBills.size())
                .localAmount(sum(localOrders.stream().map(PayOrder::getPaidAmount).toList()))
                .channelAmount(sum(channelBills.stream().map(ChannelBill::getAmount).toList()))
                .diffCount(diffs.size())
                .diffSummary(summary)
                .costMs(cost)
                .build();
    }

    /** Reconcile every channel in the given list. */
    public List<ReconcileResult> reconcileAll(LocalDate billDate, List<ChannelType> channelTypes) {
        List<ReconcileResult> results = new ArrayList<>();
        for (ChannelType channelType : channelTypes) {
            try {
                results.add(reconcile(billDate, channelType));
            } catch (Exception e) {
                log.error("[Reconcile] {} {} run failed", billDate, channelType, e);
                saveTaskLog(billDate.format(DATE_FMT), channelType, 0, 0, 0, 0, "FAIL: " + e.getMessage());
            }
        }
        return results;
    }

    public List<ReconcileDiff> listDiffs(LocalDate billDate, ChannelType channelType) {
        return reconcileDiffMapper.selectList(Wrappers.<ReconcileDiff>lambdaQuery()
                .eq(ReconcileDiff::getBillDate, billDate.format(DATE_FMT))
                .eq(channelType != null, ReconcileDiff::getChannelType, channelType)
                .orderByDesc(ReconcileDiff::getId));
    }

    /** Mark a discrepancy record as handled by a human. */
    public boolean markHandled(Long diffId, String remark) {
        ReconcileDiff update = new ReconcileDiff();
        update.setId(diffId);
        update.setHandled(1);
        update.setRemark(remark);
        return reconcileDiffMapper.updateById(update) > 0;
    }

    private ReconcileDiff buildDiff(String billDate, ChannelType channelType, String outTradeNo,
                                    String channelTradeNo, BigDecimal localAmount, BigDecimal channelAmount,
                                    String localStatus, String channelStatus, DiffType diffType, String remark) {
        ReconcileDiff diff = new ReconcileDiff();
        diff.setBillDate(billDate);
        diff.setChannelType(channelType);
        diff.setOutTradeNo(outTradeNo);
        diff.setChannelTradeNo(channelTradeNo);
        diff.setLocalAmount(localAmount);
        diff.setChannelAmount(channelAmount);
        diff.setLocalStatus(localStatus);
        diff.setChannelStatus(channelStatus);
        diff.setDiffType(diffType);
        diff.setHandled(0);
        diff.setRemark(remark);
        return diff;
    }

    private void saveTaskLog(String billDate, ChannelType channelType, int localCount,
                             int channelCount, int diffCount, long cost, String remark) {
        ReconcileTaskLog taskLog = new ReconcileTaskLog();
        taskLog.setBillDate(billDate);
        taskLog.setChannelType(channelType);
        taskLog.setLocalCount(localCount);
        taskLog.setChannelCount(channelCount);
        taskLog.setDiffCount(diffCount);
        taskLog.setStatus(remark != null && remark.startsWith("FAIL") ? "FAIL" : "SUCCESS");
        taskLog.setCostMs(cost);
        taskLog.setRemark(remark);
        reconcileTaskLogMapper.insert(taskLog);
    }

    private String summarize(List<ReconcileDiff> diffs) {
        Map<DiffType, Integer> counter = new EnumMap<>(DiffType.class);
        diffs.forEach(d -> counter.merge(d.getDiffType(), 1, Integer::sum));
        if (counter.isEmpty()) {
            return "books balanced";
        }
        Map<String, Integer> readable = new HashMap<>();
        counter.forEach((k, v) -> readable.put(k.name(), v));
        return readable.toString();
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(MoneyUtil.ZERO, MoneyUtil::add);
    }
}
