package com.wendy.paygateway.pay.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.mapper.PayOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pay order data access. Every status transition is a CAS update
 * ({@code where status = <expected>}), so a single UPDATE statement makes "read - check - write"
 * atomic and is inherently immune to concurrent duplicate webhooks.
 */
@Repository
@RequiredArgsConstructor
public class PayOrderRepository {

    private final PayOrderMapper payOrderMapper;

    public void insert(PayOrder order) {
        payOrderMapper.insert(order);
    }

    public PayOrder getByPayNo(String payNo) {
        return payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getPayNo, payNo));
    }

    public PayOrder getByBizOrderNo(String bizSystem, String bizOrderNo) {
        return payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getBizSystem, bizSystem)
                .eq(PayOrder::getBizOrderNo, bizOrderNo));
    }

    /** Record the channel's place-order result: trade number and checkout URL. */
    public void fillChannelInfo(String payNo, String channelTradeNo, String payUrl) {
        payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getChannelTradeNo, channelTradeNo)
                .set(PayOrder::getPayUrl, payUrl)
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .eq(PayOrder::getPayNo, payNo));
    }

    /** WAIT_PAY -&gt; SUCCESS. Returns 0 when another thread or a duplicate webhook already handled it. */
    public int casToSuccess(String payNo, String channelTradeNo, BigDecimal paidAmount) {
        return payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getStatus, PayOrderStatus.SUCCESS)
                .set(PayOrder::getPaidAmount, MoneyUtil.scale(paidAmount))
                .set(PayOrder::getChannelTradeNo, channelTradeNo)
                .set(PayOrder::getPayTime, LocalDateTime.now())
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(PayOrder::getPayNo, payNo)
                .eq(PayOrder::getStatus, PayOrderStatus.WAIT_PAY));
    }

    /** WAIT_PAY -> FAIL。 */
    public int casToFail(String payNo, String reason) {
        return payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getStatus, PayOrderStatus.FAIL)
                .set(PayOrder::getFailReason, truncateReason(reason))
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(PayOrder::getPayNo, payNo)
                .eq(PayOrder::getStatus, PayOrderStatus.WAIT_PAY));
    }

    /** WAIT_PAY -> CLOSED。 */
    public int casToClosed(String payNo, String reason) {
        return payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getStatus, PayOrderStatus.CLOSED)
                .set(PayOrder::getCloseTime, LocalDateTime.now())
                .set(PayOrder::getFailReason, truncateReason(reason))
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(PayOrder::getPayNo, payNo)
                .eq(PayOrder::getStatus, PayOrderStatus.WAIT_PAY));
    }

    /**
     * Accumulate the refunded amount and move the status forward.
     *
     * <p>The where clause pins {@code refunded_amount} to the value we read, so concurrent refunds
     * cannot corrupt the total.
     */
    public int casApplyRefund(String payNo, BigDecimal expectedRefunded,
                              BigDecimal newRefunded, PayOrderStatus newStatus) {
        return payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getRefundedAmount, MoneyUtil.scale(newRefunded))
                .set(PayOrder::getStatus, newStatus)
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(PayOrder::getPayNo, payNo)
                .eq(PayOrder::getRefundedAmount, MoneyUtil.scale(expectedRefunded))
                .in(PayOrder::getStatus, PayOrderStatus.SUCCESS, PayOrderStatus.PART_REFUNDED));
    }

    /** Pay orders that have expired but are still awaiting payment. */
    public List<PayOrder> listExpiredWaitPay(int limit) {
        return payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getStatus, PayOrderStatus.WAIT_PAY)
                .lt(PayOrder::getExpireTime, LocalDateTime.now())
                .orderByAsc(PayOrder::getId)
                .last("limit " + limit));
    }

    /** Locally successful trades for a given day, used by reconciliation. */
    public List<PayOrder> listPaidByDate(LocalDateTime start, LocalDateTime end) {
        return payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .in(PayOrder::getStatus, PayOrderStatus.SUCCESS,
                        PayOrderStatus.PART_REFUNDED, PayOrderStatus.REFUNDED)
                .ge(PayOrder::getPayTime, start)
                .lt(PayOrder::getPayTime, end));
    }

    /**
     * Failure reasons are often long third-party or JDBC exception strings. Truncate to the column
     * width before persisting, so recording a failure cannot itself fail.
     */
    private String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 255 ? reason.substring(0, 252) + "..." : reason;
    }

    public List<PayOrder> listAll(int limit) {
        return payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .orderByDesc(PayOrder::getId)
                .last("limit " + limit));
    }
}
