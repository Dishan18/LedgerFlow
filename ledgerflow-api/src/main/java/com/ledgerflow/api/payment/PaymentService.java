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
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final MerchantRepository merchants;
    private final AccountRepository accounts;
    private final JournalEntryRepository journalEntries;
    private final LedgerService ledger;
    private final PaymentEventPublisher events;
    private final BankGateway bank;
    private final PaymentConfirmationService confirmation;
    private final StatusPollerProperties pollerProperties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments,
                          RefundRepository refunds,
                          MerchantRepository merchants,
                          AccountRepository accounts,
                          JournalEntryRepository journalEntries,
                          LedgerService ledger,
                          PaymentEventPublisher events,
                          BankGateway bank,
                          PaymentConfirmationService confirmation,
                          StatusPollerProperties pollerProperties,
                          Clock clock,
                          TransactionTemplate tx) {
        this.payments = payments;
        this.refunds = refunds;
        this.merchants = merchants;
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.ledger = ledger;
        this.events = events;
        this.bank = bank;
        this.confirmation = confirmation;
        this.pollerProperties = pollerProperties;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Creates a payment and asks the bank to authorize it.
     *
     * <p>This is deliberately two transactions with the bank call between them, not one. Holding a
     * database transaction (and its connection and row locks) open across a network call would let a
     * slow bank exhaust the connection pool.
     *
     * <p>Once the payment row is committed we always answer with it, even if recording the bank's
     * answer fails. Throwing instead would release the Idempotency-Key, and the client's retry would
     * create a second payment. A payment left in CREATED is picked up by the status poller.
     */
    public Payment createAndAuthorize(UUID merchantId, String customerId, long amount) {
        Payment created = tx.execute(status -> create(merchantId, customerId, amount));
        try {
            AuthorizationResult result = bank.authorize(created.getId(), customerId, amount);
            return tx.execute(status -> {
                Payment payment = payments.lockById(created.getId())
                        .orElseThrow(() -> new PaymentNotFoundException(created.getId()));
                confirmation.applyAuthorization(payment, result);
                return payment;
            });
        } catch (RuntimeException e) {
            log.error("Could not record the bank's answer for payment {}; the status poller will resolve it",
                    created.getId(), e);
            return created;
        }
    }

    private Payment create(UUID merchantId, String customerId, long amount) {
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        customerAccount(customerId); // fail fast before creating a payment nobody can capture

        long fee = FeeCalculator.fee(amount, merchant.getFeeBps());
        Payment payment = Payment.create(merchantId, customerId, amount, fee);
        payment.scheduleStatusCheck(clock.instant().plus(pollerProperties.stuckCreatedAfter()));
        payments.save(payment);
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
