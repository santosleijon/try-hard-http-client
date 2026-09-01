package com.github.santosleijon.tryhardhttpclient;

import com.github.santosleijon.tryhardhttpclient.api.DelayStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DelayStrategy#exponentialWithJitter(Duration, Duration)}.
 *
 * <p>Because the returned strategy captures an internal {@link java.util.Random}, exact values
 * cannot be asserted. Each test instead verifies the mathematically guaranteed range:
 * <pre>
 *   delayFor(n) ∈ [ base·2^(n-1),  base·2^(n-1) + base )
 * </pre>
 * and that the result never exceeds {@code cap}.
 *
 * <p>Growth between consecutive attempts is also guaranteed without controlling jitter:
 * the maximum of attempt <em>n</em> ({@code base·2^(n-1) + base − 1 ns}) is always less than
 * the minimum of attempt <em>n+1</em> ({@code base·2^n}), so {@code delayFor(n) < delayFor(n+1)}
 * holds for every sample when the cap has not been reached.
 */
class DelayStrategyTest {

    private static final Duration BASE = Duration.ofMillis(100);
    private static final Duration CAP  = Duration.ofSeconds(10);

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Inclusive lower bound for attempt n (no jitter): base * 2^(n-1). */
    private static Duration minDelay(int n) {
        return BASE.multipliedBy(1L << (n - 1));
    }

    /** Exclusive upper bound for attempt n (full jitter): base * 2^(n-1) + base. */
    private static Duration maxDelayExclusive(int n) {
        return minDelay(n).plus(BASE);
    }

    private static void assertInRange(Duration delay, int attemptNumber) {
        Duration lo = minDelay(attemptNumber);
        Duration hi = maxDelayExclusive(attemptNumber);
        assertTrue(delay.compareTo(lo) >= 0,
                "attempt " + attemptNumber + ": " + delay + " < lower bound " + lo);
        assertTrue(delay.compareTo(hi) < 0,
                "attempt " + attemptNumber + ": " + delay + " >= upper bound " + hi);
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void firstAttempt_delayIsInExpectedRange() {
        // [100 ms, 200 ms)
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        assertInRange(s.delayFor(1), 1);
    }

    @Test
    void secondAttempt_delayIsInExpectedRange() {
        // [200 ms, 300 ms)
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        assertInRange(s.delayFor(2), 2);
    }

    @Test
    void thirdAttempt_delayIsInExpectedRange() {
        // [400 ms, 500 ms)
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        assertInRange(s.delayFor(3), 3);
    }

    @Test
    void consecutiveAttempts_delayStrictlyGrows_whenBelowCap() {
        // With a large cap the growth guarantee holds for every sample.
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, Duration.ofMinutes(1));

        Duration prev = s.delayFor(1);
        for (int n = 2; n <= 6; n++) {
            Duration next = s.delayFor(n);
            assertTrue(next.compareTo(prev) > 0,
                    "Expected delay to grow: attempt " + (n - 1) + " → " + n
                            + ", but " + next + " ≤ " + prev);
            prev = next;
        }
    }

    @Test
    void delay_isCappedAtMaximum() {
        Duration smallCap = Duration.ofMillis(250);
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, smallCap);

        // Attempt 3 would be [400 ms, 500 ms) without a cap — must be clamped to 250 ms.
        Duration delay = s.delayFor(3);
        assertEquals(smallCap, delay,
                "Delay for attempt 3 exceeded cap " + smallCap + ": " + delay);
    }

    @Test
    void cap_isNeverExceeded_acrossManyAttempts() {
        Duration cap = Duration.ofMillis(500);
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, cap);

        for (int n = 1; n <= 20; n++) {
            Duration delay = s.delayFor(n);
            assertTrue(delay.compareTo(cap) <= 0,
                    "Attempt " + n + ": delay " + delay + " exceeded cap " + cap);
        }
    }

    @Test
    void veryLargeAttemptNumber_returnsCap_withoutOverflow() {
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        Duration delay = s.delayFor(100);
        assertEquals(CAP, delay,
                "Attempt 100 should return cap due to overflow protection");
    }

    @Test
    void delayFor_throwsOnZeroAttemptNumber() {
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        assertThrows(IllegalArgumentException.class, () -> s.delayFor(0));
    }

    @Test
    void delayFor_throwsOnNegativeAttemptNumber() {
        DelayStrategy s = DelayStrategy.exponentialWithJitter(BASE, CAP);
        assertThrows(IllegalArgumentException.class, () -> s.delayFor(-1));
    }

    @Test
    void factory_throwsWhenBaseIsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> DelayStrategy.exponentialWithJitter(Duration.ZERO, CAP));
    }

    @Test
    void factory_throwsWhenBaseIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> DelayStrategy.exponentialWithJitter(Duration.ofMillis(-1), CAP));
    }

    @Test
    void factory_throwsWhenCapIsLessThanBase() {
        assertThrows(IllegalArgumentException.class,
                () -> DelayStrategy.exponentialWithJitter(Duration.ofSeconds(1), Duration.ofMillis(500)));
    }
}
