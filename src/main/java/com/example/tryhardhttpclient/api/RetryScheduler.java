package com.example.tryhardhttpclient.api;

import java.time.Duration;

/**
 * Schedules a {@link Runnable} to be executed after a given delay.
 *
 * <p>Implementations must be thread-safe. The default implementation is a JVM-scoped singleton
 * backed by a single daemon-threaded {@link java.util.concurrent.ScheduledExecutorService}.
 */
public interface RetryScheduler {

    /**
     * Schedules {@code task} to run after {@code delay}.
     *
     * @param task  the task to run
     * @param delay the delay before execution; {@link Duration#ZERO} means "as soon as possible"
     * @return a {@link ScheduledTask} handle that can be used to cancel the pending execution
     */
    ScheduledTask schedule(Runnable task, Duration delay);
}
