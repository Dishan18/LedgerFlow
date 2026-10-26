package com.ledgerflow.api.reconciliation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reconciliation")
@RestController
public class ReconciliationController {

    private final ReconciliationReportRepository reports;

    public ReconciliationController(ReconciliationReportRepository reports) {
        this.reports = reports;
    }

    @Operation(summary = "Latest reconciliation report for a business date (IST). Requires the ops scope.")
    @GetMapping(value = "/v1/reconciliation/{date}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> report(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.of(reports.latestJson(date));
    }
}
