package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionException;
import ai.ravenroot.api.application.GraphAdmissionFinding;
import ai.ravenroot.api.application.GraphAdmissionPhase;
import ai.ravenroot.api.application.GraphAdmissionPurpose;
import ai.ravenroot.api.application.GraphAdmissionReason;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphValidationException;
import ai.ravenroot.core.graph.NodeKind;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** One policy-independent graph-admission implementation for inspection and every start path. */
public final class GraphAdmissionValidator {
    private final BehaviorRegistry behaviors;
    private final GraphExecutionLimits limits;

    public GraphAdmissionValidator(BehaviorRegistry behaviors, GraphExecutionLimits limits) {
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Inspects exact submitted bytes and returns one deterministic primary finding at most. */
    public GraphSummary inspect(byte[] graphMl, GraphAdmissionPurpose purpose) {
        Objects.requireNonNull(graphMl, "graphMl");
        Objects.requireNonNull(purpose, "purpose");
        try (GraphManager manager = GraphManager.readGraphMl(new ByteArrayInputStream(graphMl), limits.graphMl())) {
            long starts = manager.query(g -> g.V().has(GraphManager.KIND, NodeKind.START.name()).count().next());
            long ends = manager.query(g -> g.V().has(GraphManager.KIND, NodeKind.END.name()).count().next());
            int nodes = Math.toIntExact(manager.nodeCount());
            int edges = Math.toIntExact(manager.edgeCount());
            try {
                validate(definition(manager), purpose, node -> true);
                return new GraphSummary(nodes, edges, Math.toIntExact(starts), Math.toIntExact(ends),
                        List.of(), List.of());
            } catch (GraphAdmissionException refused) {
                return summary(nodes, edges, starts, ends, refused.findings().getFirst());
            }
        }
    }

    /** Revalidates exact bytes for a mutation and returns the effective source count. */
    public int require(byte[] graphMl, GraphAdmissionPurpose purpose) {
        Objects.requireNonNull(graphMl, "graphMl");
        try (GraphManager manager = GraphManager.readGraphMl(new ByteArrayInputStream(graphMl), limits.graphMl())) {
            return validate(definition(manager), purpose, node -> true);
        }
    }

    private static GraphDefinition definition(GraphManager manager) {
        try {
            return manager.definition();
        } catch (GraphValidationException refusal) {
            throw refuse(GraphAdmissionPhase.SEMANTIC_STRUCTURE, GraphAdmissionReason.INVALID_STRUCTURE,
                    refusal.primaryNodeId().orElse(null), null);
        } catch (IllegalArgumentException unclassified) {
            // Preserve the existing trusted log for unexpected semantic defects; never publish its text.
            manager.semanticViolations();
            throw refuse(GraphAdmissionPhase.SEMANTIC_STRUCTURE, GraphAdmissionReason.INVALID_STRUCTURE,
                    null, null);
        }
    }

    /** Shared definition-level path used by GraphRunner and pinned continuation starts. */
    public int validate(GraphDefinition graph, GraphAdmissionPurpose purpose, Predicate<GraphNode> include) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(include, "include");
        try {
            new BehaviorPropertySchema(behaviors).validate(graph, include);
        } catch (BehaviorPropertySchema.BehaviorPropertyException refusal) {
            throw refuse(GraphAdmissionPhase.PROPERTY_SCHEMA, refusal.reason().publicReason(),
                    refusal.nodeId(), refusal.propertyName());
        }
        for (GraphNode node : graph.nodes().stream().sorted(Comparator.comparing(GraphNode::id)).toList()) {
            if (node.kind() != NodeKind.BEHAVIOR || !include.test(node)) continue;
            try {
                behaviors.validate(node);
            } catch (RuntimeException refusal) {
                throw refuse(GraphAdmissionPhase.CAPABILITY, GraphAdmissionReason.CAPABILITY_UNAVAILABLE,
                        node.id(), null);
            }
        }
        try {
            new NodeRuntimeNatureValidator(behaviors).validate(graph);
        } catch (NodeRuntimeNatureException refusal) {
            GraphAdmissionReason reason = switch (refusal.reason()) {
                case UNKNOWN_NATURE -> GraphAdmissionReason.RUNTIME_NATURE_INVALID;
                case NATURE_NOT_PERMITTED, DECLARED_BY_UNCATALOGUED_BEHAVIOR ->
                        GraphAdmissionReason.RUNTIME_NATURE_NOT_ALLOWED;
                case RESIDENCY_NOT_IMPLEMENTED -> GraphAdmissionReason.RUNTIME_NATURE_UNSUPPORTED;
                case DECLARED_ON_NON_BEHAVIOR_NODE -> GraphAdmissionReason.RUNTIME_NATURE_INVALID;
            };
            throw refuse(GraphAdmissionPhase.RUNTIME_NATURE, reason,
                    text(refusal.diagnosticDetail(), "nodeId"), NodeRuntimeNatureProperty.NAME);
        }
        try {
            new NodeBypassValidator().validate(graph);
        } catch (NodeBypassException refusal) {
            throw refuse(GraphAdmissionPhase.BYPASS, GraphAdmissionReason.BYPASS_INVALID,
                    text(refusal.diagnosticDetail(), "nodeId"), NodeBypassProperty.NAME);
        }
        try {
            new NodeRuntimeConcurrencyValidator(behaviors).validate(graph);
        } catch (NodeRuntimeConcurrencyException refusal) {
            throw refuse(GraphAdmissionPhase.RUNTIME_CONCURRENCY,
                    GraphAdmissionReason.RUNTIME_CONCURRENCY_INVALID,
                    text(refusal.diagnosticDetail(), "nodeId"), NodeRuntimeMaxConcurrencyProperty.NAME);
        }
        try {
            new GraphComplexityAdmission(behaviors, limits).validate(graph);
        } catch (GraphExecutionLimitException refusal) {
            throw refuse(GraphAdmissionPhase.RESOURCE_LIMIT, GraphAdmissionReason.GRAPH_LIMIT_EXCEEDED,
                    null, null);
        }
        int sources = 0;
        for (GraphNode node : graph.nodes().stream().sorted(Comparator.comparing(GraphNode::id)).toList()) {
            var descriptor = node.kind() == NodeKind.BEHAVIOR
                    ? behaviors.descriptor(node.behavior()).orElse(null) : null;
            if (NodeRuntimeNatureProperty.effectiveNature(descriptor, node.properties()) != NodeRuntimeNature.SOURCE) {
                continue;
            }
            boolean sourceCapable = node.kind() == NodeKind.BEHAVIOR
                    && behaviors.sourceCapableFactory(node.behavior()).isPresent();
            if (!sourceCapable && purpose != GraphAdmissionPurpose.EXECUTION) {
                throw refuse(GraphAdmissionPhase.SOURCE_REQUIREMENT,
                        GraphAdmissionReason.SOURCE_CAPABILITY_MISMATCH, node.id(), null);
            }
            if (sourceCapable) sources++;
        }
        if (purpose == GraphAdmissionPurpose.SOURCE_SESSION && sources == 0) {
            throw refuse(GraphAdmissionPhase.SOURCE_REQUIREMENT, GraphAdmissionReason.SOURCE_REQUIRED, null, null);
        }
        return sources;
    }

    /**
     * Runs the same admission pipeline while preserving the established core exception types for
     * callers that construct a {@link GraphRunner} directly. Application mutation boundaries call
     * {@link #validate(GraphDefinition, GraphAdmissionPurpose, Predicate)} first and therefore
     * expose only the structured contract; this compatibility path keeps the lower-level Java API
     * source- and binary-compatible without maintaining a second set of admission rules.
     */
    void validateLegacy(GraphDefinition graph, Predicate<GraphNode> include) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(include, "include");
        new BehaviorPropertySchema(behaviors).validate(graph, include);
        new BehaviorCapabilityPreflight(behaviors).validate(graph, include);
        new NodeRuntimeNatureValidator(behaviors).validate(graph);
        new NodeBypassValidator().validate(graph);
        new NodeRuntimeConcurrencyValidator(behaviors).validate(graph);
        new GraphComplexityAdmission(behaviors, limits).validate(graph);
    }

    private static GraphSummary summary(int nodes, int edges, long starts, long ends,
                                        GraphAdmissionFinding finding) {
        String legacy = finding.phase().name() + ": " + finding.reason().name();
        return new GraphSummary(nodes, edges, Math.toIntExact(starts), Math.toIntExact(ends),
                List.of(legacy), List.of(finding));
    }

    private static String text(Map<String, Object> details, String name) {
        Object value = details.get(name);
        return value == null ? null : value.toString();
    }

    private static GraphAdmissionException refuse(GraphAdmissionPhase phase, GraphAdmissionReason reason,
                                                  String nodeId, String propertyName) {
        return new GraphAdmissionException(finding(phase, reason, nodeId, propertyName));
    }

    private static GraphAdmissionFinding finding(GraphAdmissionPhase phase, GraphAdmissionReason reason,
                                                  String nodeId, String propertyName) {
        return GraphAdmissionFinding.of(phase, reason, nodeId, propertyName,
                "incident:" + UUID.randomUUID().toString().replace("-", ""));
    }
}
