package com.github.santosleijon.tryhardhttpclient;

import com.github.santosleijon.tryhardhttpclient.api.RetryScheduler;
import com.github.santosleijon.tryhardhttpclient.api.ScheduledTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link RetryScheduler} test double that captures scheduled tasks without executing them.
 *
 * <p>Tests can inspect captured tasks and invoke them manually, enabling cancellation testing.
 */
public final class CapturingRetryScheduler implements RetryScheduler {

    public record CapturedTask(Runnable task, Duration delay, CancellableTask handle) {}

    private final List<CapturedTask> captured = new ArrayList<>();

    @Override
    public ScheduledTask schedule(Runnable task, Duration delay) {
        CancellableTask handle = new CancellableTask(task);
        captured.add(new CapturedTask(task, delay, handle));
        return handle;
    }

    /** Returns all tasks captured so far. */
    public List<CapturedTask> captured() {
        return List.copyOf(captured);
    }

    /** Returns the most recently captured task, or throws if none exist. */
    public CapturedTask lastCaptured() {
        if (captured.isEmpty()) {
            throw new AssertionError("No tasks have been scheduled yet");
        }
        return captured.get(captured.size() - 1);
    }

    /**
     * A {@link ScheduledTask} that can be cancelled and tracks its cancelled/run state.
     */
    public static final class CancellableTask implements ScheduledTask {
        private final Runnable task;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean ran = new AtomicBoolean(false);

        CancellableTask(Runnable task) {
            this.task = task;
        }

        /** Runs the task if it has not been cancelled. */
        public void run() {
            if (!cancelled.get()) {
                ran.set(true);
                task.run();
            }
        }

        @Override
        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        public boolean wasCancelled() {
            return cancelled.get();
        }

        public boolean wasRun() {
            return ran.get();
        }
    }
}
