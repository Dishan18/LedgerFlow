package com.ledgerflow.api.support;

import com.ledgerflow.common.webhook.WebhookSignature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny "merchant" that receives LedgerFlow webhooks and verifies their signatures the way a real
 * merchant should: on the raw body, before parsing, with a replay window. It can be told to fail
 * its first N requests (or all of them) to exercise retries and the DLQ.
 */
public final class MerchantReceiver {

    public record Received(UUID eventId, String eventType, String body, boolean signatureValid) {
    }

    private final HttpServer server;
    private final String secret;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int failFirst;

    private MerchantReceiver(HttpServer server, String secret) {
        this.server = server;
        this.secret = secret;
    }

    public static MerchantReceiver start(String secret) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 100);
            MerchantReceiver receiver = new MerchantReceiver(server, secret);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/webhooks", receiver::handle);
            server.start();
            return receiver;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/webhooks";
    }

    /** Answer 500 to the first {@code n} requests. Use {@link Integer#MAX_VALUE} to always fail. */
    public void failFirst(int n) {
        this.failFirst = n;
    }

    /** Successfully accepted deliveries. */
    public List<Received> received() {
        return List.copyOf(received);
    }

    public int requests() {
        return requests.get();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = requests.incrementAndGet();
            if (n <= failFirst) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            String signature = exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER);
            boolean valid = WebhookSignature.verify(secret, signature, body, Instant.now(), Duration.ofMinutes(5));
            received.add(new Received(
                    UUID.fromString(exchange.getRequestHeaders().getFirst(WebhookSignature.EVENT_ID_HEADER)),
                    exchange.getRequestHeaders().getFirst(WebhookSignature.EVENT_TYPE_HEADER),
                    body,
                    valid));
            exchange.sendResponseHeaders(valid ? 200 : 401, -1);
        }
    }
}
