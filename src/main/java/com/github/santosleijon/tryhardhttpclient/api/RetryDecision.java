package com.github.santosleijon.tryhardhttpclient.api;

import java.time.Duration;

/**
 * The outcome of a {@link RetryPolicy#evaluate(RetryContext)} call.
 *
 * @param retry  whether the request should be retried
 * @param reason a human-readable explanation of the decision
 * @param delay  the delay to wait before the next attempt; {@code null} when {@code retry} is {@code false}
 */
public record RetryDecision(boolean retry, String reason, Duration delay) {

    /**
     * Returns a decision to retry after the specified delay.
     *
     * @param reason a human-readable explanation
     * @param delay  the delay to wait before the next attempt
     * @return a retry decision
     */
    public static RetryDecision retry(String reason, Duration delay) {
        return new RetryDecision(true, reason, delay);
    }

    /**
     * Returns a decision to not retry.
     *
     * @param reason a human-readable explanation
     * @return a do-not-retry decision
     */
    public static RetryDecision doNotRetry(String reason) {
        return new RetryDecision(false, reason, null);
    }
}
