package com.github.santosleijon.tryhardhttpclient.api;

import java.time.Duration;
import java.util.Random;

/**
 * Computes the delay to wait before a given retry attempt.
 */
public interface DelayStrategy {

    /**
     * Returns the delay to wait before attempt number {@code attemptNumber}.
     *
     * @param attemptNumber 1-based attempt number (1 = delay before the second attempt,
     *                      i.e. after the first failure)
     * @return the delay; must not be negative
     */
    Duration delayFor(int attemptNumber);

    /**
     * Returns a {@link DelayStrategy} that uses capped exponential back-off with uniform jitter.
     *
     * <p>The formula is: {@code min(base * 2^(attemptNumber-1) + jitter, cap)}, where
     * {@code jitter} is a uniformly distributed random value in {@code [0, base)}.
     *
     * @param base the base delay (also the upper bound of the jitter range)
     * @param cap  the maximum delay that will ever be returned
     * @return a new {@link DelayStrategy} instance backed by {@link Random}
     * @throws IllegalArgumentException if {@code base} is zero or negative, or {@code cap < base}
     */
    static DelayStrategy exponentialWithJitter(Duration base, Duration cap) {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must be >= base");
        }
        final long baseNanos = base.toNanos();
        final long capNanos  = cap.toNanos();
        final Random random  = new Random();

        return attemptNumber -> {
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be >= 1");
            }

            // Exponential component: base * 2^(attemptNumber-1), guarded against overflow.
            int shift = attemptNumber - 1;
            long exponentialNanos;
            if (shift >= 63 || baseNanos > (Long.MAX_VALUE >> shift)) {
                exponentialNanos = capNanos;
            } else {
                exponentialNanos = baseNanos << shift;
            }

            // Jitter: uniform random value in [0, base).
            long jitterNanos = (long) (random.nextDouble() * baseNanos);

            // Sum, capped.
            long totalNanos = (exponentialNanos > capNanos - jitterNanos)
                    ? capNanos
                    : Math.min(exponentialNanos + jitterNanos, capNanos);

            return Duration.ofNanos(totalNanos);
        };
    }
}
