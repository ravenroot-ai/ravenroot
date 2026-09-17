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
}
