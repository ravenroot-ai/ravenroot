package ai.ravenroot.api.execution;

import ai.ravenroot.api.security.SecurityContext;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One delivery to one node.
 *
 * <h2>Identity is a typed component, not an attribute (SEC-07)</h2>
 * <p>{@code security} is the tenant and principal established at ingress. It is a <strong>record
 * component</strong> rather than an entry in {@link #attributes()}, and that is the whole mechanism
 * by which GraphML and payloads cannot overwrite it.</p>
 *
 * <p>The reasoning is structural rather than defensive. {@code attributes} is a caller-shaped map
 * that flows node to node from {@link NodeResult#attributes()} — that is, from <em>whatever the
 * previous node returned</em> — and fan-in merges those maps across branches. Any identity placed
 * there would be writable by the first graph-authored node that runs, and a filter guarding it would
 * have to be repeated at every hop and every merge, where one omission silently reopens the hole.</p>
 *
 * <p>By contrast {@link NodeResult} has <strong>no</strong> identity component at all, so a node has
 * no <em>syntax</em> with which to propose a different one. {@link #next(UUID, UUID, String, Object)}
 * copies {@code security} forward unchanged alongside the process and traversal identifiers. There is
 * therefore nothing to filter and nothing to forget: non-overridability is a property of the types,
 * not of anyone's discipline in maintaining a guard.</p>
 * @param security ingress-established tenant and principal; it is copied unchanged across routing
 * @param processInstanceId the durable process instance containing this delivery
 * @param traversalId the traversal to which the invocation belongs
 * @param invocationId the unique graph-node invocation represented by this message
 * @param attemptId the particular execution attempt of that invocation
 * @param parentInvocationIds immutable IDs of upstream invocations that caused this delivery
 * @param nodeId the graph node selected as the receiver
 * @param payload the data supplied to the node behavior
 * @param attributes immutable upstream attributes available to the node
 * @param command the processing directive selected for the target node
 */
public record NodeMessage(SecurityContext security, UUID processInstanceId, UUID traversalId,
                          UUID invocationId, UUID attemptId, Set<UUID> parentInvocationIds,
                          String nodeId, Object payload, Map<String, Object> attributes,
                          NodeCommand command) {
/**
 * Enforces immutable identity and routing invariants before a delivery enters the engine.
 */
    public NodeMessage {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        if (parentInvocationIds != null && parentInvocationIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("parentInvocationIds cannot contain null");
        }
        parentInvocationIds = Set.copyOf(parentInvocationIds == null ? Set.of() : parentInvocationIds);
        if (parentInvocationIds.contains(invocationId)) {
            throw new IllegalArgumentException("an invocation cannot be its own parent");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId cannot be blank");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        command = command == null ? NodeCommand.PROCESS : command;
    }

/**
 * Compatibility constructor for callers that only carried execution and invocation identity.
 * @param security ingress-established identity retained by the delivery
 * @param executionId legacy identifier used for both process and traversal IDs
 * @param invocationId identity of the target invocation
 * @param nodeId graph node that receives the message
 * @param payload data to give that node
 * @param attributes immutable upstream attributes
 */
    public NodeMessage(SecurityContext security, UUID executionId, UUID invocationId, String nodeId,
                       Object payload, Map<String, Object> attributes) {
        this(security, executionId, executionId, invocationId, invocationId, Set.of(), nodeId, payload, attributes,
                NodeCommand.PROCESS);
    }

/**
 * Compatibility constructor for ordinary processing with no parent fan-in and the PROCESS directive.
 * @param security ingress-established identity retained by the delivery
 * @param processInstanceId durable process containing the target invocation
 * @param traversalId traversal carrying the message
 * @param invocationId identity of the target invocation
 * @param attemptId execution attempt for that invocation
 * @param nodeId graph node that receives the message
 * @param payload data to give that node
 * @param attributes immutable upstream attributes
 */
    public NodeMessage(SecurityContext security, UUID processInstanceId, UUID traversalId, UUID invocationId,
                       UUID attemptId, String nodeId, Object payload, Map<String, Object> attributes) {
        this(security, processInstanceId, traversalId, invocationId, attemptId, Set.of(), nodeId, payload,
                attributes, NodeCommand.PROCESS);
    }

/**
 * Compatibility constructor preserving the pre-command canonical descriptor.
 * @param security ingress-established identity retained by the delivery
 * @param processInstanceId durable process containing the target invocation
 * @param traversalId traversal carrying the message
 * @param invocationId identity of the target invocation
 * @param attemptId execution attempt for that invocation
 * @param parentInvocationIds upstream invocations represented by a fan-in delivery
 * @param nodeId graph node that receives the message
 * @param payload data to give that node
 * @param attributes immutable upstream attributes
 */
    public NodeMessage(SecurityContext security, UUID processInstanceId, UUID traversalId, UUID invocationId,
                       UUID attemptId, Set<UUID> parentInvocationIds, String nodeId, Object payload,
                       Map<String, Object> attributes) {
        this(security, processInstanceId, traversalId, invocationId, attemptId, parentInvocationIds, nodeId,
                payload, attributes, NodeCommand.PROCESS);
    }

/**
 * Compatibility alias: a legacy execution identifies one traversal.
 * @return the traversal ID, retained as a legacy execution identifier
 */
    public UUID executionId() {
        return traversalId;
    }

/**
 * The tenant that owns this work. Convenience for the common scoping case.
 * @return the tenant established at ingress
 */
    public String tenantId() {
        return security.tenantId();
    }

    /**
     * Routing must supply fresh invocation and attempt IDs; an invocation never changes node.
     *
     * <p>{@code security} is carried forward verbatim and is never sourced from the {@link NodeResult}
     * that produced {@code nextPayload}. A node influences payload, outcome and attributes; it does
     * not influence who it is running as.</p>
 * @param nextInvocationId fresh identity for the downstream invocation
 * @param nextAttemptId fresh identity for that invocation's first attempt
 * @param nextNodeId graph node selected by routing
 * @param nextPayload data forwarded to that node
 * @return a downstream delivery retaining this delivery's trusted identity and attributes
     */
    public NodeMessage next(UUID nextInvocationId, UUID nextAttemptId, String nextNodeId, Object nextPayload) {
        return new NodeMessage(security, processInstanceId, traversalId, nextInvocationId, nextAttemptId,
                Set.of(invocationId), nextNodeId, nextPayload, attributes, command);
    }

/**
 * Routes this delivery under an explicit target command.
 * @param nextInvocationId fresh identity for the downstream invocation
 * @param nextAttemptId fresh identity for that invocation's first attempt
 * @param nextNodeId graph node selected by routing
 * @param nextPayload data forwarded to that node
 * @param nextCommand the target-specific processing directive
 * @return a downstream delivery retaining this delivery's trusted identity and attributes
 */
    public NodeMessage next(UUID nextInvocationId, UUID nextAttemptId, String nextNodeId, Object nextPayload,
                            NodeCommand nextCommand) {
        return new NodeMessage(security, processInstanceId, traversalId, nextInvocationId, nextAttemptId,
                Set.of(invocationId), nextNodeId, nextPayload, attributes, nextCommand);
    }
}
