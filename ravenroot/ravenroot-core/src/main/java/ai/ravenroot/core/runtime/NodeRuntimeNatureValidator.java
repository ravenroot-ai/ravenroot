package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Fail-closed validation of every node's declared runtime nature, before any actor or source exists
 * (ADR 0024 §2).
 *
 * <h2>Unconditional, and that is the whole reason it is a separate class</h2>
 * <p>{@link BehaviorPropertySchema} returns early for non-behavior nodes and for behaviors absent from
 * the catalog. Those early returns are correct for the behavior properties it owns: a {@code START}
 * node has no behavior properties, and what happens to an unknown behavior is a separate escalated
 * question. They would be a hole here. {@code runtime.nature} is not a behavior property — it is a
 * platform-owned declaration that decides which lifecycle a node gets — so a {@code START} node or an
 * uncatalogued behavior carrying {@code runtime.nature=AUTHORITY} must be refused, not skipped. This
 * validator therefore shares no early return with that class and consults no unknown-behavior mode:
 * that mode governs whether an unknown behavior may <em>execute</em>, never whether it may
 * <em>declare a privilege</em>.</p>
 *
 * <h2>Declared, never inferred</h2>
 * <p>Nothing here reads topology. Edges, predecessors, successors and reachability are not consulted
 * and must not become consulted: ADR 0024 rejects inferring lifecycle from graph shape by name, and a
 * later change that derived a nature from an edge would be building the rejected alternative inside
 * the class written to prevent it.</p>
 *
 * <h2>Where it runs</h2>
 * <p>From the {@link GraphRunner} constructor, immediately after {@link BehaviorPropertySchema} and
 * before the spawn loop — so before a single actor exists, and, on the deployment path, before
 * {@code DefaultGraphDeployment} starts any inbound source. Both the deployment start path and the
 * one-shot submission path build a runner, so one call site covers both; a graph that names a
 * lifecycle the runtime cannot honour is refused as a submission rather than discovered mid-flight.
 * The deployment's own rollback then unwinds the domain it had already opened.</p>
 *
 * <h2>The residency refusal is temporary by construction</h2>
 * <p>{@link NodeRuntimeNature#requiresUnimplementedResidency()} is the single definition behind the
 * {@code AUTHORITY}/{@code KEYED} refusal. Implementing those lifecycles empties that predicate, and
 * the tests asserting the refusal invert visibly rather than being quietly deleted. Until then,
 * accepting the declaration would mean handing an
 * {@code AUTHORITY} node the per-pod resident actor ADR 0024 §2 forbids, with nothing anywhere
 * reporting that the guarantee was not delivered.</p>
 */
public final class NodeRuntimeNatureValidator {

    private final BehaviorRegistry behaviors;

    public NodeRuntimeNatureValidator(BehaviorRegistry behaviors) {
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
    }

    /** Validates every node in {@code graph}, throwing on the first violation. */
    public void validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        for (GraphNode node : graph.nodes()) {
            validateNode(node);
        }
    }

    private void validateNode(GraphNode node) {
        boolean declared = NodeRuntimeNatureProperty.declaredBy(node.properties());
        NodeTypeDescriptor descriptor = node.kind() == NodeKind.BEHAVIOR
                ? behaviors.descriptor(node.behavior()).orElse(null)
                : null;

        if (declared && node.kind() != NodeKind.BEHAVIOR) {
            throw refuse(NodeRuntimeNatureException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE, node, null, null);
        }
        if (declared && descriptor == null) {
            throw refuse(NodeRuntimeNatureException.Reason.DECLARED_BY_UNCATALOGUED_BEHAVIOR, node, null, null);
        }

        if (declared) {
            Object raw = node.properties().get(NodeRuntimeNatureProperty.NAME);
            Optional<NodeRuntimeNature> parsed = NodeRuntimeNature.parse(raw);
            if (parsed.isEmpty()) {
                throw refuse(NodeRuntimeNatureException.Reason.UNKNOWN_NATURE, node, descriptor, raw);
            }
            if (!descriptor.effectiveAllowedNatures().contains(parsed.get())) {
                // The privilege boundary. A descriptor that says nothing allows exactly its effective
                // default, so a legacy catalog entry cannot be talked into an authority by a graph.
                throw refuse(NodeRuntimeNatureException.Reason.NATURE_NOT_PERMITTED, node, descriptor, raw);
            }
        }

        NodeRuntimeNature effective =
                NodeRuntimeNatureProperty.effectiveNature(descriptor, node.properties());
        if (effective.requiresUnimplementedResidency()) {
            throw refuse(NodeRuntimeNatureException.Reason.RESIDENCY_NOT_IMPLEMENTED, node, descriptor,
                    effective.name());
        }
    }

    /**
     * Builds the refusal. Every value derived from the submitted graph goes to the diagnostic map and
     * none of it reaches the public message — see {@link NodeRuntimeNatureException}.
     */
    private static NodeRuntimeNatureException refuse(NodeRuntimeNatureException.Reason reason,
                                                     GraphNode node, NodeTypeDescriptor descriptor,
                                                     Object declaredValue) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodeId", node.id());
        detail.put("nodeKind", node.kind().name());
        if (node.behavior() != null) {
            detail.put("behavior", node.behavior());
        }
        if (declaredValue != null) {
            detail.put("declaredNature", declaredValue.toString());
        }
        if (descriptor != null) {
            detail.put("defaultNature", descriptor.effectiveDefaultNature().name());
            detail.put("permittedNatures", new TreeSet<>(descriptor.allowedNatureIdentifiers()).toString());
        }
        return new NodeRuntimeNatureException(reason, detail);
    }
}
