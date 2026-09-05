package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;

import java.util.Optional;
import java.util.List;

/** Optional bridge that makes approval lifecycle and economic accounting one store commit. */
public interface ToolApprovalBudgetHooks {
    Optional<AgentBudgetOperation> hold(ExecutionKey key, ToolApprovalRegistration request);
    List<AgentBudgetOperation> dispatch(DurableToolApproval approval);
    Optional<AgentBudgetOperation> release(DurableToolApproval approval);
    Optional<AgentBudgetOperation> settle(DurableToolApproval approval);
    Optional<AgentBudgetOperation> indeterminate(DurableToolApproval approval);

    static ToolApprovalBudgetHooks none() {
        return new ToolApprovalBudgetHooks() {
            @Override public Optional<AgentBudgetOperation> hold(ExecutionKey key,
                                                                 ToolApprovalRegistration request) {
                return Optional.empty();
            }
            @Override public List<AgentBudgetOperation> dispatch(DurableToolApproval approval) {
                return List.of();
            }
            @Override public Optional<AgentBudgetOperation> release(DurableToolApproval approval) {
                return Optional.empty();
            }
            @Override public Optional<AgentBudgetOperation> settle(DurableToolApproval approval) {
                return Optional.empty();
            }
            @Override public Optional<AgentBudgetOperation> indeterminate(DurableToolApproval approval) {
                return Optional.empty();
            }
        };
    }
}
