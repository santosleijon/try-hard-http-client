package com.github.santosleijon.tryhardhttpclient.api;

import com.github.santosleijon.tryhardhttpclient.impl.DefaultTryHardHttpClient;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An HTTP client that wraps a {@link java.net.http.HttpClient} and adds configurable retry behaviour.
 *
 * <p>Instances are thread-safe and intended to be shared across threads. The client is not
 * {@link AutoCloseable}; the lifecycle of the underlying scheduler is JVM-scoped.
 */
public interface TryHardHttpClient {

    /**
     * Sends an HTTP request asynchronously, retrying according to the configured {@link RetryPolicy}.
     *
     * <p>The {@code requestSupplier} is invoked once per attempt so that a fresh {@link HttpRequest}
     * can be created for every try (satisfying REQ-005 and the body-replay requirement).
     *
     * @param requestSupplier    produces the request for each attempt; must not be {@code null}
     * @param responseBodyHandler handles the response body; must not be {@code null}
     * @param <T>                the response body type
     * @return a {@link CompletableFuture} that completes with the final response,
     *         or exceptionally if all attempts fail with an exception
     */
    <T> CompletableFuture<HttpResponse<T>> sendAsync(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler);

    /**
     * Returns a new builder for creating a {@link TryHardHttpClient}.
     *
     * @param delegate the underlying {@link HttpClient} to wrap; must not be {@code null}
     * @return a new {@link Builder}
     */
    static Builder builder(HttpClient delegate) {
        return new DefaultTryHardHttpClient.Builder(delegate);
    }

    /**
     * Builder for {@link TryHardHttpClient}.
     */
    interface Builder {

        /**
         * Sets the retry policy to use. This field is mandatory.
         *
         * @param policy the retry policy; must not be {@code null}
         * @return this builder
         */
        Builder retryPolicy(RetryPolicy policy);

        /**
         * Sets a custom scheduler for retry delays. Optional; when not set, a shared JVM-scoped
         * singleton backed by daemon threads is used.
         *
         * @param scheduler the scheduler to use
         * @return this builder
         */
        Builder scheduler(RetryScheduler scheduler);

        /**
         * Builds the {@link TryHardHttpClient}.
         *
         * @return a new {@link TryHardHttpClient}
         * @throws IllegalStateException if {@code retryPolicy} was not set
         */
        TryHardHttpClient build();
    }
}
