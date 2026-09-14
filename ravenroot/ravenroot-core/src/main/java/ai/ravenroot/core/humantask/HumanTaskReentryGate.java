package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.DurableHumanTask;

/**
 * Durable admission decision applied immediately before a settled Human Task re-enters execution.
 *
 * <p>The task settlement itself is durable custody and is never refused by this gate. Returning
 * {@code false} leaves its handler trigger pending so a later recovery sweep can dispatch it. This
 * distinction is what lets graph Pause retain a response without executing it, while graph Drain
 * continues accepted work normally.</p>
 */
@FunctionalInterface
public interface HumanTaskReentryGate {

    /** Compatibility policy for runtimes without a graph lifecycle authority. */
    HumanTaskReentryGate OPEN = task -> true;

    /**
     * Whether this terminal task may begin its continuation now.
     *
     * @param task durably settled task whose handler trigger is pending
     * @return {@code true} to dispatch, {@code false} to retain the trigger
     */
    boolean admits(DurableHumanTask task);
}
