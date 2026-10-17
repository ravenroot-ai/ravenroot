package ai.ravenroot.api.persistence;

import java.util.List;

/** Frozen participant and compensation contract for one authored logical step.
 * @param stepId logical step identity
 * @param nodeId forward graph node identity
 * @param participantContract trusted participant protocol name
 * @param compensationNodeId bound compensation node, or null
 * @param dependencies logical predecessor step identities
 * @param irreversible whether compensation is explicitly impossible
 * @param businessCompletionRequired whether broker acceptance alone is insufficient
 */
public record SagaStepDefinition(String stepId, String nodeId, String participantContract,
                                 String compensationNodeId, List<String> dependencies,
                                 boolean irreversible, boolean businessCompletionRequired) {
    /** Validates and defensively snapshots the durable value. */
    public SagaStepDefinition {
        stepId = token(stepId, "stepId");
        nodeId = token(nodeId, "nodeId");
        participantContract = token(participantContract, "participantContract");
        compensationNodeId = compensationNodeId == null || compensationNodeId.isBlank()
                ? null : token(compensationNodeId, "compensationNodeId");
        dependencies = (dependencies == null ? List.<String>of() : dependencies).stream().sorted().toList();
        if (dependencies.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("saga dependencies cannot be blank");
        }
        if (dependencies.size() != dependencies.stream().distinct().count()) {
            throw new IllegalArgumentException("saga dependencies cannot contain duplicates");
        }
        if (!irreversible && compensationNodeId == null && !"pure".equals(participantContract)) {
            throw new IllegalArgumentException("an effectful saga step requires compensation or irreversible=true");
        }
    }

    private static String token(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(name + " must be non-blank and at most 200 characters");
        }
        return value;
    }
}
