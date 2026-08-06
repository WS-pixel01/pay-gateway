package com.wendy.paygateway.pay;

import com.wendy.paygateway.account.service.AccountService;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.enums.RefundStatus;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.pay.dto.CreatePayRequest;
import com.wendy.paygateway.pay.dto.CreatePayResponse;
import com.wendy.paygateway.pay.dto.PayOrderVO;
import com.wendy.paygateway.pay.service.PayOrderService;
import com.wendy.paygateway.refund.dto.RefundOrderVO;
import com.wendy.paygateway.refund.dto.RefundRequest;
import com.wendy.paygateway.refund.service.RefundOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Balance payments run entirely synchronously with no async webhook, which makes them ideal for
 * driving the whole chain — checkout, idempotency, partial refund, full refund — in one integration
 * test.
 */
@SpringBootTest
class BalancePayFlowTest {

    private static final String USER_ID = "U10001";

    @Autowired
    private PayOrderService payOrderService;
    @Autowired
    private RefundOrderService refundOrderService;
    @Autowired
    private AccountService accountService;

    @Test
    @DisplayName("Balance payment debits on checkout, and a duplicate checkout never debits twice")
    void payIsIdempotent() {
        BigDecimal before = accountService.getBalance(USER_ID);
        CreatePayRequest request = buildRequest(new BigDecimal("100.00"));

        CreatePayResponse first = payOrderService.createPay(request);
        assertEquals(PayOrderStatus.SUCCESS, first.getStatus());
        assertTrue(first.isSyncPaid());
        assertEquals(MoneyUtil.subtract(before, new BigDecimal("100.00")), accountService.getBalance(USER_ID));

        // Same bizOrderNo again: the first result is replayed and no second debit happens
        CreatePayResponse second = payOrderService.createPay(request);
        assertEquals(first.getPayNo(), second.getPayNo());
        assertEquals(MoneyUtil.subtract(before, new BigDecimal("100.00")), accountService.getBalance(USER_ID));
    }

    @Test
    @DisplayName("A partial refund moves the order to PART_REFUNDED, refunding the rest to REFUNDED, and over-refunds are rejected")
    void partialThenFullRefund() {
        BigDecimal before = accountService.getBalance(USER_ID);
        CreatePayResponse pay = payOrderService.createPay(buildRequest(new BigDecimal("200.00")));
        assertEquals(PayOrderStatus.SUCCESS, pay.getStatus());

        RefundOrderVO first = refundOrderService.applyRefund(
                buildRefund(pay.getPayNo(), new BigDecimal("50.00")));
        assertEquals(RefundStatus.SUCCESS, first.getStatus());
        assertNotNull(first.getRefundNo());

        PayOrderVO afterPartial = payOrderService.queryByPayNo(pay.getPayNo());
        assertEquals(PayOrderStatus.PART_REFUNDED, afterPartial.getStatus());
        assertEquals(new BigDecimal("150.00"), afterPartial.getRefundableAmount());
        assertEquals(MoneyUtil.subtract(before, new BigDecimal("150.00")), accountService.getBalance(USER_ID));

        // Anything beyond the refundable balance is rejected outright
        assertThrows(BizException.class, () -> refundOrderService.applyRefund(
                buildRefund(pay.getPayNo(), new BigDecimal("150.01"))));

        refundOrderService.applyRefund(buildRefund(pay.getPayNo(), new BigDecimal("150.00")));
        PayOrderVO afterFull = payOrderService.queryByPayNo(pay.getPayNo());
        assertEquals(PayOrderStatus.REFUNDED, afterFull.getStatus());
        assertEquals(before, accountService.getBalance(USER_ID));
    }

    @Test
    @DisplayName("Insufficient balance fails the checkout and leaves no successful pay order")
    void balanceNotEnough() {
        CreatePayRequest request = buildRequest(new BigDecimal("100.00"));
        request.setUserId("U10003");
        assertThrows(BizException.class, () -> payOrderService.createPay(request));
    }

    private CreatePayRequest buildRequest(BigDecimal amount) {
        CreatePayRequest request = new CreatePayRequest();
        request.setBizSystem("mall");
        request.setBizOrderNo("IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        request.setUserId(USER_ID);
        request.setAmount(amount);
        request.setSubject("integration test item");
        request.setChannelType(ChannelType.BALANCE);
        request.setRegion("CN");
        return request;
    }

    private RefundRequest buildRefund(String payNo, BigDecimal amount) {
        RefundRequest request = new RefundRequest();
        request.setPayNo(payNo);
        request.setBizRefundNo("ITR" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        request.setAmount(amount);
        request.setReason("integration test refund");
        return request;
    }
}
