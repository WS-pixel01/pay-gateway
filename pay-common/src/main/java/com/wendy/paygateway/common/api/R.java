package com.wendy.paygateway.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.wendy.paygateway.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Unified API response envelope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    public static final int SUCCESS_CODE = 0;

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS_CODE, "success", data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }

    @JsonIgnore
    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}
