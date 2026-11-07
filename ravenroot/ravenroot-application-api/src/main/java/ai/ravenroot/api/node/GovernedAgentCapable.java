package ai.ravenroot.api.node;

import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.runner.AgentCommand;
import ai.ravenroot.api.runner.AgentDefinition;
import ai.ravenroot.api.runner.RunnerPolicy;

/** Trusted AI-package composition of a named Agent without filesystem or runner authority. */
public interface GovernedAgentCapable extends NodeBehavior {
    /**
     * Captures the immutable approved definition through the ordinary managed package services.
     * Graph properties are deliberately absent: they cannot override governed Agent authority.
     * @param nodeId exact graph node binding
     * @param services manifest-bound managed services, never direct credentials or network access
     * @param definition immutable tenant-scoped approved definition
     * @param command exact approved application command
     * @param authority intersection of deployment, definition and command ceilings
     * @return bounded ordinary Agent action with direct governed outcome and payload
     */
    NodeAction createGoverned(String nodeId, NodePackageServices services, AgentDefinition definition,
                             AgentCommand command, RunnerPolicy authority);
    /**
     * Composes approved Agent authority with an explicit credential selection.
     * The mode selects billing authority only; it cannot replace definition instructions, tools or budgets.
     * @param nodeId exact graph node binding
     * @param services manifest-bound managed services
     * @param definition immutable approved source-tenant definition
     * @param command exact approved command
     * @param authority intersection of deployment, definition and command ceilings
     * @param credentialMode explicit system or personal credential selection from the graph node
     * @return bounded action preserving the approved definition's authority
     * @throws UnsupportedOperationException when the package does not support the selected mode
     */
    default NodeAction createGoverned(String nodeId, NodePackageServices services, AgentDefinition definition,
            AgentCommand command, RunnerPolicy authority, String credentialMode) {
        if (!"system".equals(credentialMode)) throw new UnsupportedOperationException("Personal governed Agent unavailable");
        return createGoverned(nodeId, services, definition, command, authority);
    }
}
