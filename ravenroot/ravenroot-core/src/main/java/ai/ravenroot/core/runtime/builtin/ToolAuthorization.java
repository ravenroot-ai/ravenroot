package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;

import java.util.Map;

final class ToolAuthorization {
    private ToolAuthorization() {
    }

    static void requireAllowed(ToolPolicy policy, NodeMessage message, String tool, Map<String, Object> arguments) {
        // The identity comes from the delivered message, never from node properties or the payload:
        // NodeMessage.security() was fixed at ingress and carried forward by NodeMessage.next(...).
        ToolDecision decision = policy.evaluate(new ToolInvocation(message.security(), message.executionId(),
                message.nodeId(), tool, arguments));
        if (decision.disposition() == ToolDecision.Disposition.REQUIRE_APPROVAL) {
            throw new SecurityException("Tool requires approval before execution: " + tool
                    + (decision.approvalId().isBlank() ? "" : " (approval " + decision.approvalId() + ")"));
        }
        if (decision.disposition() != ToolDecision.Disposition.ALLOW) {
            throw new SecurityException(decision.reason().isBlank() ? "Tool execution denied: " + tool
                    : decision.reason());
        }
    }
}
