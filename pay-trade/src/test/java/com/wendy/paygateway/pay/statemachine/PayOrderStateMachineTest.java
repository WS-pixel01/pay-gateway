package com.wendy.paygateway.pay.statemachine;

import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayOrderStateMachineTest {

    private final PayOrderStateMachine stateMachine = new PayOrderStateMachine();

    @Test
    @DisplayName("WAIT_PAY can move to SUCCESS, FAIL or CLOSED")
    void waitPayTransitions() {
        assertTrue(stateMachine.canTransfer(PayOrderStatus.WAIT_PAY, PayOrderStatus.SUCCESS));
        assertTrue(stateMachine.canTransfer(PayOrderStatus.WAIT_PAY, PayOrderStatus.FAIL));
        assertTrue(stateMachine.canTransfer(PayOrderStatus.WAIT_PAY, PayOrderStatus.CLOSED));
    }

    @Test
    @DisplayName("A closed order cannot be flipped to paid by a late success webhook")
    void closedCannotBecomeSuccess() {
        assertFalse(stateMachine.canTransfer(PayOrderStatus.CLOSED, PayOrderStatus.SUCCESS));
        assertThrows(BizException.class,
                () -> stateMachine.assertCanTransfer("P1", PayOrderStatus.CLOSED, PayOrderStatus.SUCCESS));
    }

    @Test
    @DisplayName("REFUNDED is a terminal state")
    void refundedIsFinal() {
        assertFalse(stateMachine.canTransfer(PayOrderStatus.REFUNDED, PayOrderStatus.PART_REFUNDED));
        assertFalse(stateMachine.canTransfer(PayOrderStatus.REFUNDED, PayOrderStatus.SUCCESS));
    }

    @Test
    @DisplayName("A partially refunded order can keep refunding until it is fully refunded")
    void partRefundedCanContinue() {
        assertTrue(stateMachine.canTransfer(PayOrderStatus.SUCCESS, PayOrderStatus.PART_REFUNDED));
        assertTrue(stateMachine.canTransfer(PayOrderStatus.PART_REFUNDED, PayOrderStatus.PART_REFUNDED));
        assertTrue(stateMachine.canTransfer(PayOrderStatus.PART_REFUNDED, PayOrderStatus.REFUNDED));
    }
}
