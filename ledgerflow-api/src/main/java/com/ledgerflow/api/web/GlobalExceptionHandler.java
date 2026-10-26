package com.ledgerflow.api.web;

import com.ledgerflow.api.idempotency.IdempotencyKeyInUseException;
import com.ledgerflow.api.idempotency.IdempotencyKeyReusedException;
import com.ledgerflow.api.ledger.InsufficientFundsException;
import com.ledgerflow.api.merchant.MerchantNotFoundException;
import com.ledgerflow.api.payment.CustomerAccountNotFoundException;
import com.ledgerflow.api.payment.IllegalStateTransitionException;
import com.ledgerflow.api.payment.PaymentNotFoundException;
import com.ledgerflow.api.payment.RefundExceedsCapturedException;
import com.ledgerflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 7807 problem responses with a stable {@code code} property.
 * Domain classes throw plain exceptions and know nothing about HTTP; the mapping lives here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail paymentNotFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail merchantNotFound(MerchantNotFoundException e) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.MERCHANT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidMerchantTokenException.class)
    ProblemDetail invalidToken(InvalidMerchantTokenException e) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.MERCHANT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CustomerAccountNotFoundException.class)
    ProblemDetail customerNotFound(CustomerAccountNotFoundException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.CUSTOMER_ACCOUNT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail illegalTransition(IllegalStateTransitionException e) {
        return problem(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION, e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INSUFFICIENT_FUNDS, "insufficient funds");
    }

    @ExceptionHandler(RefundExceedsCapturedException.class)
    ProblemDetail refundTooLarge(RefundExceedsCapturedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.REFUND_EXCEEDS_CAPTURED, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInUseException.class)
    ProblemDetail keyInUse(IdempotencyKeyInUseException e) {
        return problem(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_IN_USE, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "internal error");
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && PaymentController.IDEMPOTENCY_KEY.equals(missing.getHeaderName())) {
            return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "the Idempotency-Key header is required on every POST"));
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
