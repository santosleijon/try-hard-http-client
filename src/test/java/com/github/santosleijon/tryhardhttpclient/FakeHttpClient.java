package com.github.santosleijon.tryhardhttpclient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A test double for {@link HttpClient} that returns pre-configured responses in order.
 *
 * <p>Each call to {@link #sendAsync} dequeues the next response (or exception) from the configured
 * queue. If no response is left, an {@link AssertionError} is thrown.
 */
public final class FakeHttpClient extends HttpClient {

    /**
     * Holder for a response outcome: either a successful response or an exception.
     */
    public sealed interface ResponseOutcome {
        record Success<T>(HttpResponse<T> response) implements ResponseOutcome {}
        record Failure(Throwable exception) implements ResponseOutcome {}
    }

    private final Queue<ResponseOutcome> outcomes = new ArrayDeque<>();

    /**
     * Enqueues a successful response to be returned for the next sendAsync call.
     */
    public void enqueueResponse(HttpResponse<?> response) {
        outcomes.add(new ResponseOutcome.Success<>(response));
    }

    /**
     * Enqueues an exception to be thrown for the next sendAsync call.
     */
    public void enqueueFailure(Throwable failure) {
        outcomes.add(new ResponseOutcome.Failure(failure));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {

        ResponseOutcome outcome = outcomes.poll();
        if (outcome == null) {
            return CompletableFuture.failedFuture(
                    new AssertionError("No more responses configured in FakeHttpClient"));
        }

        return switch (outcome) {
            case ResponseOutcome.Success<?> s ->
                    CompletableFuture.completedFuture((HttpResponse<T>) s.response());
            case ResponseOutcome.Failure f ->
                    CompletableFuture.failedFuture(f.exception());
        };
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }

    @Override
    public <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Use sendAsync");
    }

    // ---- Unused abstract method stubs ----

    @Override
    public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

    @Override
    public Optional<Duration> connectTimeout() { return Optional.empty(); }

    @Override
    public Redirect followRedirects() { return Redirect.NEVER; }

    @Override
    public Optional<ProxySelector> proxy() { return Optional.empty(); }

    @Override
    public SSLContext sslContext() {
        try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override
    public SSLParameters sslParameters() { return new SSLParameters(); }

    @Override
    public Optional<Authenticator> authenticator() { return Optional.empty(); }

    @Override
    public Version version() { return Version.HTTP_1_1; }

    @Override
    public Optional<Executor> executor() { return Optional.empty(); }

    /** Returns the number of responses remaining in the queue. */
    public int remainingResponses() {
        return outcomes.size();
    }
}
