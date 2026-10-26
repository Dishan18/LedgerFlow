package com.ledgerflow.mockbank;

import com.ledgerflow.common.bank.BankApi;
import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.bank.CollectResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectController {

    private final CollectService collects;

    public CollectController(CollectService collects) {
        this.collects = collects;
    }

    @PostMapping(BankApi.COLLECTS)
    public CollectResponse collect(@RequestBody CollectRequest request) {
        return collects.collect(request);
    }

    @GetMapping(BankApi.COLLECT_BY_PAYMENT)
    public ResponseEntity<CollectResponse> status(@PathVariable UUID paymentId) {
        return ResponseEntity.of(collects.status(paymentId));
    }

    @ExceptionHandler(BankUnavailableException.class)
    ProblemDetail unavailable(BankUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(ConflictingCollectException.class)
    ProblemDetail conflict(ConflictingCollectException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
