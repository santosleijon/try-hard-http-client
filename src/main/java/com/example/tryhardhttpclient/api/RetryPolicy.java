package com.example.tryhardhttpclient.api;

import com.example.tryhardhttpclient.impl.DefaultRetryPolicy;

import java.util.Set;

/**
 * Decides whether and how long to delay a retry after a failed HTTP attempt.
 */
public interface RetryPolicy {

    /**
     * The total maximum number of attempts, including the initial attempt.
     *
     * @return maximum attempts; always &ge; 1
     */
    int maxAttempts();

    /**
     * Evaluates whether the request described by {@code context} should be retried.
     *
     * @param context the context of the attempt that just completed
     * @return the retry decision, including the delay if a retry is warranted
     */
    RetryDecision evaluate(RetryContext context);

    /**
     * Returns a new builder for constructing a {@link RetryPolicy}.
     *
     * @return a new {@link Builder}
     */
    static Builder builder() {
        return new DefaultRetryPolicy.Builder();
    }

    /**
     * Builder for {@link RetryPolicy}.
     */
    interface Builder {

        /**
         * Sets the total maximum number of attempts (including the initial attempt).
         * This field is mandatory; {@link #build()} will throw if it is not set.
         *
         * @param maxAttempts total attempts; must be &ge; 1
         * @return this builder
         */
        Builder maxAttempts(int maxAttempts);

        /**
         * Sets the HTTP status codes that are eligible for retry (default: 408, 429, 500, 502, 503, 504).
         *
         * @param codes the set of retryable status codes
         * @return this builder
         */
        Builder retryableStatusCodes(Set<Integer> codes);

        /**
         * Sets the HTTP methods that are considered idempotent and therefore retryable by default
         * (default: GET, HEAD, PUT, DELETE, OPTIONS).
         *
         * @param methods the set of retryable method names
         * @return this builder
         */
        Builder retryableMethods(Set<String> methods);

        /**
         * Opts in additional methods (e.g. POST, PATCH) for retry.
         * These are merged with the retryable methods set.
         *
         * @param methods additional methods to allow retrying
         * @return this builder
         */
        Builder allowRetryForMethods(Set<String> methods);

        /**
         * Sets exception types that are eligible for retry (default: empty — exceptions are not retried
         * without explicit opt-in).
         *
         * @param types exception classes; supertype matching is used
         * @return this builder
         */
        Builder retryableExceptions(Set<Class<? extends Throwable>> types);

        /**
         * Sets the delay strategy used to compute wait times between attempts
         * (default: exponential back-off with jitter, base 100 ms, cap 10 s).
         *
         * @param strategy the delay strategy
         * @return this builder
         */
        Builder delayStrategy(DelayStrategy strategy);

        /**
         * Builds the {@link RetryPolicy}.
         *
         * @return a new immutable {@link RetryPolicy}
         * @throws IllegalStateException if {@code maxAttempts} was not set
         */
        RetryPolicy build();
    }
}
