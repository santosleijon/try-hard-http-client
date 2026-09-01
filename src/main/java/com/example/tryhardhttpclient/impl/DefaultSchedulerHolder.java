package com.example.tryhardhttpclient.impl;

import com.example.tryhardhttpclient.api.RetryScheduler;
import com.example.tryhardhttpclient.api.ScheduledTask;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Holds the JVM-scoped default {@link RetryScheduler} singleton.
 *
 * <p>Uses the initialization-on-demand holder pattern to ensure thread-safe lazy initialization
 * without explicit synchronization.
 */
final class DefaultSchedulerHolder {

    private DefaultSchedulerHolder() {}

    /**
     * The shared default {@link RetryScheduler} backed by a single daemon-thread executor.
     */
    static final RetryScheduler INSTANCE = Holder.INSTANCE;

    private static final class Holder {
        private static final RetryScheduler INSTANCE = createScheduler();

        private static RetryScheduler createScheduler() {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "try-hard-retry-scheduler");
                thread.setDaemon(true);
                return thread;
            });

            return (task, delay) -> {
                long delayNanos = delay.isNegative() ? 0L : delay.toNanos();
                ScheduledFuture<?> future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
                return new ScheduledTaskImpl(future);
            };
        }
    }

    private static final class ScheduledTaskImpl implements ScheduledTask {
        private final ScheduledFuture<?> future;

        ScheduledTaskImpl(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public boolean cancel() {
            // Do not interrupt a running task
            return future.cancel(false);
        }
    }
}
