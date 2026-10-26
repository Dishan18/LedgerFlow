package com.ledgerflow.api.web;

import com.ledgerflow.api.idempotency.IdempotencyService;
import com.ledgerflow.api.payment.Payment;
import com.ledgerflow.api.payment.PaymentService;
import com.ledgerflow.api.payment.Refund;
import com.ledgerflow.api.web.dto.CreatePaymentRequest;
import com.ledgerflow.api.web.dto.PaymentResponse;
import com.ledgerflow.api.web.dto.RefundRequest;
import com.ledgerflow.api.web.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments")
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    @Operation(summary = "Create a payment and authorize it with the bank")
    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Parameter(description = "Unique per logical request; retries reuse it")
                                    @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey,
                                    @Valid @RequestBody CreatePaymentRequest body,
                                    HttpServletRequest request) {
        UUID merchantId = merchantId(jwt);
        return idempotency.execute(merchantId, idempotencyKey, request.getMethod(), request.getRequestURI(), body, () -> {
            Payment payment = paymentService.createAndAuthorize(merchantId, body.customerId(), body.amount());
            return ResponseEntity.created(URI.create("/v1/payments/" + payment.getId()))
                    .body(PaymentResponse.from(payment));
        });
    }

    @Operation(summary = "Get a payment")
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.get(merchantId(jwt), paymentId));
    }

    @Operation(summary = "Capture the full authorized amount and post it to the ledger")
    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<?> capture(@AuthenticationPrincipal Jwt jwt,
                                     @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey,
                                     @PathVariable UUID paymentId,
                                     HttpServletRequest request) {
        UUID merchantId = merchantId(jwt);
        return idempotency.execute(merchantId, idempotencyKey, request.getMethod(), request.getRequestURI(), null,
                () -> ResponseEntity.ok(PaymentResponse.from(paymentService.capture(merchantId, paymentId))));
    }

    @Operation(summary = "Refund part or all of a captured payment")
    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<?> refund(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey,
                                    @PathVariable UUID paymentId,
                                    @Valid @RequestBody RefundRequest body,
                                    HttpServletRequest request) {
        UUID merchantId = merchantId(jwt);
        return idempotency.execute(merchantId, idempotencyKey, request.getMethod(), request.getRequestURI(), body, () -> {
            Refund refund = paymentService.refund(merchantId, paymentId, body.amount());
            return ResponseEntity.status(HttpStatus.CREATED).body(RefundResponse.from(refund));
        });
    }

    /** The JWT subject is the merchant id. A token with any other subject is rejected as 401. */
    private static UUID merchantId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidMerchantTokenException();
        }
    }
}
