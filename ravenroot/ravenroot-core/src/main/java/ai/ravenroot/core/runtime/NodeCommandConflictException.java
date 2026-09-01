package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeCommand;

/** Two matching outgoing edges selected incompatible commands for one target delivery. */
public final class NodeCommandConflictException extends IllegalStateException {
    private final String targetId;
    private final NodeCommand first;
    private final NodeCommand conflicting;

    public NodeCommandConflictException(String targetId, NodeCommand first, NodeCommand conflicting) {
        super("Matching edges to target '" + targetId + "' declare conflicting node commands: '"
                + first + "' and '" + conflicting + "'");
        this.targetId = targetId;
        this.first = first;
        this.conflicting = conflicting;
    }

    public String targetId() { return targetId; }
    public NodeCommand first() { return first; }
    public NodeCommand conflicting() { return conflicting; }
}
