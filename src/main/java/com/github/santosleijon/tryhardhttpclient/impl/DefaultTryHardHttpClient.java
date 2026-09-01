package com.github.santosleijon.tryhardhttpclient.impl;

import com.github.santosleijon.tryhardhttpclient.api.RetryContext;
import com.github.santosleijon.tryhardhttpclient.api.RetryDecision;
import com.github.santosleijon.tryhardhttpclient.api.RetryPolicy;
import com.github.santosleijon.tryhardhttpclient.api.RetryScheduler;
import com.github.santosleijon.tryhardhttpclient.api.ScheduledTask;
import com.github.santosleijon.tryhardhttpclient.api.TryHardHttpClient;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Default implementation of {@link TryHardHttpClient}.
 *
 * <p>Wraps a {@link HttpClient} and retries failed requests according to a {@link RetryPolicy}.
 * This class is immutable and thread-safe.
 */
public final class DefaultTryHardHttpClient implements TryHardHttpClient {

    private static final Logger LOGGER =
            Logger.getLogger(DefaultTryHardHttpClient.class.getName());

    private final HttpClient delegate;
    private final RetryPolicy policy;
    private final RetryScheduler scheduler;

    private DefaultTryHardHttpClient(Builder builder) {
        this.delegate = builder.delegate;
        this.policy = builder.policy;
        this.scheduler = builder.scheduler;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler) {

        AtomicReference<ScheduledTask> pendingTask = new AtomicReference<>();

        // An anonymous subclass of CompletableFuture that cancels the pending retry on cancel().
        CompletableFuture<HttpResponse<T>> outerFuture = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    ScheduledTask task = pendingTask.getAndSet(null);
                    if (task != null) {
                        task.cancel();
                    }
                }
                return cancelled;
            }
        };

        attempt(requestSupplier, responseBodyHandler, outerFuture, pendingTask, 1);

        return outerFuture;
    }

    private <T> void attempt(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            CompletableFuture<HttpResponse<T>> outerFuture,
            AtomicReference<ScheduledTask> pendingTask,
            int attemptNumber) {

        if (outerFuture.isDone()) {
            return;
        }

        HttpRequest request;
        try {
            request = requestSupplier.get();
        } catch (Throwable t) {
            outerFuture.completeExceptionally(t);
            return;
        }

        delegate.sendAsync(request, responseBodyHandler)
                .whenComplete((response, failure) ->
                        handleCompletion(requestSupplier, responseBodyHandler,
                                outerFuture, pendingTask, attemptNumber, request, response, failure));
    }

    private <T> void handleCompletion(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            CompletableFuture<HttpResponse<T>> outerFuture,
            AtomicReference<ScheduledTask> pendingTask,
            int attemptNumber,
            HttpRequest request,
            HttpResponse<T> response,
            Throwable failure) {

        if (outerFuture.isDone()) {
            // Future was cancelled while a request was in-flight; discard the result.
            if (response != null) {
                discardBody(response);
            }
            return;
        }

        RetryContext ctx = new RetryContext(
                request,
                Optional.ofNullable(response),
                Optional.ofNullable(failure),
                attemptNumber);

        RetryDecision decision;
        try {
            decision = policy.evaluate(ctx);
        } catch (Throwable t) {
            outerFuture.completeExceptionally(t);
            return;
        }

        if (!decision.retry()) {
            if (failure != null) {
                outerFuture.completeExceptionally(failure);
            } else {
                outerFuture.complete(response);
            }
            return;
        }

        // Retry: discard the current response body before scheduling the next attempt.
        if (response != null) {
            discardBody(response);
        }

        // Schedule the next attempt. Store the ScheduledTask so cancellation can abort it.
        ScheduledTask task = scheduler.schedule(
                () -> attempt(requestSupplier, responseBodyHandler,
                        outerFuture, pendingTask, attemptNumber + 1),
                decision.delay());

        pendingTask.set(task);

        // If the future was cancelled while we were between scheduling and storing, cancel now.
        if (outerFuture.isDone()) {
            ScheduledTask storedTask = pendingTask.getAndSet(null);
            if (storedTask != null) {
                storedTask.cancel();
            }
        }
    }

    /**
     * Discards the body of a response that will not be returned to the caller.
     * If the body implements {@link AutoCloseable}, it is closed; otherwise it is ignored.
     * Close failures are logged at WARNING level and not propagated.
     */
    private static <T> void discardBody(HttpResponse<T> response) {
        T body = response.body();
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warning("Failed to close discarded response body: " + e.getMessage());
            }
        }
    }

    /**
     * Builder for {@link DefaultTryHardHttpClient}.
     */
    public static final class Builder implements TryHardHttpClient.Builder {

        private final HttpClient delegate;
        private RetryPolicy policy;
        private RetryScheduler scheduler = DefaultSchedulerHolder.INSTANCE;

        public Builder(HttpClient delegate) {
            if (delegate == null) {
                throw new IllegalArgumentException("delegate must not be null");
            }
            this.delegate = delegate;
        }

        @Override
        public Builder retryPolicy(RetryPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("policy must not be null");
            }
            this.policy = policy;
            return this;
        }

        @Override
        public Builder scheduler(RetryScheduler scheduler) {
            if (scheduler == null) {
                throw new IllegalArgumentException("scheduler must not be null");
            }
            this.scheduler = scheduler;
            return this;
        }

        @Override
        public TryHardHttpClient build() {
            if (policy == null) {
                throw new IllegalStateException("retryPolicy must be set");
            }
            return new DefaultTryHardHttpClient(this);
        }
    }
}
