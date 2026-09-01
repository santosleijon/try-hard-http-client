package com.github.santosleijon.tryhardhttpclient.impl;

import com.github.santosleijon.tryhardhttpclient.FakeHttpResponse;
import com.github.santosleijon.tryhardhttpclient.api.DelayStrategy;
import com.github.santosleijon.tryhardhttpclient.api.RetryContext;
import com.github.santosleijon.tryhardhttpclient.api.RetryDecision;
import com.github.santosleijon.tryhardhttpclient.api.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultRetryPolicy}.
 */
class DefaultRetryPolicyTest {

    /** A zero-delay strategy to eliminate timing concerns. */
    private static final DelayStrategy ZERO_DELAY = attempt -> Duration.ZERO;

    private RetryPolicy.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RetryPolicy.builder().delayStrategy(ZERO_DELAY);
    }

    // ---- Helper methods ----

    private static HttpRequest getRequest() {
        return HttpRequest.newBuilder(URI.create("http://localhost/test")).GET().build();
    }

    private static HttpRequest requestForMethod(String method) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost/test"));
        return switch (method) {
            case "GET" -> b.GET().build();
            case "HEAD" -> b.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> b.DELETE().build();
            case "POST" -> b.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
            case "OPTIONS" -> b.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
    }

    private static RetryContext responseCtx(HttpRequest request, int status, int attempt) {
        HttpResponse<?> response = new FakeHttpResponse<>(status, "body", request);
        return new RetryContext(request, Optional.of(response), Optional.empty(), attempt);
    }

    private static RetryContext failureCtx(HttpRequest request, Throwable failure, int attempt) {
        return new RetryContext(request, Optional.empty(), Optional.of(failure), attempt);
    }

    // ---- maxAttempts boundary ----

    @Test
    void maxAttempts_1_neverRetries() {
        RetryPolicy policy = builder.maxAttempts(1).build();
        RetryDecision d = policy.evaluate(responseCtx(getRequest(), 503, 1));
        assertFalse(d.retry(), "maxAttempts=1 should never retry");
    }

    @Test
    void maxAttempts_3_retriesUntilThirdAttempt() {
        RetryPolicy policy = builder.maxAttempts(3).build();

        // Attempt 1 and 2 should be retryable (503, GET)
        RetryDecision d1 = policy.evaluate(responseCtx(getRequest(), 503, 1));
        assertTrue(d1.retry(), "Attempt 1 of 3 should retry");

        RetryDecision d2 = policy.evaluate(responseCtx(getRequest(), 503, 2));
        assertTrue(d2.retry(), "Attempt 2 of 3 should retry");

        // Attempt 3 = maxAttempts → no retry
        RetryDecision d3 = policy.evaluate(responseCtx(getRequest(), 503, 3));
        assertFalse(d3.retry(), "Attempt 3 of 3 should not retry (max reached)");
    }

    // ---- Retryable status codes ----

    @Test
    void retryableStatusCodes_408_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 408, 1);
    }

    @Test
    void retryableStatusCodes_429_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 429, 1);
    }

    @Test
    void retryableStatusCodes_500_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 500, 1);
    }

    @Test
    void retryableStatusCodes_502_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 502, 1);
    }

    @Test
    void retryableStatusCodes_503_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 503, 1);
    }

    @Test
    void retryableStatusCodes_504_withGet_retries() {
        assertRetries(builder.maxAttempts(3).build(), getRequest(), 504, 1);
    }

    // ---- Non-retryable status codes ----

    @Test
    void statusCode_200_withGet_doesNotRetry() {
        assertDoesNotRetry(builder.maxAttempts(3).build(), getRequest(), 200, 1);
    }

    @Test
    void statusCode_404_withGet_doesNotRetry() {
        assertDoesNotRetry(builder.maxAttempts(3).build(), getRequest(), 404, 1);
    }

    @Test
    void statusCode_301_withGet_doesNotRetry() {
        assertDoesNotRetry(builder.maxAttempts(3).build(), getRequest(), 301, 1);
    }

    // ---- Non-retryable methods (default — no opt-in) ----

    @Test
    void post_withRetryableStatus_doesNotRetryByDefault() {
        RetryPolicy policy = builder.maxAttempts(3).build();
        assertDoesNotRetry(policy, requestForMethod("POST"), 503, 1);
    }

    @Test
    void patch_withRetryableStatus_doesNotRetryByDefault() {
        RetryPolicy policy = builder.maxAttempts(3).build();
        assertDoesNotRetry(policy, requestForMethod("PATCH"), 503, 1);
    }

    // ---- Opted-in methods ----

    @Test
    void post_withAllowRetryForMethods_andRetryableStatus_retries() {
        RetryPolicy policy = builder
                .maxAttempts(3)
                .allowRetryForMethods(Set.of("POST"))
                .build();
        assertRetries(policy, requestForMethod("POST"), 503, 1);
    }

    // ---- Default idempotent methods ----

    @Test
    void head_withRetryableStatus_retries() {
        assertRetries(builder.maxAttempts(3).build(), requestForMethod("HEAD"), 503, 1);
    }

    @Test
    void put_withRetryableStatus_retries() {
        assertRetries(builder.maxAttempts(3).build(), requestForMethod("PUT"), 503, 1);
    }

    @Test
    void delete_withRetryableStatus_retries() {
        assertRetries(builder.maxAttempts(3).build(), requestForMethod("DELETE"), 503, 1);
    }

    @Test
    void options_withRetryableStatus_retries() {
        assertRetries(builder.maxAttempts(3).build(), requestForMethod("OPTIONS"), 503, 1);
    }

    // ---- Exception-based retry ----

    @Test
    void optedInException_retries() {
        RetryPolicy policy = builder
                .maxAttempts(3)
                .retryableExceptions(Set.of(IOException.class))
                .build();

        RetryDecision d = policy.evaluate(failureCtx(getRequest(), new IOException("timeout"), 1));
        assertTrue(d.retry(), "Opted-in exception should trigger retry");
    }

    @Test
    void nonListedException_doesNotRetry() {
        RetryPolicy policy = builder.maxAttempts(3).build(); // no retryableExceptions

        RetryDecision d = policy.evaluate(failureCtx(getRequest(), new IOException("timeout"), 1));
        assertFalse(d.retry(), "Exception not in retryable set should not retry");
    }

    @Test
    void supertypeMatch_retries() {
        // IOException is a supertype of java.net.SocketTimeoutException
        RetryPolicy policy = builder
                .maxAttempts(3)
                .retryableExceptions(Set.of(IOException.class))
                .build();

        RetryDecision d = policy.evaluate(
                failureCtx(getRequest(), new java.net.SocketTimeoutException("connect timed out"), 1));
        assertTrue(d.retry(), "Supertype exception match should trigger retry");
    }

    // ---- TLS exclusion ----

    @Test
    void sslException_isNeverRetried_evenIfListed() {
        RetryPolicy policy = builder
                .maxAttempts(3)
                .retryableExceptions(Set.of(SSLException.class))
                .build();

        RetryDecision d = policy.evaluate(
                failureCtx(getRequest(), new SSLException("bad cert"), 1));
        assertFalse(d.retry(), "SSLException must never be retried");
    }

    @Test
    void sslHandshakeException_isNeverRetried_evenIfListed() {
        RetryPolicy policy = builder
                .maxAttempts(3)
                .retryableExceptions(Set.of(SSLException.class, SSLHandshakeException.class))
                .build();

        RetryDecision d = policy.evaluate(
                failureCtx(getRequest(), new SSLHandshakeException("cert verify failed"), 1));
        assertFalse(d.retry(), "SSLHandshakeException must never be retried");
    }

    // ---- Builder validation ----

    @Test
    void build_withoutMaxAttempts_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> RetryPolicy.builder().build());
    }

    @Test
    void build_withMaxAttempts_lessThan1_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().maxAttempts(0));
    }

    // ---- Delay is included in retry decision ----

    @Test
    void retryDecision_includesDelay_fromDelayStrategy() {
        Duration expectedDelay = Duration.ofMillis(42);
        RetryPolicy policy = builder
                .maxAttempts(3)
                .delayStrategy(attempt -> expectedDelay)
                .build();

        RetryDecision d = policy.evaluate(responseCtx(getRequest(), 503, 1));
        assertTrue(d.retry());
        assertEquals(expectedDelay, d.delay());
    }

    // ---- Helpers ----

    private static void assertRetries(RetryPolicy policy, HttpRequest request, int status, int attempt) {
        RetryDecision d = policy.evaluate(responseCtx(request, status, attempt));
        assertTrue(d.retry(),
                "Expected retry for " + request.method() + " / " + status
                        + " attempt " + attempt + ", reason: " + d.reason());
    }

    private static void assertDoesNotRetry(RetryPolicy policy, HttpRequest request, int status, int attempt) {
        RetryDecision d = policy.evaluate(responseCtx(request, status, attempt));
        assertFalse(d.retry(),
                "Expected no retry for " + request.method() + " / " + status
                        + " attempt " + attempt + ", reason: " + d.reason());
    }
}
