package com.wendy.paygateway.refund.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.channel.adapter.ChannelAdapterFactory;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.enums.RefundStatus;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.idempotent.PayIdempotent;
import com.wendy.paygateway.common.infra.DistributedLock;
import com.wendy.paygateway.common.ratelimit.RateLimit;
import com.wendy.paygateway.common.util.IdGenerator;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.repository.PayOrderRepository;
import com.wendy.paygateway.pay.service.PayOrderStatusService;
import com.wendy.paygateway.refund.dto.RefundOrderVO;
import com.wendy.paygateway.refund.dto.RefundRequest;
import com.wendy.paygateway.refund.entity.RefundOrder;
import com.wendy.paygateway.refund.mapper.RefundOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Refund service.
 *
 * <p>Preventing duplicate and over-refunds comes down to one rule: refunds for the same pay order
 * must be serialised. Two concurrent partial refunds that both read the same
 * {@code refunded_amount} would double-spend. This uses two safeguards — a distributed lock keyed
 * by payNo, plus a CAS update on the pay order's {@code refunded_amount}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrderService {

    private static final String LOCK_PREFIX = "pay:lock:refund:";

    private final RefundOrderMapper refundOrderMapper;
    private final PayOrderRepository payOrderRepository;
    private final PayOrderStatusService payOrderStatusService;
    private final PayChannelConfigService payChannelConfigService;
    private final ChannelAdapterFactory channelAdapterFactory;
    private final DistributedLock distributedLock;
    private final PayProperties payProperties;

    /** Request a refund, full or partial. */
    @RateLimit(name = "refund", limit = 10, windowSeconds = 1, dimension = RateLimit.Dimension.IP)
    @PayIdempotent(prefix = "refund", key = "#request.bizRefundNo", ttlSeconds = 600)
    public RefundOrderVO applyRefund(RefundRequest request) {
        return distributedLock.executeWithLock(LOCK_PREFIX + request.getPayNo(),
                () -> doApplyRefund(request));
    }

    private RefundOrderVO doApplyRefund(RefundRequest request) {
        RefundOrder existing = getByBizRefundNo(request.getBizRefundNo());
        if (existing != null) {
            log.info("[Refund] refund order already exists, returning it idempotently bizRefundNo={}",
                    request.getBizRefundNo());
            return RefundOrderVO.from(existing);
        }

        PayOrder payOrder = payOrderRepository.getByPayNo(request.getPayNo());
        if (payOrder == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND, request.getPayNo());
        }
        if (!payOrder.getStatus().isPaid()) {
            throw BizException.of(ErrorCode.REFUND_NOT_ALLOWED,
                    request.getPayNo() + " is currently " + payOrder.getStatus());
        }

        BigDecimal amount = MoneyUtil.scale(request.getAmount());
        BigDecimal refundable = payOrder.refundableAmount();
        if (MoneyUtil.gt(amount, refundable)) {
            throw BizException.of(ErrorCode.REFUND_AMOUNT_EXCEED,
                    "refundable " + refundable + ", requested " + amount);
        }

        RefundOrder refundOrder = buildRefundOrder(request, payOrder, amount);
        try {
            refundOrderMapper.insert(refundOrder);
        } catch (DuplicateKeyException e) {
            log.warn("[Refund] unique index conflict, returning the existing refund order bizRefundNo={}",
                    request.getBizRefundNo());
            return RefundOrderVO.from(getByBizRefundNo(request.getBizRefundNo()));
        }

        PayChannelConfig config = payChannelConfigService.getEnabled(payOrder.getChannelType());
        ChannelRefundResponse response;
        try {
            response = channelAdapterFactory.get(payOrder.getChannelType()).refund(
                    ChannelRefundRequest.builder()
                            .refundNo(refundOrder.getRefundNo())
                            .payNo(payOrder.getPayNo())
                            .channelTradeNo(payOrder.getChannelTradeNo())
                            .userId(payOrder.getUserId())
                            .refundAmount(amount)
                            .totalAmount(payOrder.getPaidAmount())
                            .reason(request.getReason())
                            .notifyUrl(payProperties.getMock().getSelfBaseUrl()
                                    + "/api/webhooks/" + payOrder.getChannelType().name().toLowerCase() + "/refund")
                            .build(), config);
        } catch (Exception e) {
            markRefundFail(refundOrder, "channel refund call threw: " + e.getMessage());
            throw BizException.of(ErrorCode.REFUND_CHANNEL_CALL_FAIL, e.getMessage());
        }

        if (!response.isAccepted()) {
            markRefundFail(refundOrder, response.getErrorMsg());
            throw BizException.of(ErrorCode.REFUND_CHANNEL_CALL_FAIL, response.getErrorMsg());
        }

        refundOrder.setChannelRefundNo(response.getChannelRefundNo());
        updateChannelRefundNo(refundOrder);

        if (response.isSyncRefunded()) {
            // Balance refunds land synchronously
            finishRefund(refundOrder.getRefundNo(), response.getChannelRefundNo());
            return RefundOrderVO.from(getByRefundNo(refundOrder.getRefundNo()));
        }

        log.info("[Refund] accepted, awaiting the channel's async webhook refundNo={} payNo={} amount={}",
                refundOrder.getRefundNo(), payOrder.getPayNo(), amount);
        return RefundOrderVO.from(refundOrder);
    }

    /**
     * Shared handling for a channel refund webhook and a synchronous refund success: mark the
     * refund order successful and write the refunded amount back to the pay order.
     *
     * @return true when this call actually completed the refund
     */
    public boolean handleRefundSuccess(String refundNo, String channelRefundNo) {
        RefundOrder refundOrder = getByRefundNo(refundNo);
        if (refundOrder == null) {
            throw BizException.of(ErrorCode.REFUND_ORDER_NOT_FOUND, refundNo);
        }
        return distributedLock.executeWithLock(LOCK_PREFIX + refundOrder.getPayNo(),
                () -> finishRefund(refundNo, channelRefundNo));
    }

    /** Channel refund-failure webhook. */
    public boolean handleRefundFail(String refundNo, String reason) {
        RefundOrder refundOrder = getByRefundNo(refundNo);
        if (refundOrder == null) {
            throw BizException.of(ErrorCode.REFUND_ORDER_NOT_FOUND, refundNo);
        }
        if (refundOrder.getStatus() != RefundStatus.PROCESSING) {
            return false;
        }
        markRefundFail(refundOrder, reason);
        return true;
    }

    private boolean finishRefund(String refundNo, String channelRefundNo) {
        RefundOrder refundOrder = getByRefundNo(refundNo);
        if (refundOrder == null) {
            throw BizException.of(ErrorCode.REFUND_ORDER_NOT_FOUND, refundNo);
        }
        if (refundOrder.getStatus() == RefundStatus.SUCCESS) {
            log.info("[Refund] refund order already succeeded, ignoring idempotently refundNo={}", refundNo);
            return false;
        }
        // CAS: only PROCESSING may become SUCCESS, which blocks duplicate webhooks
        int rows = refundOrderMapper.update(null, Wrappers.<RefundOrder>lambdaUpdate()
                .set(RefundOrder::getStatus, RefundStatus.SUCCESS)
                .set(RefundOrder::getChannelRefundNo, channelRefundNo)
                .set(RefundOrder::getRefundTime, LocalDateTime.now())
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(RefundOrder::getRefundNo, refundNo)
                .eq(RefundOrder::getStatus, RefundStatus.PROCESSING));
        if (rows == 0) {
            log.info("[Refund] CAS missed, a concurrent request already handled it refundNo={}", refundNo);
            return false;
        }
        payOrderStatusService.applyRefundSuccess(
                refundOrder.getPayNo(), refundNo, refundOrder.getAmount());
        log.info("[Refund] refund succeeded refundNo={} payNo={} amount={}",
                refundNo, refundOrder.getPayNo(), refundOrder.getAmount());
        return true;
    }

    private void markRefundFail(RefundOrder refundOrder, String reason) {
        refundOrderMapper.update(null, Wrappers.<RefundOrder>lambdaUpdate()
                .set(RefundOrder::getStatus, RefundStatus.FAIL)
                .set(RefundOrder::getFailReason, reason)
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(RefundOrder::getRefundNo, refundOrder.getRefundNo())
                .eq(RefundOrder::getStatus, RefundStatus.PROCESSING));
        log.warn("[Refund] refund failed refundNo={} reason={}", refundOrder.getRefundNo(), reason);
    }

    private void updateChannelRefundNo(RefundOrder refundOrder) {
        refundOrderMapper.update(null, Wrappers.<RefundOrder>lambdaUpdate()
                .set(RefundOrder::getChannelRefundNo, refundOrder.getChannelRefundNo())
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .eq(RefundOrder::getRefundNo, refundOrder.getRefundNo()));
    }

    private RefundOrder buildRefundOrder(RefundRequest request, PayOrder payOrder, BigDecimal amount) {
        RefundOrder refundOrder = new RefundOrder();
        refundOrder.setRefundNo(IdGenerator.refundNo());
        refundOrder.setPayNo(payOrder.getPayNo());
        refundOrder.setBizRefundNo(request.getBizRefundNo());
        refundOrder.setChannelType(payOrder.getChannelType());
        refundOrder.setAmount(amount);
        refundOrder.setStatus(RefundStatus.PROCESSING);
        refundOrder.setReason(request.getReason());
        refundOrder.setVersion(0);
        return refundOrder;
    }

    public RefundOrder getByRefundNo(String refundNo) {
        return refundOrderMapper.selectOne(Wrappers.<RefundOrder>lambdaQuery()
                .eq(RefundOrder::getRefundNo, refundNo));
    }

    public RefundOrder getByBizRefundNo(String bizRefundNo) {
        return refundOrderMapper.selectOne(Wrappers.<RefundOrder>lambdaQuery()
                .eq(RefundOrder::getBizRefundNo, bizRefundNo));
    }

    public RefundOrderVO queryByRefundNo(String refundNo) {
        RefundOrder order = getByRefundNo(refundNo);
        if (order == null) {
            throw BizException.of(ErrorCode.REFUND_ORDER_NOT_FOUND, refundNo);
        }
        return RefundOrderVO.from(order);
    }

    public List<RefundOrderVO> listByPayNo(String payNo) {
        return refundOrderMapper.selectList(Wrappers.<RefundOrder>lambdaQuery()
                        .eq(RefundOrder::getPayNo, payNo)
                        .orderByDesc(RefundOrder::getId))
                .stream().map(RefundOrderVO::from).toList();
    }
}
