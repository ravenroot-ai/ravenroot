package ai.ravenroot.core.recovery;

import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What each node of a graph declared about repeating an effect of unknown outcome (ADR 0022 §2).
 *
 * <p>Every branch fails closed, and there is no "default to repeatable" anywhere in this type or any
 * source it builds: a default the other way would convert the entire posture from fail-closed to
 * fail-open, invisibly, because the result is indistinguishable from an author who chose it.</p>
 *
 * <h2>The catalog is the only route in</h2>
 * <p>{@link #fromGraph} resolves each node through its behavior's {@link NodeTypeDescriptor} and then
 * through {@link RecoveryRepeatabilityProperty#read(NodeTypeDescriptor, Map)}, which takes the
 * descriptor as a parameter and cannot be called without one. A node whose descriptor never declared
 * the property is {@link AttemptRepeatability#UNDECLARED} however its GraphML reads — a stray
 * {@code recovery.repeatable} key on a node of an undeclaring type is inert rather than quietly
 * authoritative. Nothing here reads node properties by string.</p>
 *
 * <h2>What is still deferred, and it is not this</h2>
 * <p>A snapshot is built while the graph is in memory. Rebuilding one <em>after a restart</em> needs
 * the graph, and graph bytes are not stored — {@code GraphVersionPin} holds a hash and the definition
 * store does not exist. So a recovery sweep in a fresh process still has no snapshot to consult and
 * parks, which is the safe direction and the same residual limitation the rest of recovery carries.</p>
 */
@FunctionalInterface
public interface RepeatabilityDeclarations {

    /**
     * What {@code nodeId} declared. Implementations must never throw: a source that cannot answer
     * has, by that fact, not declared anything, and must say {@link AttemptRepeatability#UNDECLARED}
     * rather than propagate a failure into a sweep that is trying to be safe.
     */
    AttemptRepeatability declaredFor(String nodeId);

    /** Every node is undeclared, therefore every ambiguous attempt parks. */
    RepeatabilityDeclarations NONE_DECLARED = nodeId -> AttemptRepeatability.UNDECLARED;

    /**
     * A snapshot of one graph, resolved node by node through the catalog.
     *
     * @param nodes      the graph's nodes, carrying their per-instance property values
     * @param descriptors the catalog lookup, by behavior name — normally
     *                   {@code BehaviorRegistry::descriptor}
     */
    static RepeatabilityDeclarations fromGraph(Iterable<GraphNode> nodes,
                                               Function<String, Optional<NodeTypeDescriptor>> descriptors) {
        var resolved = new LinkedHashMap<String, AttemptRepeatability>();
        if (nodes != null && descriptors != null) {
            for (GraphNode node : nodes) {
                if (node == null || node.kind() != NodeKind.BEHAVIOR) {
                    continue;
                }
                NodeTypeDescriptor descriptor = descriptors.apply(node.behavior()).orElse(null);
                resolved.put(node.id(), RecoveryRepeatabilityProperty.read(descriptor, node.properties()));
            }
        }
        Map<String, AttemptRepeatability> snapshot = Map.copyOf(resolved);
        return nodeId -> snapshot.getOrDefault(nodeId, AttemptRepeatability.UNDECLARED);
    }
}
