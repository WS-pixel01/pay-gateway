package com.wendy.paygateway.pay.statemachine;

import com.wendy.paygateway.common.enums.PayOrderStatus;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pay order state machine.
 *
 * <pre>
 *   WAIT_PAY ──paid──▶ SUCCESS ──partial refund──▶ PART_REFUNDED ──fully refunded──▶ REFUNDED
 *      │                  └──────full refund──────────────────────────────────────▶ REFUNDED
 *      ├──payment failed──▶ FAIL
 *      └──expired / closed by caller──▶ CLOSED
 * </pre>
 *
 * <p>Declaring the legal transitions in one place means every status change has to pass this
 * check, which structurally rules out production money incidents such as "a late webhook flips a
 * closed order back to paid".
 */
@Slf4j
@Component
public class PayOrderStateMachine {

    private static final Map<PayOrderStatus, Set<PayOrderStatus>> TRANSITIONS =
            new EnumMap<>(PayOrderStatus.class);

    static {
        TRANSITIONS.put(PayOrderStatus.WAIT_PAY,
                EnumSet.of(PayOrderStatus.SUCCESS, PayOrderStatus.FAIL, PayOrderStatus.CLOSED));
        TRANSITIONS.put(PayOrderStatus.SUCCESS,
                EnumSet.of(PayOrderStatus.PART_REFUNDED, PayOrderStatus.REFUNDED));
        TRANSITIONS.put(PayOrderStatus.PART_REFUNDED,
                EnumSet.of(PayOrderStatus.PART_REFUNDED, PayOrderStatus.REFUNDED));
        TRANSITIONS.put(PayOrderStatus.FAIL, EnumSet.of(PayOrderStatus.CLOSED));
        TRANSITIONS.put(PayOrderStatus.REFUNDED, EnumSet.noneOf(PayOrderStatus.class));
        TRANSITIONS.put(PayOrderStatus.CLOSED, EnumSet.noneOf(PayOrderStatus.class));
    }

    public boolean canTransfer(PayOrderStatus from, PayOrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PayOrderStatus.class)).contains(to);
    }

    /** Throw immediately on an illegal transition. */
    public void assertCanTransfer(String payNo, PayOrderStatus from, PayOrderStatus to) {
        if (!canTransfer(from, to)) {
            log.warn("[State machine] illegal transition payNo={} {} -> {}", payNo, from, to);
            throw BizException.of(ErrorCode.PAY_ORDER_STATUS_ILLEGAL, payNo + " " + from + " -> " + to);
        }
    }
}
