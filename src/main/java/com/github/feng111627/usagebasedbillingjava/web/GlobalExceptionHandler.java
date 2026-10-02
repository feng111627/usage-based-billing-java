package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/**
 * 业务失败分类映射，保证调用方能按 HTTP 状态 + code 区分失败原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Set<ErrorCode> NOT_FOUND = Set.of(
            ErrorCode.NOT_FOUND, ErrorCode.PLAN_NOT_FOUND,
            ErrorCode.SUBSCRIPTION_NOT_FOUND, ErrorCode.INVOICE_NOT_FOUND);

    private static final Set<ErrorCode> CONFLICT = Set.of(
            ErrorCode.DUPLICATE_REQUEST, ErrorCode.CHANGE_OUT_OF_ORDER,
            ErrorCode.SUBSCRIPTION_INACTIVE, ErrorCode.SUBSCRIPTION_SUSPENDED,
            ErrorCode.SUBSCRIPTION_PAUSED,
            ErrorCode.CONFLICT, ErrorCode.NOTHING_TO_CLOSE,
            ErrorCode.INVOICE_ALREADY_PAID);

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handle(BillingException ex) {
        HttpStatus status;
        if (NOT_FOUND.contains(ex.getCode())) {
            status = HttpStatus.NOT_FOUND;
        } else if (CONFLICT.contains(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        } else if (ex.getCode() == ErrorCode.GATEWAY_REJECTED) {
            status = HttpStatus.PAYMENT_REQUIRED;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        log.warn("[api-error] code={} status={} message={}", ex.getCode(), status, ex.getMessage());
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.getCode().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnknown(Exception ex) {
        log.error("[api-error] 未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL", "message", String.valueOf(ex.getMessage())));
    }
}
