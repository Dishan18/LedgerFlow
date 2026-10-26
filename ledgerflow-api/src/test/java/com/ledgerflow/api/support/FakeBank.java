package com.ledgerflow.api.support;

import com.ledgerflow.common.bank.BankApi;
import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.bank.CollectResponse;
import com.ledgerflow.common.bank.CollectStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scriptable bank for LedgerFlow's tests, built on the JDK's own HTTP server (no extra library).
 * Each test decides how the next collects behave; the default approves everything instantly.
 *
 * <p>Like the real mock-bank, it records a collect <em>before</em> any artificial delay, so a
 * "timeout" means "the bank did it, but the answer was lost".
 */
public final class FakeBank {

    /** Decides how to answer one collect attempt. */
    @FunctionalInterface
    public interface CollectHandler {
        Reply handle(CollectRequest request, int attempt);
    }

    /** HTTP status, optional body (recorded as the bank's state when status is 200), delay before answering. */
    public record Reply(int status, CollectResponse body, Duration delay) {
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<UUID, CollectResponse> known = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> collectAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> statusChecks = new ConcurrentHashMap<>();
    private volatile CollectHandler handler = FakeBank::approve;

    private FakeBank(HttpServer server) {
        this.server = server;
    }

    public static FakeBank start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 1000);
            FakeBank bank = new FakeBank(server);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(BankApi.COLLECTS, bank::handle);
            server.start();
            return bank;
        } catch (IOException e) {
            throw new IllegalStateException("could not start fake bank", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    // ---- scripting -------------------------------------------------------------------------------

    public void onCollect(CollectHandler handler) {
        this.handler = handler;
    }

    public void reset() {
        handler = FakeBank::approve;
        known.clear();
        collectAttempts.clear();
        statusChecks.clear();
    }

    /** Sets what the bank will report for this payment from now on. */
    public void resolve(UUID paymentId, CollectStatus status, String reasonOrUtr) {
        known.put(paymentId, response(paymentId, status, reasonOrUtr));
    }

    public int collectAttempts(UUID paymentId) {
        AtomicInteger count = collectAttempts.get(paymentId);
        return count == null ? 0 : count.get();
    }

    public int statusChecks(UUID paymentId) {
        AtomicInteger count = statusChecks.get(paymentId);
        return count == null ? 0 : count.get();
    }

    public boolean knows(UUID paymentId) {
        return known.containsKey(paymentId);
    }

    // ---- canned behaviours -----------------------------------------------------------------------

    public static Reply approve(CollectRequest request, int attempt) {
        return ok(response(request.paymentId(), CollectStatus.APPROVED, "UTR" + request.paymentId().toString().substring(0, 8)));
    }

    public static CollectHandler decline(String reason) {
        return (request, attempt) -> ok(response(request.paymentId(), CollectStatus.DECLINED, reason));
    }

    public static CollectHandler pending() {
        return (request, attempt) -> ok(response(request.paymentId(), CollectStatus.PENDING, null));
    }

    public static CollectHandler failWith(int httpStatus) {
        return (request, attempt) -> new Reply(httpStatus, null, Duration.ZERO);
    }

    /**
     * Approves, but answers every attempt only after {@code delay}. Longer than the client timeout
     * means every response is lost.
     */
    public static CollectHandler approveAfter(Duration delay) {
        return (request, attempt) -> {
            Reply approved = approve(request, attempt);
            return new Reply(approved.status(), approved.body(), delay);
        };
    }

    /** Approves; only the first answer is slow (lost), retries are answered at once. */
    public static CollectHandler approveFirstAnswerLost(Duration delay) {
        return (request, attempt) -> {
            Reply approved = approve(request, attempt);
            return attempt == 1 ? new Reply(approved.status(), approved.body(), delay) : approved;
        };
    }

    /** 503 for the first {@code failures} attempts, then approve. */
    public static CollectHandler flaky(int failures) {
        return (request, attempt) -> attempt <= failures ? new Reply(503, null, Duration.ZERO) : approve(request, attempt);
    }

    private static Reply ok(CollectResponse body) {
        return new Reply(200, body, Duration.ZERO);
    }

    private static CollectResponse response(UUID paymentId, CollectStatus status, String reasonOrUtr) {
        return switch (status) {
            case APPROVED -> new CollectResponse(paymentId, status, reasonOrUtr, null);
            case DECLINED -> new CollectResponse(paymentId, status, null, reasonOrUtr);
            case PENDING -> new CollectResponse(paymentId, status, null, null);
        };
    }

    // ---- HTTP plumbing ---------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(BankApi.COLLECTS)) {
                CollectRequest request = json.readValue(exchange.getRequestBody(), CollectRequest.class);
                int attempt = collectAttempts.computeIfAbsent(request.paymentId(), id -> new AtomicInteger()).incrementAndGet();
                // The handler still decides status and delay on retries (a bank can stay slow), but a
                // collect it already recorded keeps its original outcome: retries are idempotent.
                Reply reply = handler.handle(request, attempt);
                if (reply.status() == 200 && reply.body() != null) {
                    known.putIfAbsent(request.paymentId(), reply.body());
                }
                sleep(reply.delay());
                send(exchange, reply.status(), reply.body() == null ? null : known.get(request.paymentId()));
            } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith(BankApi.COLLECTS + "/")) {
                UUID paymentId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                statusChecks.computeIfAbsent(paymentId, id -> new AtomicInteger()).incrementAndGet();
                CollectResponse response = known.get(paymentId);
                send(exchange, response == null ? 404 : 200, response);
            } else {
                send(exchange, 404, null);
            }
        } catch (IOException clientWentAway) {
            // The client timed out and closed the connection; that's the point of some tests.
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
