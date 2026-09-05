package ai.ravenroot.core.recovery;

import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.persistence.ExecutionKey;
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
 * <h2>Two lookups, because one of them cannot be answered after a restart</h2>
 * <p>{@link #fromGraph} builds a snapshot while a graph is in memory, which is all a live runtime
 * needs. A recovery sweep in a fresh process has no such graph: it holds an instance and the
 * write-once pin that instance was created with, and the document behind that pin now lives in a
 * {@link ai.ravenroot.api.persistence.GraphDefinitionStore}. {@link #declaredFor(ExecutionKey, String)}
 * is the lookup that can reach it, and {@link ai.ravenroot.core.recovery.PinnedGraphRecoveryAuthority}
 * is the implementation that does. The single-argument form remains the whole interface for a
 * snapshot source, and the default below keeps every existing source — including a lambda — working
 * unchanged rather than silently answering a question it has no instance to answer.</p>
 */
@FunctionalInterface
public interface RepeatabilityDeclarations {

    /**
     * What {@code nodeId} declared. Implementations must never throw: a source that cannot answer
     * has, by that fact, not declared anything, and must say {@link AttemptRepeatability#UNDECLARED}
     * rather than propagate a failure into a sweep that is trying to be safe.
     */
    AttemptRepeatability declaredFor(String nodeId);

    /**
     * What {@code nodeId} declared in the document {@code key}'s execution is pinned to.
     *
     * <p>The default ignores the instance and defers to {@link #declaredFor(String)}, which is
     * exactly right for a snapshot of one graph: it is already the graph in question, so the instance
     * adds nothing. A source that resolves the document per instance overrides this. Implementations
     * must never throw, for the same reason the single-argument form must not.</p>
     *
     * @param key    tenant-scoped instance whose pinned document the declaration is read from.
     * @param nodeId graph node whose declaration is requested.
     * @return the declaration, or {@link AttemptRepeatability#UNDECLARED} when none can be read.
     */
    default AttemptRepeatability declaredFor(ExecutionKey key, String nodeId) {
        return declaredFor(nodeId);
    }

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
