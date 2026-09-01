package ai.ravenroot.api.execution;

/**
 * Defines the scheduled task contract exposed to Ravenroot integrators.
 */
public interface ScheduledTask {
/**
 * Cancels the callback when it has not begun execution.
 * @return whether this invocation prevented the callback from running
 */
    boolean cancel();
}
