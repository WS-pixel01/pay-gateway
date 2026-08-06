package com.wendy.paygateway.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.account.entity.AccountFlow;
import com.wendy.paygateway.account.entity.UserAccount;
import com.wendy.paygateway.account.mapper.AccountFlowMapper;
import com.wendy.paygateway.account.mapper.UserAccountMapper;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Transactional account operations.
 *
 * <p>These live in their own bean rather than inside {@link AccountService} because
 * {@code @Transactional} works through a Spring proxy: a self-invocation inside the same class
 * would never start a transaction. Splitting them makes the call chain "acquire the distributed
 * lock, then enter the transaction", so the lock strictly encloses the transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTxService {

    private static final int MAX_RETRY = 3;

    private final UserAccountMapper userAccountMapper;
    private final AccountFlowMapper accountFlowMapper;

    @Transactional(rollbackFor = Exception.class)
    public void deduct(String userId, BigDecimal amount, String bizNo, String remark) {
        if (flowExists(bizNo, AccountFlow.OUT)) {
            log.info("[Balance pay] ledger entry already exists, skipping idempotently bizNo={}", bizNo);
            return;
        }
        for (int i = 0; i < MAX_RETRY; i++) {
            UserAccount account = loadAccount(userId);
            if (MoneyUtil.lt(account.getBalance(), amount)) {
                throw BizException.of(ErrorCode.BALANCE_NOT_ENOUGH,
                        "balance " + account.getBalance() + ", required " + amount);
            }
            BigDecimal after = MoneyUtil.subtract(account.getBalance(), amount);
            if (updateBalance(account, after)) {
                writeFlow(userId, bizNo, AccountFlow.OUT, amount, after, remark);
                log.info("[Balance pay] debited userId={} amount={} balance={} bizNo={}", userId, amount, after, bizNo);
                return;
            }
            log.warn("[Balance pay] optimistic lock conflict, retry {} userId={}", i + 1, userId);
        }
        throw BizException.of(ErrorCode.ACCOUNT_CONCURRENT_UPDATE, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void credit(String userId, BigDecimal amount, String bizNo, String remark) {
        if (flowExists(bizNo, AccountFlow.IN)) {
            log.info("[Balance refund] ledger entry already exists, skipping idempotently bizNo={}", bizNo);
            return;
        }
        for (int i = 0; i < MAX_RETRY; i++) {
            UserAccount account = loadAccount(userId);
            BigDecimal after = MoneyUtil.add(account.getBalance(), amount);
            if (updateBalance(account, after)) {
                writeFlow(userId, bizNo, AccountFlow.IN, amount, after, remark);
                log.info("[Balance refund] credited userId={} amount={} balance={} bizNo={}", userId, amount, after, bizNo);
                return;
            }
            log.warn("[Balance refund] optimistic lock conflict, retry {} userId={}", i + 1, userId);
        }
        throw BizException.of(ErrorCode.ACCOUNT_CONCURRENT_UPDATE, userId);
    }

    public UserAccount loadAccount(String userId) {
        UserAccount account = userAccountMapper.selectOne(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUserId, userId));
        if (account == null) {
            throw BizException.of(ErrorCode.ACCOUNT_NOT_FOUND, userId);
        }
        return account;
    }

    /** Optimistic-locked update; false means someone else already bumped the version. */
    private boolean updateBalance(UserAccount account, BigDecimal newBalance) {
        UserAccount update = new UserAccount();
        update.setId(account.getId());
        update.setBalance(newBalance);
        update.setVersion(account.getVersion());
        return userAccountMapper.updateById(update) > 0;
    }

    private boolean flowExists(String bizNo, String direction) {
        return accountFlowMapper.selectCount(Wrappers.<AccountFlow>lambdaQuery()
                .eq(AccountFlow::getBizNo, bizNo)
                .eq(AccountFlow::getDirection, direction)) > 0;
    }

    private void writeFlow(String userId, String bizNo, String direction,
                           BigDecimal amount, BigDecimal balanceAfter, String remark) {
        AccountFlow flow = new AccountFlow();
        flow.setUserId(userId);
        flow.setBizNo(bizNo);
        flow.setDirection(direction);
        flow.setAmount(MoneyUtil.scale(amount));
        flow.setBalanceAfter(balanceAfter);
        flow.setRemark(remark);
        accountFlowMapper.insert(flow);
    }
}
