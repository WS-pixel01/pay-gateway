package com.wendy.paygateway.channel.adapter;

import com.wendy.paygateway.account.service.AccountService;
import com.wendy.paygateway.channel.dto.ChannelPayRequest;
import com.wendy.paygateway.channel.dto.ChannelPayResponse;
import com.wendy.paygateway.channel.dto.ChannelQueryResponse;
import com.wendy.paygateway.channel.dto.ChannelRefundRequest;
import com.wendy.paygateway.channel.dto.ChannelRefundResponse;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Balance payment adapter: an internal channel that answers synchronously and has no async webhook.
 *
 * <p>Dressing the internal funds account up as just another payment channel means the checkout and
 * refund code above needs no special-case branch for balance payments at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceChannelAdapter implements PayChannelAdapter {

    private final AccountService accountService;

    @Override
    public ChannelType channelType() {
        return ChannelType.BALANCE;
    }

    @Override
    public ChannelPayResponse pay(ChannelPayRequest request, PayChannelConfig config) {
        try {
            accountService.deduct(request.getUserId(), request.getAmount(), request.getPayNo(),
                    "Balance payment for " + request.getSubject());
        } catch (BizException e) {
            log.warn("[Balance pay] debit failed payNo={} : {}", request.getPayNo(), e.getMessage());
            return ChannelPayResponse.fail(String.valueOf(e.getCode()), e.getMessage());
        }
        return ChannelPayResponse.builder()
                .accepted(true)
                .channelTradeNo(IdGenerator.channelTradeNo("BA"))
                .payUrl(null)
                .syncPaid(true)
                .build();
    }

    @Override
    public ChannelRefundResponse refund(ChannelRefundRequest request, PayChannelConfig config) {
        try {
            accountService.credit(request.getUserId(), request.getRefundAmount(), request.getRefundNo(),
                    "Balance refund for " + request.getPayNo());
        } catch (BizException e) {
            return ChannelRefundResponse.fail(String.valueOf(e.getCode()), e.getMessage());
        }
        return ChannelRefundResponse.builder()
                .accepted(true)
                .channelRefundNo(IdGenerator.channelTradeNo("BAR"))
                .syncRefunded(true)
                .build();
    }

    @Override
    public ChannelQueryResponse query(String payNo, PayChannelConfig config) {
        // For an internal channel the local pay order is the source of truth; querying is left to the caller
        return ChannelQueryResponse.builder().success(true).tradeStatus("LOCAL").build();
    }

    @Override
    public boolean verifyWebhook(Map<String, String> params, PayChannelConfig config) {
        return true;
    }

    @Override
    public String downloadBill(LocalDate billDate, PayChannelConfig config) {
        // Internal money movements are recorded straight into account_flow; there is no external
        // bill, so there is nothing to reconcile
        return null;
    }
}
