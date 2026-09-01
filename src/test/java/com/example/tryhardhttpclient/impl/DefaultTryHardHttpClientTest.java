package com.example.tryhardhttpclient.impl;

import com.example.tryhardhttpclient.CapturingRetryScheduler;
import com.example.tryhardhttpclient.FakeHttpClient;
import com.example.tryhardhttpclient.FakeHttpResponse;
import com.example.tryhardhttpclient.ImmediateRetryScheduler;
import com.example.tryhardhttpclient.api.DelayStrategy;
import com.example.tryhardhttpclient.api.RetryContext;
import com.example.tryhardhttpclient.api.RetryDecision;
import com.example.tryhardhttpclient.api.RetryPolicy;
import com.example.tryhardhttpclient.api.TryHardHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultTryHardHttpClient}.
 *
 * <p>All collaborators are injected as test doubles; the real {@link DefaultSchedulerHolder} is
 * never used.
 */
@Timeout(10)
class DefaultTryHardHttpClientTest {

    private static final DelayStrategy ZERO_DELAY = attempt -> Duration.ZERO;

    private FakeHttpClient httpClient;
    private ImmediateRetryScheduler immediateScheduler;

    @BeforeEach
    void setUp() {
        httpClient = new FakeHttpClient();
        immediateScheduler = new ImmediateRetryScheduler();
    }

    private static HttpRequest newGetRequest() {
        return HttpRequest.newBuilder(URI.create("http://localhost/test")).GET().build();
    }

    private static RetryPolicy policyWithMaxAttempts(int max) {
        return RetryPolicy.builder()
                .maxAttempts(max)
                .delayStrategy(ZERO_DELAY)
                .build();
    }

    private TryHardHttpClient clientWith(RetryPolicy policy, ImmediateRetryScheduler scheduler) {
        return TryHardHttpClient.builder(httpClient)
                .retryPolicy(policy)
                .scheduler(scheduler)
                .build();
    }

    // ---- Successful first attempt — no retry ----

    @Test
    void successfulFirstAttempt_completesWithResponse_noRetry() throws Exception {
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        HttpResponse<String> expectedResponse = new FakeHttpResponse<>(200, "ok");
        httpClient.enqueueResponse(expectedResponse);

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> result = future.get();
        assertEquals(200, result.statusCode());
        assertEquals(0, immediateScheduler.scheduleCount(), "No retry should have been scheduled");
    }

    // ---- Retry on retryable response ----

    @Test
    void retryableResponse_retriesAndSucceeds() throws Exception {
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        // First attempt: 503 (retryable), second attempt: 200
        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "retry"));
        httpClient.enqueueResponse(new FakeHttpResponse<>(200, "ok"));

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> result = future.get();
        assertEquals(200, result.statusCode());
        assertEquals(1, immediateScheduler.scheduleCount(), "Expected exactly one retry");
    }

    // ---- maxAttempts exhausted: return final response ----

    @Test
    void maxAttemptsExhausted_returnsFinalResponse() throws Exception {
        // maxAttempts=2: one retry then give up
        RetryPolicy policy = policyWithMaxAttempts(2);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "retry"));
        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "final"));

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> result = future.get();
        assertEquals(503, result.statusCode());
        assertEquals("final", result.body());
        assertEquals(1, immediateScheduler.scheduleCount());
    }

    // ---- Cancellation prevents next retry ----

    @Test
    void cancellation_preventsNextRetry() throws Exception {
        // Use capturing scheduler so we can cancel before the retry runs
        CapturingRetryScheduler capturingScheduler = new CapturingRetryScheduler();
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = TryHardHttpClient.builder(httpClient)
                .retryPolicy(policy)
                .scheduler(capturingScheduler)
                .build();

        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "retry"));

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        // A task should have been scheduled but not yet run
        assertEquals(1, capturingScheduler.captured().size());
        CapturingRetryScheduler.CancellableTask scheduledTask =
                capturingScheduler.lastCaptured().handle();

        assertFalse(scheduledTask.wasCancelled(), "Task should not be cancelled yet");
        assertFalse(scheduledTask.wasRun(), "Task should not have run yet");

        // Cancel the outer future
        boolean cancelled = future.cancel(true);
        assertTrue(cancelled, "Future should have been cancelled");

        // The scheduled task must have been cancelled
        assertTrue(scheduledTask.wasCancelled(), "Scheduled task must be cancelled on future.cancel()");

        // Ensure no second attempt was made
        assertEquals(0, httpClient.remainingResponses() - 0,
                "There should be no enqueued responses consumed for a second attempt");
        assertThrows(CancellationException.class, () -> future.get());
    }

    // ---- Discarded response body is closed ----

    @Test
    void retriedResponse_bodyIsClosed() throws Exception {
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        // Body that tracks close calls
        TrackingCloseable trackingBody = new TrackingCloseable();
        HttpResponse<TrackingCloseable> retryableResponse =
                new FakeHttpResponse<>(503, trackingBody);

        httpClient.enqueueResponse(retryableResponse);
        httpClient.enqueueResponse(new FakeHttpResponse<>(200, new TrackingCloseable()));

        client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString()).get();

        assertTrue(trackingBody.wasClosed(),
                "Body of retried (discarded) response must be closed");
    }

    // ---- Request supplier called once per attempt ----

    @Test
    void requestSupplier_calledOncePerAttempt() throws Exception {
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "retry"));
        httpClient.enqueueResponse(new FakeHttpResponse<>(503, "retry"));
        httpClient.enqueueResponse(new FakeHttpResponse<>(200, "ok"));

        AtomicInteger supplierCallCount = new AtomicInteger(0);

        client.sendAsync(() -> {
            supplierCallCount.incrementAndGet();
            return newGetRequest();
        }, HttpResponse.BodyHandlers.ofString()).get();

        assertEquals(3, supplierCallCount.get(),
                "Supplier must be called once per attempt");
    }

    // ---- Supplier exception is treated as non-retryable ----

    @Test
    void supplierThrows_completesExceptionally_noRetry() {
        RetryPolicy policy = policyWithMaxAttempts(3);
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        RuntimeException supplierError = new RuntimeException("cannot build request");

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> { throw supplierError; }, HttpResponse.BodyHandlers.ofString());

        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertSame(supplierError, ex.getCause());
        assertEquals(0, immediateScheduler.scheduleCount(), "No retry after supplier exception");
    }

    // ---- Exception from transport ----

    @Test
    void transportException_notInRetryableSet_completesExceptionally() {
        RetryPolicy policy = policyWithMaxAttempts(3); // default: no exception retries
        TryHardHttpClient client = clientWith(policy, immediateScheduler);

        RuntimeException transportError = new RuntimeException("connection refused");
        httpClient.enqueueFailure(transportError);

        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertSame(transportError, ex.getCause());
        assertEquals(0, immediateScheduler.scheduleCount(), "No retry for non-listed exception");
    }

    // ---- Builder validation ----

    @Test
    void builder_withoutRetryPolicy_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                TryHardHttpClient.builder(httpClient).build());
    }

    // ---- Concurrency: two concurrent sendAsync calls do not interfere ----

    @Test
    void concurrentSendAsync_doNotInterfere() throws Exception {
        // Two independent FakeHttpClients to avoid shared queue contention
        FakeHttpClient client1Http = new FakeHttpClient();
        FakeHttpClient client2Http = new FakeHttpClient();

        RetryPolicy policy = policyWithMaxAttempts(2);
        ImmediateRetryScheduler sched1 = new ImmediateRetryScheduler();
        ImmediateRetryScheduler sched2 = new ImmediateRetryScheduler();

        TryHardHttpClient client1 = TryHardHttpClient.builder(client1Http)
                .retryPolicy(policy).scheduler(sched1).build();
        TryHardHttpClient client2 = TryHardHttpClient.builder(client2Http)
                .retryPolicy(policy).scheduler(sched2).build();

        client1Http.enqueueResponse(new FakeHttpResponse<>(503, "retry1"));
        client1Http.enqueueResponse(new FakeHttpResponse<>(200, "ok1"));

        client2Http.enqueueResponse(new FakeHttpResponse<>(503, "retry2"));
        client2Http.enqueueResponse(new FakeHttpResponse<>(200, "ok2"));

        CompletableFuture<HttpResponse<String>> f1 =
                client1.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> f2 =
                client2.sendAsync(() -> newGetRequest(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> r1 = f1.get();
        HttpResponse<String> r2 = f2.get();

        assertEquals(200, r1.statusCode());
        assertEquals(200, r2.statusCode());
    }

    // ---- Test helper: AutoCloseable body tracker ----

    static final class TrackingCloseable implements AutoCloseable {
        private boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }

        boolean wasClosed() {
            return closed;
        }
    }
}
