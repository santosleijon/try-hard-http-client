package com.github.santosleijon.tryhardhttpclient.impl;

import com.github.santosleijon.tryhardhttpclient.api.DelayStrategy;
import com.github.santosleijon.tryhardhttpclient.api.RetryContext;
import com.github.santosleijon.tryhardhttpclient.api.RetryDecision;
import com.github.santosleijon.tryhardhttpclient.api.RetryPolicy;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Set;

/**
 * Default immutable {@link RetryPolicy} implementation.
 *
 * <p>Constructed via {@link Builder}. All fields are final.
 */
public final class DefaultRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final Set<Integer> retryableStatusCodes;
    private final Set<String> retryableMethods;
    private final Set<String> allowRetryForMethods;
    private final Set<Class<? extends Throwable>> retryableExceptions;
    private final DelayStrategy delayStrategy;

    private DefaultRetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.retryableStatusCodes = Set.copyOf(builder.retryableStatusCodes);
        this.retryableMethods = Set.copyOf(builder.retryableMethods);
        this.allowRetryForMethods = Set.copyOf(builder.allowRetryForMethods);
        this.retryableExceptions = Set.copyOf(builder.retryableExceptions);
        this.delayStrategy = builder.delayStrategy;
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public RetryDecision evaluate(RetryContext ctx) {
        // 1. Max attempts check
        if (ctx.attemptNumber() >= maxAttempts) {
            return RetryDecision.doNotRetry("max attempts reached");
        }

        // 2. Exception-based retry
        if (ctx.failure().isPresent()) {
            Throwable failure = ctx.failure().get();

            // Never retry TLS/SSL failures, even if the exception type was listed
            if (isSslException(failure)) {
                return RetryDecision.doNotRetry("TLS/SSL exceptions are never retried");
            }

            if (isRetryableException(failure)) {
                Duration delay = delayStrategy.delayFor(ctx.attemptNumber());
                return RetryDecision.retry("retryable exception: " + failure.getClass().getName(), delay);
            }

            return RetryDecision.doNotRetry("exception not in retryable set: " + failure.getClass().getName());
        }

        // 3. Response-based retry
        if (ctx.response().isPresent()) {
            int statusCode = ctx.response().get().statusCode();
            String method = ctx.request().method();

            boolean retryableStatus = retryableStatusCodes.contains(statusCode);
            boolean retryableMethod = retryableMethods.contains(method) || allowRetryForMethods.contains(method);

            if (retryableStatus && retryableMethod) {
                Duration delay = delayStrategy.delayFor(ctx.attemptNumber());
                return RetryDecision.retry(
                        "status " + statusCode + " is retryable for method " + method, delay);
            }

            if (!retryableStatus) {
                return RetryDecision.doNotRetry("status code " + statusCode + " is not retryable");
            }
            return RetryDecision.doNotRetry("method " + method + " is not retryable");
        }

        // 4. Should never happen — context must have exactly one of response/failure
        return RetryDecision.doNotRetry("no response or failure in context");
    }

    private boolean isSslException(Throwable t) {
        return t instanceof SSLException;
    }

    private boolean isRetryableException(Throwable failure) {
        for (Class<? extends Throwable> retryableType : retryableExceptions) {
            if (retryableType.isInstance(failure)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builder for {@link DefaultRetryPolicy}.
     */
    public static final class Builder implements RetryPolicy.Builder {

        private static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES =
                Set.of(408, 429, 500, 502, 503, 504);
        private static final Set<String> DEFAULT_RETRYABLE_METHODS =
                Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");

        private Integer maxAttempts;
        private Set<Integer> retryableStatusCodes = DEFAULT_RETRYABLE_STATUS_CODES;
        private Set<String> retryableMethods = DEFAULT_RETRYABLE_METHODS;
        private Set<String> allowRetryForMethods = Set.of();
        private Set<Class<? extends Throwable>> retryableExceptions = Set.of();
        private DelayStrategy delayStrategy =
                DelayStrategy.exponentialWithJitter(Duration.ofMillis(100), Duration.ofSeconds(10));

        public Builder() {}

        @Override
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        @Override
        public Builder retryableStatusCodes(Set<Integer> codes) {
            this.retryableStatusCodes = Set.copyOf(codes);
            return this;
        }

        @Override
        public Builder retryableMethods(Set<String> methods) {
            this.retryableMethods = Set.copyOf(methods);
            return this;
        }

        @Override
        public Builder allowRetryForMethods(Set<String> methods) {
            this.allowRetryForMethods = Set.copyOf(methods);
            return this;
        }

        @Override
        public Builder retryableExceptions(Set<Class<? extends Throwable>> types) {
            this.retryableExceptions = Set.copyOf(types);
            return this;
        }

        @Override
        public Builder delayStrategy(DelayStrategy strategy) {
            this.delayStrategy = strategy;
            return this;
        }

        @Override
        public RetryPolicy build() {
            if (maxAttempts == null) {
                throw new IllegalStateException("maxAttempts must be set");
            }
            return new DefaultRetryPolicy(this);
        }
    }
}
