package com.wendy.paygateway.pay.service;

import com.wendy.paygateway.channel.adapter.ChannelAdapterFactory;
import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelPayResponse;
import com.wendy.paygateway.channel.dto.ChannelQueryResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.route.ChannelRouter;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.idempotent.PayIdempotent;
import com.wendy.paygateway.common.ratelimit.RateLimit;
import com.wendy.paygateway.common.util.IdGenerator;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.common.util.WebUtils;
import com.wendy.paygateway.pay.dto.CreatePayRequest;
import com.wendy.paygateway.pay.dto.CreatePayResponse;
import com.wendy.paygateway.pay.dto.PayOrderVO;
import com.wendy.paygateway.pay.entity.PayOrder;
import com.wendy.paygateway.pay.repository.PayOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Unified checkout service.
 *
 * <p>Duplicate orders are blocked at three layers:
 * <ol>
 *   <li>{@link RateLimit}, a sliding window that holds back abusive traffic;</li>
 *   <li>{@link PayIdempotent}, distributed idempotency keyed on business system + business order
 *       number, replaying the first result for duplicates;</li>
 *   <li>the unique index on pay_order (biz_system, biz_order_no), the database's final backstop.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderService {

    private final PayOrderRepository payOrderRepository;
    private final PayOrderStatusService payOrderStatusService;
    private final ChannelRouter channelRouter;
    private final ChannelAdapterFactory channelAdapterFactory;
    private final PayChannelConfigService payChannelConfigService;
    private final PayProperties payProperties;

    /** Create a pay order and place the order with the channel. */
    @RateLimit(name = "createPay", limit = 20, windowSeconds = 1,
            dimension = RateLimit.Dimension.SPEL, key = "#request.userId")
    @PayIdempotent(prefix = "createPay", key = "#request.bizSystem + ':' + #request.bizOrderNo", ttlSeconds = 300)
    public CreatePayResponse createPay(CreatePayRequest request) {
        if (!MoneyUtil.isPositive(request.getAmount())) {
            throw BizException.of(ErrorCode.PAY_AMOUNT_ILLEGAL, String.valueOf(request.getAmount()));
        }

        PayOrder existing = payOrderRepository.getByBizOrderNo(request.getBizSystem(), request.getBizOrderNo());
        if (existing != null) {
            return reuseOrRejectExisting(existing);
        }

        PayChannelConfig config = channelRouter.route(
                request.getChannelType(), MoneyUtil.scale(request.getAmount()), request.getRegion());

        PayOrder order = buildOrder(request, config);
        try {
            payOrderRepository.insert(order);
        } catch (DuplicateKeyException e) {
            // A concurrent duplicate outside the idempotency window; the unique index catches it
            log.warn("[Create pay order] unique index conflict, returning the existing order bizOrderNo={}",
                    request.getBizOrderNo());
            return reuseOrRejectExisting(
                    payOrderRepository.getByBizOrderNo(request.getBizSystem(), request.getBizOrderNo()));
        }

        return callChannel(order, config);
    }

    private CreatePayResponse callChannel(PayOrder order, PayChannelConfig config) {
        ChannelPayRequest channelRequest = ChannelPayRequest.builder()
                .payNo(order.getPayNo())
                .userId(order.getUserId())
                .amount(order.getAmount())
                .subject(order.getSubject())
                .body(order.getBody())
                .clientIp(order.getClientIp())
                .expireTime(order.getExpireTime())
                .notifyUrl(payProperties.getMock().getSelfBaseUrl() + config.getNotifyUrl())
                .build();

        ChannelPayResponse channelResponse;
        try {
            channelResponse = channelAdapterFactory.get(config.getChannelType()).pay(channelRequest, config);
        } catch (Exception e) {
            log.error("[Create pay order] channel call threw payNo={} channel={}",
                    order.getPayNo(), config.getChannelType(), e);
            payOrderStatusService.markPayFail(order.getPayNo(), "channel call threw: " + e.getMessage());
            throw BizException.of(ErrorCode.PAY_CHANNEL_CALL_FAIL, e.getMessage());
        }

        if (!channelResponse.isAccepted()) {
            payOrderStatusService.markPayFail(order.getPayNo(), channelResponse.getErrorMsg());
            throw BizException.of(ErrorCode.PAY_CHANNEL_CALL_FAIL, channelResponse.getErrorMsg());
        }

        payOrderRepository.fillChannelInfo(order.getPayNo(),
                channelResponse.getChannelTradeNo(), channelResponse.getPayUrl());

        PayOrderStatus status = order.getStatus();
        if (channelResponse.isSyncPaid()) {
            // Balance payment: the channel answered success synchronously, so drive the state
            // machine right away instead of waiting for an async webhook
            payOrderStatusService.markPaySuccess(order.getPayNo(),
                    channelResponse.getChannelTradeNo(), order.getAmount());
            status = PayOrderStatus.SUCCESS;
        }

        log.info("[Create pay order] payNo={} bizOrderNo={} channel={} amount={} status={}",
                order.getPayNo(), order.getBizOrderNo(), config.getChannelType(), order.getAmount(), status);

        return CreatePayResponse.builder()
                .payNo(order.getPayNo())
                .bizOrderNo(order.getBizOrderNo())
                .channelType(config.getChannelType())
                .amount(order.getAmount())
                .status(status)
                .payUrl(channelResponse.getPayUrl())
                .expireTime(order.getExpireTime())
                .syncPaid(channelResponse.isSyncPaid())
                .build();
    }

    /**
     * Handling for an order that already exists: reuse it when it is still awaiting payment and not
     * expired, otherwise reject the duplicate checkout.
     */
    private CreatePayResponse reuseOrRejectExisting(PayOrder existing) {
        if (existing == null) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "concurrent pay order creation anomaly");
        }
        if (existing.getStatus() == PayOrderStatus.WAIT_PAY && !existing.expired()) {
            log.info("[Create pay order] reusing the existing unpaid order payNo={}", existing.getPayNo());
            return CreatePayResponse.builder()
                    .payNo(existing.getPayNo())
                    .bizOrderNo(existing.getBizOrderNo())
                    .channelType(existing.getChannelType())
                    .amount(existing.getAmount())
                    .status(existing.getStatus())
                    .payUrl(existing.getPayUrl())
                    .expireTime(existing.getExpireTime())
                    .syncPaid(false)
                    .build();
        }
        throw BizException.of(ErrorCode.PAY_ORDER_STATUS_ILLEGAL,
                "business order " + existing.getBizOrderNo() + " already has pay order "
                        + existing.getPayNo() + ", currently " + existing.getStatus());
    }

    private PayOrder buildOrder(CreatePayRequest request, PayChannelConfig config) {
        int expireMinutes = request.getExpireMinutes() != null && request.getExpireMinutes() > 0
                ? request.getExpireMinutes() : payProperties.getOrder().getExpireMinutes();
        PayOrder order = new PayOrder();
        order.setPayNo(IdGenerator.payNo());
        order.setBizSystem(request.getBizSystem());
        order.setBizOrderNo(request.getBizOrderNo());
        order.setUserId(request.getUserId());
        order.setChannelType(config.getChannelType());
        order.setSubject(request.getSubject());
        order.setBody(request.getBody());
        order.setAmount(MoneyUtil.scale(request.getAmount()));
        order.setPaidAmount(MoneyUtil.ZERO);
        order.setRefundedAmount(MoneyUtil.ZERO);
        order.setStatus(PayOrderStatus.WAIT_PAY);
        order.setRegion(request.getRegion() == null ? "CN" : request.getRegion());
        order.setClientIp(WebUtils.currentClientIp());
        order.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        order.setVersion(0);
        return order;
    }

    // ------------------------------------------------------------------ queries

    public PayOrderVO queryByPayNo(String payNo) {
        return PayOrderVO.from(mustGet(payNo));
    }

    public PayOrderVO queryByBizOrderNo(String bizSystem, String bizOrderNo) {
        PayOrder order = payOrderRepository.getByBizOrderNo(bizSystem, bizOrderNo);
        if (order == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND, bizSystem + ":" + bizOrderNo);
        }
        return PayOrderVO.from(order);
    }

    public List<PayOrderVO> listRecent(int limit) {
        return payOrderRepository.listAll(limit).stream().map(PayOrderVO::from).toList();
    }

    /**
     * Compensating query: when a webhook is lost, correct the local pay order from the channel's
     * own status.
     *
     * <p>In production a scheduled task runs this in batches over orders that have been awaiting
     * payment for more than N minutes.
     */
    public PayOrderVO syncFromChannel(String payNo) {
        PayOrder order = mustGet(payNo);
        PayChannelConfig config = payChannelConfigService.getEnabled(order.getChannelType());
        ChannelQueryResponse response = channelAdapterFactory.get(order.getChannelType()).query(payNo, config);
        log.info("[Channel query] payNo={} channelStatus={}", payNo, response.getTradeStatus());
        if ("SUCCESS".equals(response.getTradeStatus())) {
            payOrderStatusService.markPaySuccess(payNo, response.getChannelTradeNo(), response.getAmount());
        } else if ("FAIL".equals(response.getTradeStatus())) {
            payOrderStatusService.markPayFail(payNo, "channel query reported failure");
        }
        return PayOrderVO.from(mustGet(payNo));
    }

    /** Close a pay order (cancelled by the upstream system). */
    public boolean close(String payNo, String reason) {
        return payOrderStatusService.closeOrder(payNo, reason);
    }

    private PayOrder mustGet(String payNo) {
        PayOrder order = payOrderRepository.getByPayNo(payNo);
        if (order == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND, payNo);
        }
        return order;
    }
}
