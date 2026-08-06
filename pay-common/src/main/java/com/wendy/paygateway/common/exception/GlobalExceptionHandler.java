package com.wendy.paygateway.common.exception;

import com.wendy.paygateway.common.api.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** Global exception handling. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("[BizException] code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + defaultMessage(fe))
                .collect(Collectors.joining("; "));
        log.warn("[Validation failed] {}", detail);
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[Illegal argument] {}", e.getMessage());
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), e.getMessage());
    }

    /**
     * Unknown paths must stay a plain 404. Without this, Spring Boot 3.2's
     * NoResourceFoundException falls into the catch-all below and every typo URL
     * answers HTTP 200 "system busy" with an ERROR stack trace in the log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(HttpStatus.NOT_FOUND.value(), "Not found: /" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("[System error]", e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage();
    }
}
