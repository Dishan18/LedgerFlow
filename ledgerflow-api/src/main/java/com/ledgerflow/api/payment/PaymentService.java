package com.ledgerflow.api.payment;

import com.ledgerflow.api.bank.AuthorizationResult;
import com.ledgerflow.api.bank.BankGateway;
import com.ledgerflow.api.ledger.Account;
import com.ledgerflow.api.ledger.AccountRepository;
import com.ledgerflow.api.ledger.AccountType;
import com.ledgerflow.api.ledger.EntryType;
import com.ledgerflow.api.ledger.JournalEntry;
import com.ledgerflow.api.ledger.JournalEntryDraft;
import com.ledgerflow.api.ledger.JournalEntryRepository;
import com.ledgerflow.api.ledger.LedgerService;
import com.ledgerflow.api.merchant.Merchant;
import com.ledgerflow.api.merchant.MerchantNotFoundException;
import com.ledgerflow.api.merchant.MerchantRepository;
import com.ledgerflow.api.outbox.PaymentEventPublisher;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final MerchantRepository merchants;
    private final AccountRepository accounts;
    private final JournalEntryRepository journalEntries;
    private final LedgerService ledger;
    private final PaymentEventPublisher events;
    private final BankGateway bank;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments,
                          RefundRepository refunds,
                          MerchantRepository merchants,
                          AccountRepository accounts,
                          JournalEntryRepository journalEntries,
                          LedgerService ledger,
                          PaymentEventPublisher events,
                          BankGateway bank,
                          TransactionTemplate tx) {
        this.payments = payments;
        this.refunds = refunds;
        this.merchants = merchants;
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.ledger = ledger;
        this.events = events;
        this.bank = bank;
        this.tx = tx;
    }

    /**
     * Creates a payment and asks the bank to authorize it.
     *
     * <p>This is deliberately two transactions with the bank call between them, not one. Holding a
     * database transaction (and its connection and row locks) open across a network call would let a
     * slow bank exhaust the connection pool. The price is that a crash between the two leaves the
     * payment in CREATED, which the status poller (Phase 3) resolves.
     */
    public Payment createAndAuthorize(UUID merchantId, String customerId, long amount) {
        Payment created = tx.execute(status -> create(merchantId, customerId, amount));

        AuthorizationResult result = bank.authorize(created.getId(), customerId, amount);

        return tx.execute(status -> {
            Payment payment = payments.lockByIdAndMerchantId(created.getId(), merchantId)
                    .orElseThrow(() -> new PaymentNotFoundException(created.getId()));
            switch (result) {
                case AuthorizationResult.Approved approved -> {
                    payment.markAuthorized(approved.bankReference());
                    events.publish("payment.authorized", payment);
                }
                case AuthorizationResult.Declined declined -> {
                    payment.transitionTo(PaymentStatus.FAILED);
                    events.publish("payment.failed", payment);
                }
            }
            return payment;
        });
    }

    private Payment create(UUID merchantId, String customerId, long amount) {
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        customerAccount(customerId); // fail fast before creating a payment nobody can capture

        long fee = FeeCalculator.fee(amount, merchant.getFeeBps());
        Payment payment = payments.save(Payment.create(merchantId, customerId, amount, fee));
        events.publish("payment.created", payment);
        return payment;
    }

    /**
     * Captures the full amount: customer is debited, merchant receivable and platform fee are
     * credited. Status change, journal entry and outbox event commit atomically or not at all.
     */
    @Transactional
    public Payment capture(UUID merchantId, UUID paymentId) {
        Payment payment = payments.lockByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        payment.capture();

        UUID customer = customerAccount(payment.getCustomerId());
        UUID merchantReceivable = merchantReceivableAccount(merchantId);
        UUID platformFee = systemAccount(AccountType.PLATFORM_FEE);

        ledger.post(JournalEntryDraft.builder(EntryType.CAPTURE, "capture of payment " + paymentId)
                .paymentId(paymentId)
                .debit(customer, payment.getAmount())
                .credit(merchantReceivable, payment.getAmount() - payment.getFeeAmount())
                .credit(platformFee, payment.getFeeAmount())
                .build());

        events.publish("payment.captured", payment);
        return payment;
    }

    /**
     * Refunds part or all of a captured payment. The journal entry is the mirror image of the
     * capture, scaled to the refund, and points back at it via {@code reverses_entry_id}.
     */
    @Transactional
    public Refund refund(UUID merchantId, UUID paymentId, long amount) {
        Payment payment = payments.lockByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        long feePortion = payment.applyRefund(amount);

        JournalEntry capture = journalEntries.findFirstByPaymentIdAndEntryType(paymentId, EntryType.CAPTURE)
                .orElseThrow(() -> new IllegalStateException("captured payment has no capture entry: " + paymentId));
        UUID customer = customerAccount(payment.getCustomerId());
        UUID merchantReceivable = merchantReceivableAccount(merchantId);
        UUID platformFee = systemAccount(AccountType.PLATFORM_FEE);

        JournalEntry reversal = ledger.post(JournalEntryDraft.builder(EntryType.REFUND, "refund of payment " + paymentId)
                .paymentId(paymentId)
                .reverses(capture.getId())
                .debit(merchantReceivable, amount - feePortion)
                .debit(platformFee, feePortion)
                .credit(customer, amount)
                .build());

        Refund refund = refunds.save(new Refund(paymentId, amount, reversal.getId()));
        events.publish("payment.refunded", payment);
        return refund;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID merchantId, UUID paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private UUID customerAccount(String customerId) {
        return accounts.findIdByTypeAndOwner(AccountType.CUSTOMER, customerId)
                .orElseThrow(() -> new CustomerAccountNotFoundException(customerId));
    }

    private UUID merchantReceivableAccount(UUID merchantId) {
        return accounts.findIdByTypeAndOwner(AccountType.MERCHANT_RECEIVABLE, merchantId.toString())
                .orElseThrow(() -> new IllegalStateException("merchant has no receivable account: " + merchantId));
    }

    private UUID systemAccount(AccountType type) {
        return accounts.findIdByTypeAndOwner(type, Account.SYSTEM_OWNER)
                .orElseThrow(() -> new IllegalStateException("missing system account " + type));
    }
}
