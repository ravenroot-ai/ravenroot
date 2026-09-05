package ai.ravenroot.api.node;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.Set;
import java.util.Optional;

/**
 * One node type contributed by a {@link NodePackage}.
 *
 * <h2>The descriptor is the trust anchor, not documentation (SEC-09)</h2>
 * <p>{@link #descriptor()} is what makes a third-party node a first-class catalog entry rather than
 * an opaque handler. The runtime reads it to decide which properties the node has, their types,
 * which are required, which values are permitted, and which capabilities it declares — and it
 * validates every submitted graph against it <em>before</em> any node is spawned. A graph supplies
 * property values; it cannot introduce a property, change a property's type, or claim a capability,
 * because none of that is ever read from graph content.</p>
 *
 * <p>So a behavior that returns a thin descriptor is not merely under-documented: it opts its own
 * nodes out of schema validation, and its handler then receives whatever the graph happened to
 * write. Declare every property the behavior reads.</p>
 *
 * <h2>Capabilities are declarations with consequences</h2>
 * <p>{@link NodeTypeDescriptor#capabilities()} is consulted by the runtime, not just displayed. A
 * behavior declaring a capability the runtime treats as generative has its output marked with a
 * machine-readable synthetic-provenance marker. Declare what is true; a capability is an assertion
 * about the world, and an inaccurate one is worse than a missing one.</p>
 */
public interface NodeBehavior {

    /**
     * Operator services this behavior cannot function without.
     *
     * <p>The default is empty for binary compatibility with SDK /1 packages. Declaring a service
     * makes absence a package-activation failure; accessing an undeclared service remains possible
     * only through a deny-only view and fails at invocation.</p>
     * @return immutable set of required grants; empty preserves SDK /1 behavior
     */
    default Set<NodePackageCapability> requiredServices() {
        return Set.of();
    }

    /**
     * This behavior's catalog entry.
     *
     * <p>Must be stable across calls: the runtime may read it at registration, at graph validation
     * and when serving the catalog to an editor, and a descriptor that varies between those points
     * describes a node type that does not exist. Build it from constants rather than from mutable
     * state.</p>
     * @return stable descriptor used for registration, graph validation, and catalog publication
     */
    NodeTypeDescriptor descriptor();

    /**
     * Builds the action for one graph node.
     *
     * <p>Called once per node, so configuration parsing and any derived state belong here rather
     * than in the returned action. Work done here happens while the graph is being built; work done
     * in the action happens on every message.</p>
     *
     * <p><strong>Once per node is not once per invocation, and the action it returns is invoked
     * concurrently (ADR 0024 §3).</strong> The runtime creates a runtime instance per logical
     * invocation, so several traversals reaching one node execute through the single action this
     * method returned, at the same time, on different threads. The action must therefore be
     * re-entrant. This is the reason the split matters rather than merely being tidy: everything
     * derived here is shared, so it should be immutable, and anything that cannot be immutable must
     * carry its own synchronisation. See {@link NodeAction} for the full statement and for what
     * changed.</p>
     *
     * <p>Throwing here refuses the whole graph. That is the right response to a node this behavior
     * cannot ever serve, and the wrong response to a condition that might resolve — a dependency
     * that is merely not configured yet should produce an action that fails when reached, so the
     * graph still constructs and the failure is attributable to the node rather than to the
     * submission.</p>
     * @param configuration validated identity and property snapshot for one graph node
     * @return re-entrant action shared by concurrent traversals of that logical node
     */
    NodeAction create(NodeConfiguration configuration);

    /**
     * SDK /2 construction path for service-aware behaviors.
     *
     * <p>The default bridge deliberately calls the published SDK /1 method, preserving already
     * compiled packages. The runtime invokes this overload only for a package declaring SDK /2.</p>
     * @param configuration validated identity and property snapshot for one graph node
     * @param services package-scoped operator grants; legacy packages receive no extra authority
     * @return re-entrant action shared by concurrent traversals of that logical node
     */
    default NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        return create(configuration);
    }

    /**
     * Opt-in durable re-entry for a package-owned, versioned tool-call checkpoint.
     *
     * <p>The fail-closed default keeps existing packages source and binary compatible. A package
     * that opts in owns the complete decoder for its checkpoint; core never interprets package
     * state and graph content cannot nominate a decoder.</p>
     * @param configuration validated identity and property snapshot for one graph node
     * @param services package-scoped operator grants
     * @return optional trusted continuation action; empty refuses re-entry
     */
    default Optional<ToolCallContinuationAction> createToolCallContinuation(
            NodeConfiguration configuration, NodePackageServices services) {
        return Optional.empty();
    }
}
