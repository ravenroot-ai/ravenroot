package ai.ravenroot.api.runner;

import ai.ravenroot.api.execution.NodeCommand;

import java.util.Objects;
import java.util.Set;

/**
 * An approved command policy, separate from the graph's command selection and bounded payload.
 * Custom commands must be explicitly present in a definition. Reserved engine directives are never
 * redefined here; in particular {@code continue} remains an outcome and the compatibility spelling
 * of {@link NodeCommand#PROCESS}.
 * @param name application command name
 * @param readOnly whether the workspace and capabilities must be structurally read-only
 * @param policy command authority ceiling
 * @param outcomes admitted terminal outcome names for graph routing
 */
public record AgentCommand(String name, boolean readOnly, RunnerPolicy policy, Set<String> outcomes) {
    /** Standard development-cycle command vocabulary; extensions remain explicit policies. */
    public static final Set<String> STANDARD_NAMES = Set.of("plan", "read", "research", "review", "implement",
            "test", "remediate", "integrate", "resume", "summarize", "handoff");
    /** Stable common and development-cycle outcomes. */
    public static final Set<String> STANDARD_OUTCOMES = Set.of("completed", "needs-input", "needs-work",
            "blocked", "escalation", "answered", "inconclusive", "approved", "changes-requested",
            "passed", "failed", "flaky", "fixed", "not-fixed", "continue");
    private static final Set<String> READ_ONLY = Set.of("plan", "read", "research", "review", "summarize");

    /** Rejects ambiguous directives and writable policies for reserved read-only vocabulary. */
    public AgentCommand {
        name = NodeCommand.application(RunnerPolicy.identifier(name)).name();
        Objects.requireNonNull(policy, "policy");
        outcomes = RunnerPolicy.identifiers(outcomes);
        if (outcomes.isEmpty()) throw new IllegalArgumentException("command must declare outcomes");
        if (READ_ONLY.contains(name) && !readOnly) {
            throw new IllegalArgumentException("this command requires structural read-only authority");
        }
    }
}
