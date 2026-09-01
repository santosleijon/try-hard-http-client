package com.example.tryhardhttpclient;

import com.example.tryhardhttpclient.api.DelayStrategy;
import com.example.tryhardhttpclient.api.RetryPolicy;
import com.example.tryhardhttpclient.api.TryHardHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TryHardHttpClient} using a real embedded JDK HTTP server
 * bound to a loopback address on an ephemeral port.
 *
 * <p>The {@link DelayStrategy} is configured to return {@link Duration#ZERO} to keep tests fast.
 * The real shared scheduler ({@code DefaultSchedulerHolder}) is used.
 */
@Timeout(30)
class TryHardHttpClientIntegrationTest {

    private HttpServer server;
    private URI serverUri;
    private HttpClient delegate;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        serverUri = URI.create("http://127.0.0.1:" + port);
        delegate = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI uri(String path) {
        return serverUri.resolve(path);
    }

    /**
     * Builds a client that retries GET + default status codes with zero delay.
     */
    private TryHardHttpClient clientWithMaxAttempts(int maxAttempts) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .delayStrategy(attempt -> Duration.ZERO)
                .build();
        return TryHardHttpClient.builder(delegate).retryPolicy(policy).build();
    }

    // ---- Scenario 1: 503 twice then 200 ----

    @Test
    void server_returns503Twice_then200_clientSucceeds() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/test", exchange -> {
            int count = requestCount.incrementAndGet();
            int status = count <= 2 ? 503 : 200;
            byte[] body = ("attempt=" + count).getBytes();
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        TryHardHttpClient client = clientWithMaxAttempts(5);

        HttpResponse<String> response = client
                .sendAsync(() -> HttpRequest.newBuilder(uri("/test")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .get();

        assertEquals(200, response.statusCode());
        assertEquals(3, requestCount.get(), "Server should have received exactly 3 requests");
    }

    // ---- Scenario 2: Always 503 → stop after maxAttempts, return 503 ----

    @Test
    void server_always503_clientStopsAfterMaxAttempts() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/always503", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        int maxAttempts = 3;
        TryHardHttpClient client = clientWithMaxAttempts(maxAttempts);

        HttpResponse<String> response = client
                .sendAsync(() -> HttpRequest.newBuilder(uri("/always503")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .get();

        // Must return the final failing response (REQ-008), not throw
        assertEquals(503, response.statusCode());
        assertEquals(maxAttempts, requestCount.get(),
                "Server should have received exactly maxAttempts requests");
    }

    // ---- Scenario 3: 200 immediately → single attempt, no retry ----

    @Test
    void server_returns200Immediately_singleAttempt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/ok", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        TryHardHttpClient client = clientWithMaxAttempts(5);

        HttpResponse<String> response = client
                .sendAsync(() -> HttpRequest.newBuilder(uri("/ok")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .get();

        assertEquals(200, response.statusCode());
        assertEquals(1, requestCount.get(), "Single 200 response should not trigger any retry");
    }

    // ---- Scenario 4: POST with no opt-in + 503 → no retry ----

    @Test
    void post_withNoOptIn_and503_noRetry() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/post", exchange -> {
            requestCount.incrementAndGet();
            // Consume request body to avoid broken pipe on client side
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            byte[] body = "post unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .delayStrategy(attempt -> Duration.ZERO)
                // No allowRetryForMethods call → POST not retried
                .build();
        TryHardHttpClient client = TryHardHttpClient.builder(delegate).retryPolicy(policy).build();

        HttpResponse<String> response = client
                .sendAsync(
                        () -> HttpRequest.newBuilder(uri("/post"))
                                .POST(HttpRequest.BodyPublishers.ofString("data"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .get();

        assertEquals(503, response.statusCode());
        assertEquals(1, requestCount.get(), "POST without opt-in must not be retried on 503");
    }

    // ---- Scenario 5: POST with allowRetryForMethods + 503 → retried ----

    @Test
    void post_withAllowRetryForMethods_and503_isRetried() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/post-retry", exchange -> {
            int count = requestCount.incrementAndGet();
            // Consume request body
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            int status = count < 2 ? 503 : 200;
            byte[] body = ("count=" + count).getBytes();
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .delayStrategy(attempt -> Duration.ZERO)
                .allowRetryForMethods(Set.of("POST"))
                .build();
        TryHardHttpClient client = TryHardHttpClient.builder(delegate).retryPolicy(policy).build();

        HttpResponse<String> response = client
                .sendAsync(
                        () -> HttpRequest.newBuilder(uri("/post-retry"))
                                .POST(HttpRequest.BodyPublishers.ofString("data"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .get();

        assertEquals(200, response.statusCode());
        assertEquals(2, requestCount.get(),
                "POST with allowRetryForMethods + 503 should be retried once");
    }
}
