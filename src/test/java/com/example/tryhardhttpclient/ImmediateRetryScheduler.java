package com.example.tryhardhttpclient;

import com.example.tryhardhttpclient.api.RetryScheduler;
import com.example.tryhardhttpclient.api.ScheduledTask;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RetryScheduler} test double that runs tasks immediately on the calling thread.
 *
 * <p>This allows synchronous testing of retry logic without real timers.
 */
public final class ImmediateRetryScheduler implements RetryScheduler {

    private final AtomicInteger scheduleCount = new AtomicInteger(0);

    @Override
    public ScheduledTask schedule(Runnable task, Duration delay) {
        scheduleCount.incrementAndGet();
        task.run();
        // Task has already run; cancel is a no-op.
        return () -> false;
    }

    /** Returns how many times {@link #schedule} was called. */
    public int scheduleCount() {
        return scheduleCount.get();
    }
}
