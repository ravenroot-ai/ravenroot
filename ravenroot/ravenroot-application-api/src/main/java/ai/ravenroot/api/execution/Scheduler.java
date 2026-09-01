package ai.ravenroot.api.execution;

import java.time.Duration;

/**
 * Defines the scheduler contract exposed to Ravenroot integrators.
 */
public interface Scheduler {
/**
 * Schedules one task without exposing the underlying runtime timer implementation.
 * @param delay non-negative delay before the task becomes eligible to run
 * @param task callback invoked once unless its returned handle is cancelled
 * @return a handle that reports and controls the scheduled callback
 */
    ScheduledTask schedule(Duration delay, Runnable task);
}
