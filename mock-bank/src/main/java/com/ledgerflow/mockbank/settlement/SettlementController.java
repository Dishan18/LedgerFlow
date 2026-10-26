package com.ledgerflow.mockbank.settlement;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Generates a settlement file on demand (local runs and tests). In AWS the daily schedule does it. */
@RestController
public class SettlementController {

    private final SettlementGenerator generator;

    public SettlementController(SettlementGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/bank/v1/settlements/{date}")
    public SettlementGenerator.Result generate(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                               @RequestParam(defaultValue = "0") int drop,
                                               @RequestParam(defaultValue = "0") int duplicate,
                                               @RequestParam(defaultValue = "0") int wrongAmount,
                                               @RequestParam(defaultValue = "0") int phantom) {
        return generator.generate(date, new Discrepancies(drop, duplicate, wrongAmount, phantom));
    }
}
