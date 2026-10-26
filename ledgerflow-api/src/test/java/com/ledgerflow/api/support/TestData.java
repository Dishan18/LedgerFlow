package com.ledgerflow.api.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ledgerflow.api.config.LedgerflowProperties;
import com.ledgerflow.api.ledger.Account;
import com.ledgerflow.api.ledger.AccountRepository;
import com.ledgerflow.api.ledger.AccountType;
import com.ledgerflow.api.ledger.EntryType;
import com.ledgerflow.api.ledger.JournalEntryDraft;
import com.ledgerflow.api.ledger.LedgerService;
import com.ledgerflow.api.merchant.Merchant;
import com.ledgerflow.api.merchant.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates isolated merchants and funded customers through the real ledger code. */
@TestComponent
public class TestData {

    private final MerchantRepository merchants;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final LedgerflowProperties properties;

    public TestData(MerchantRepository merchants,
                    AccountRepository accounts,
                    LedgerService ledger,
                    TransactionTemplate tx,
                    LedgerflowProperties properties) {
        this.merchants = merchants;
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = tx;
        this.properties = properties;
    }

    public UUID merchant(int feeBps) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            merchants.save(new Merchant(id, "Test merchant " + id, feeBps));
            accounts.save(Account.open(AccountType.MERCHANT_RECEIVABLE, id.toString()));
        });
        return id;
    }

    /** Opens a customer account and funds it from the bank settlement account. */
    public String customer(long balance) {
        String customerId = "cust_" + UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Account customer = accounts.save(Account.open(AccountType.CUSTOMER, customerId));
            if (balance > 0) {
                UUID bank = accounts.findIdByTypeAndOwner(AccountType.BANK_SETTLEMENT, Account.SYSTEM_OWNER).orElseThrow();
                ledger.post(JournalEntryDraft.builder(EntryType.TRANSFER, "test funding")
                        .debit(bank, balance)
                        .credit(customer.getId(), balance)
                        .build());
            }
        });
        return customerId;
    }

    public long balance(AccountType type, String ownerId) {
        return accounts.findByAccountTypeAndOwnerId(type, ownerId).orElseThrow().getBalance();
    }

    public UUID accountId(AccountType type, String ownerId) {
        return accounts.findIdByTypeAndOwner(type, ownerId).orElseThrow();
    }

    /** A real HS256 token, signed with the app's key, for tests that go over HTTP instead of MockMvc. */
    public String bearerToken(UUID merchantId) {
        SecretKeySpec key = new SecretKeySpec(
                properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(merchantId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .build();
        return NimbusJwtEncoder.withSecretKey(key).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public static RequestPostProcessor as(UUID merchantId) {
        return jwt().jwt(j -> j.subject(merchantId.toString()));
    }
}
