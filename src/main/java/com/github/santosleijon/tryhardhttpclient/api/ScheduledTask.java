package com.github.santosleijon.tryhardhttpclient.api;

/**
 * A handle to a task that has been submitted to a {@link RetryScheduler}.
 * Callers may attempt to cancel it before it fires.
 */
public interface ScheduledTask {

    /**
     * Attempts to cancel the scheduled task.
     *
     * @return {@code true} if the task was cancelled before it ran,
     *         {@code false} if the task had already run or was already cancelled.
     */
    boolean cancel();
}
