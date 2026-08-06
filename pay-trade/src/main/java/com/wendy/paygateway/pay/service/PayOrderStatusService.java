package com.wendy.paygateway.pay.service;

import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.message.dto.PayResultMessage;
import com.wendy.paygateway.message.mq.MqTopics;
import com.wendy.paygateway.message.service.LocalMessageService;
import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.repository.PayOrderRepository;
import com.wendy.paygateway.pay.statemachine.PayOrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The single entry point for pay order status transitions.
 *
 * <p>Every method is one local transaction that updates the pay order and writes an outbox row;
 * the MQ publish happens only after that transaction commits.
 *
 * <p>A return value of {@code true} means this call really changed the status; {@code false} means
 * a duplicate request was swallowed idempotently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderStatusService {

    private final PayOrderRepository payOrderRepository;
    private final PayOrderStateMachine stateMachine;
    private final LocalMessageService localMessageService;

    /** Payment succeeded. Duplicate webhooks return false. */
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaySuccess(String payNo, String channelTradeNo, BigDecimal paidAmount) {
        PayOrder order = mustGet(payNo);

        if (order.getStatus() != PayOrderStatus.WAIT_PAY) {
            if (order.getStatus().isPaid() || order.getStatus() == PayOrderStatus.REFUNDED) {
                log.info("[Payment success] pay order already handled, ignoring idempotently payNo={} status={}",
                        payNo, order.getStatus());
                return false;
            }
            // A success webhook for a closed/failed order is the classic one-sided entry: the money
            // sits at the channel while we have no matching income recorded
            log.error("[MONEY ALERT] success webhook for a terminal pay order, possible one-sided entry "
                            + "payNo={} status={} channelTradeNo={}",
                    payNo, order.getStatus(), channelTradeNo);
            return false;
        }

        // Amount check: stops a tampered webhook from claiming a smaller amount
        if (!MoneyUtil.eq(order.getAmount(), paidAmount)) {
            log.error("[MONEY ALERT] webhook amount differs from the pay order payNo={} due={} paid={}",
                    payNo, order.getAmount(), paidAmount);
            throw BizException.of(ErrorCode.PAY_AMOUNT_MISMATCH,
                    "due " + order.getAmount() + ", webhook reported " + paidAmount);
        }

        stateMachine.assertCanTransfer(payNo, order.getStatus(), PayOrderStatus.SUCCESS);
        if (payOrderRepository.casToSuccess(payNo, channelTradeNo, paidAmount) == 0) {
            log.info("[Payment success] CAS missed, a concurrent request already handled it payNo={}", payNo);
            return false;
        }

        order.setStatus(PayOrderStatus.SUCCESS);
        order.setPaidAmount(paidAmount);
        order.setChannelTradeNo(channelTradeNo);
        localMessageService.saveAndSendAfterCommit(MqTopics.PAY_RESULT, payNo,
                buildMessage(order, PayOrderStatus.SUCCESS.name(), null, null));
        log.info("[Payment success] payNo={} bizOrderNo={} amount={} channelTradeNo={}",
                payNo, order.getBizOrderNo(), paidAmount, channelTradeNo);
        return true;
    }

    /** Payment failed. */
    @Transactional(rollbackFor = Exception.class)
    public boolean markPayFail(String payNo, String reason) {
        PayOrder order = mustGet(payNo);
        if (order.getStatus() != PayOrderStatus.WAIT_PAY) {
            log.info("[Payment failed] pay order is not awaiting payment, ignoring payNo={} status={}",
                    payNo, order.getStatus());
            return false;
        }
        stateMachine.assertCanTransfer(payNo, order.getStatus(), PayOrderStatus.FAIL);
        if (payOrderRepository.casToFail(payNo, reason) == 0) {
            return false;
        }
        order.setStatus(PayOrderStatus.FAIL);
        localMessageService.saveAndSendAfterCommit(MqTopics.PAY_RESULT, payNo,
                buildMessage(order, PayOrderStatus.FAIL.name(), null, null));
        log.info("[Payment failed] payNo={} reason={}", payNo, reason);
        return true;
    }

    /** Close the order (expired, or cancelled by the caller); upstream releases stock on the message. */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeOrder(String payNo, String reason) {
        PayOrder order = mustGet(payNo);
        if (order.getStatus() != PayOrderStatus.WAIT_PAY) {
            log.info("[Close order] pay order is not awaiting payment, ignoring payNo={} status={}",
                    payNo, order.getStatus());
            return false;
        }
        stateMachine.assertCanTransfer(payNo, order.getStatus(), PayOrderStatus.CLOSED);
        if (payOrderRepository.casToClosed(payNo, reason) == 0) {
            return false;
        }
        order.setStatus(PayOrderStatus.CLOSED);
        localMessageService.saveAndSendAfterCommit(MqTopics.PAY_CLOSED, payNo,
                buildMessage(order, PayOrderStatus.CLOSED.name(), null, null));
        log.info("[Close order] payNo={} reason={}", payNo, reason);
        return true;
    }

    /**
     * Write a successful refund back to the pay order: accumulate the refunded amount and move to
     * PART_REFUNDED or REFUNDED.
     *
     * @return true when this call actually accumulated the amount
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyRefundSuccess(String payNo, String refundNo, BigDecimal refundAmount) {
        PayOrder order = mustGet(payNo);
        if (!order.getStatus().isPaid()) {
            log.warn("[Refund write-back] pay order status does not allow refunds payNo={} status={}",
                    payNo, order.getStatus());
            return false;
        }
        BigDecimal newRefunded = MoneyUtil.add(order.getRefundedAmount(), refundAmount);
        if (MoneyUtil.gt(newRefunded, order.getPaidAmount())) {
            throw BizException.of(ErrorCode.REFUND_AMOUNT_EXCEED,
                    "already refunded " + order.getRefundedAmount() + " + this refund " + refundAmount
                            + " > paid " + order.getPaidAmount());
        }
        PayOrderStatus newStatus = MoneyUtil.eq(newRefunded, order.getPaidAmount())
                ? PayOrderStatus.REFUNDED : PayOrderStatus.PART_REFUNDED;
        stateMachine.assertCanTransfer(payNo, order.getStatus(), newStatus);

        if (payOrderRepository.casApplyRefund(payNo, order.getRefundedAmount(), newRefunded, newStatus) == 0) {
            log.info("[Refund write-back] CAS missed, a concurrent refund already handled it payNo={}", payNo);
            return false;
        }

        order.setRefundedAmount(newRefunded);
        order.setStatus(newStatus);
        localMessageService.saveAndSendAfterCommit(MqTopics.REFUND_RESULT, payNo,
                buildMessage(order, newStatus.name(), refundNo, refundAmount));
        log.info("[Refund write-back] payNo={} refundNo={} thisRefund={} totalRefunded={} status={}",
                payNo, refundNo, refundAmount, newRefunded, newStatus);
        return true;
    }

    private PayOrder mustGet(String payNo) {
        PayOrder order = payOrderRepository.getByPayNo(payNo);
        if (order == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND, payNo);
        }
        return order;
    }

    private PayResultMessage buildMessage(PayOrder order, String status,
                                          String refundNo, BigDecimal refundAmount) {
        return PayResultMessage.builder()
                .payNo(order.getPayNo())
                .bizSystem(order.getBizSystem())
                .bizOrderNo(order.getBizOrderNo())
                .userId(order.getUserId())
                .channelType(order.getChannelType())
                .channelTradeNo(order.getChannelTradeNo())
                .amount(order.getAmount())
                .status(status)
                .refundNo(refundNo)
                .refundAmount(refundAmount)
                .occurTime(LocalDateTime.now())
                .build();
    }
}
