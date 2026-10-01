package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.web.dto.ErrorDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务异常 -> HTTP 响应；错误码透出，不同失败原因可区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<ErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCode.PLAN_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.SUBSCRIPTION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.BILL_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.SUBSCRIPTION_NOT_ACTIVE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SUBSCRIPTION_SUSPENDED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVALID_TIMELINE_EVENT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.BILL_ALREADY_PAID, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.BILL_NOT_PAYABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.BILL_ALREADY_EXISTS, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PERIOD_NOT_CLOSED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.LEDGER_NOT_BALANCED, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.RECONCILE_AMOUNT_MISMATCH, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.GATEWAY_ERROR, HttpStatus.BAD_GATEWAY),
            Map.entry(ErrorCode.USAGE_QTY_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.USAGE_METERING_MISSING, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST));

    private final BillingClock clock;

    public GlobalExceptionHandler(BillingClock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<ErrorDto> handleBilling(BillingException ex) {
        HttpStatus status = STATUS.getOrDefault(ex.getCode(), HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(status)
                .body(new ErrorDto(ex.getCode().name(), ex.getMessage(), clock.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorDto> handleIllegalArg(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorDto(ErrorCode.INVALID_ARGUMENT.name(), ex.getMessage(), clock.now()));
    }
}
