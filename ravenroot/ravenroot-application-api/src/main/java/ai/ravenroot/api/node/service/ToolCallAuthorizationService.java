package ai.ravenroot.api.node.service;

import ai.ravenroot.api.execution.NodeMessage;

/**
 * Server-owned reference monitor for model-requested tool calls.
 *
 * <p>The service accepts untrusted JSON bytes, parses and canonicalizes them under its own bounds,
 * evaluates the trusted policy immediately before the effect, and records the decision. It exposes
 * neither the policy nor an authority-bearing callback to the package.</p>
 */
@FunctionalInterface
public interface ToolCallAuthorizationService {
    /**
     * Decides one requested call and, when allowed, returns the canonical arguments that were
     * evaluated. Invalid arguments are a denied decision and never reach the policy or an effect.
     *
     * @param message trusted invocation identity delivered by the runtime
     * @param tool canonical tool name selected from the immutable tool inventory
     * @param argumentsJson untrusted model-authored JSON argument document
     * @return server-minted, audited decision; never {@code null}
     */
    ToolCallAuthorization authorize(NodeMessage message, String tool, byte[] argumentsJson);

    /**
     * Constructs the compatibility default: no configured service means no authority.
     * @return a stateless service that denies every proposed call
     */
    static ToolCallAuthorizationService unavailable() {
        return (message, tool, argumentsJson) -> ToolCallAuthorization.unavailable();
    }
}
