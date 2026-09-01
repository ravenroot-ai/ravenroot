package ai.ravenroot.api.security;

/**
 * Decides whether a tool invocation is permitted.
 *
 * <h2>Implementations must be safe for concurrent use (ADR 0024 §3)</h2>
 * <p>One policy is consulted by every node that invokes a tool, and those consultations happen concurrently. A policy that memoises decisions must do so safely: a decision function that races is an authorisation decision that races.</p>
 *
 * <p>This is stated rather than newly imposed. Nothing here was ever documented as single-threaded;
 * it was true by accident, because one logical graph node was backed by one actor and an actor
 * handles one message at a time. ADR 0024 removes that serialisation deliberately, so an
 * implementation that relied on it is now racy. The implementations in this repository are already
 * safe; a third-party one never had a rule to follow, so here it is.</p>
 */
@FunctionalInterface
public interface ToolPolicy {
/**
 * Evaluates whether one proposed tool invocation may proceed.
 * @param invocation identity, execution, node, tool, and arguments being evaluated
 * @return an allow, deny, or approval-required decision; never {@code null}
 */
    ToolDecision evaluate(ToolInvocation invocation);

/**
 * Produces the fail-closed policy used when no trusted evaluator was configured.
 * @return a policy that denies every invocation with a stable reason
 */
    static ToolPolicy denyAll() {
        return invocation -> new ToolDecision(ToolDecision.Disposition.DENY,
                "Tool execution is disabled by default", "");
    }
}
