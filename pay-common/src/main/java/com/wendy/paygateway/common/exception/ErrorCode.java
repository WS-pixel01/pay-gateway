package com.wendy.paygateway.common.exception;

import lombok.Getter;

/** Business error codes. 1xxx general / 2xxx pay / 3xxx refund / 4xxx webhook / 5xxx channel / 6xxx account / 7xxx reconciliation. */
@Getter
public enum ErrorCode {

    PARAM_INVALID(1001, "Parameter validation failed"),
    UNAUTHORIZED(1002, "Not authenticated or token expired"),
    REPEAT_SUBMIT(1003, "Duplicate request"),
    PROCESSING(1004, "Request is being processed, please retry later"),
    RATE_LIMITED(1005, "Too many requests, please slow down"),
    LOCK_FAILED(1006, "Failed to acquire distributed lock, please retry later"),
    SYSTEM_ERROR(1500, "System busy, please retry later"),

    PAY_ORDER_NOT_FOUND(2001, "Pay order not found"),
    PAY_ORDER_EXPIRED(2002, "Pay order has expired"),
    PAY_ORDER_STATUS_ILLEGAL(2003, "Pay order status does not allow this operation"),
    PAY_AMOUNT_ILLEGAL(2004, "Illegal payment amount"),
    PAY_AMOUNT_MISMATCH(2005, "Payment amount does not match the pay order"),
    PAY_CHANNEL_CALL_FAIL(2006, "Channel place-order call failed"),

    REFUND_ORDER_NOT_FOUND(3001, "Refund order not found"),
    REFUND_AMOUNT_EXCEED(3002, "Refund amount exceeds the refundable balance"),
    REFUND_NOT_ALLOWED(3003, "Current pay order status does not allow refunds"),
    REFUND_REPEAT(3004, "Duplicate refund request"),
    REFUND_CHANNEL_CALL_FAIL(3005, "Channel refund call failed"),

    WEBHOOK_SIGN_INVALID(4001, "Webhook signature verification failed"),
    WEBHOOK_PARAM_INVALID(4002, "Failed to parse webhook payload"),
    WEBHOOK_ORDER_NOT_FOUND(4003, "No pay order matches this webhook"),

    CHANNEL_NOT_FOUND(5001, "Payment channel not found or disabled"),
    CHANNEL_NOT_MATCH(5002, "No available payment channel matches"),
    CHANNEL_SIGN_ERROR(5003, "Channel signature computation error"),

    ACCOUNT_NOT_FOUND(6001, "Account not found"),
    BALANCE_NOT_ENOUGH(6002, "Insufficient account balance"),
    ACCOUNT_CONCURRENT_UPDATE(6003, "Concurrent account update conflict"),

    RECONCILE_BILL_NOT_FOUND(7001, "Channel bill file not found"),
    RECONCILE_FAIL(7002, "Reconciliation failed");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
