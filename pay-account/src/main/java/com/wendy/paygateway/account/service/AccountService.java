package com.wendy.paygateway.account.service;

import com.wendy.paygateway.common.infra.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Public API of the balance account. Money safety rests on three lines of defence:
 * <ol>
 *   <li>a distributed lock keyed by userId serialises concurrent deductions for one user;</li>
 *   <li>MyBatis-Plus optimistic locking on {@code version} prevents lost updates even if the lock fails;</li>
 *   <li>the unique index on account_flow (biz_no, direction) stops double-posting at the database level.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String LOCK_PREFIX = "pay:lock:account:";

    private final AccountTxService accountTxService;
    private final DistributedLock distributedLock;

    public BigDecimal getBalance(String userId) {
        return accountTxService.loadAccount(userId).getBalance();
    }

    /** Debit. Throws {@code BALANCE_NOT_ENOUGH} on insufficient funds; repeat calls with the same bizNo are idempotent. */
    public void deduct(String userId, BigDecimal amount, String bizNo, String remark) {
        distributedLock.runWithLock(LOCK_PREFIX + userId,
                () -> accountTxService.deduct(userId, amount, bizNo, remark));
    }

    /** Credit funds back on refund. */
    public void credit(String userId, BigDecimal amount, String bizNo, String remark) {
        distributedLock.runWithLock(LOCK_PREFIX + userId,
                () -> accountTxService.credit(userId, amount, bizNo, remark));
    }
}
