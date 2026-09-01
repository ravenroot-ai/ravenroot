package ai.ravenroot.core.embed;

import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphVersionRecord;
import ai.ravenroot.core.graph.GraphVersionState;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a published graph version into the render-only payload an embed registration captures.
 *
 * <h2>Projection is provision-time work</h2>
 * <p>A read-time authority would let the browser request a projection and make this code resolve a
 * registration by graph coordinates before rendering it. Instead, rendering happens once when an
 * operator provisions, and its
 * result is frozen inside {@link ai.ravenroot.api.embed.EmbedRegistrationAggregate}.</p>
 *
 * <h2>What crosses, and what cannot</h2>
 * <p>Only node id, node kind and four layout numbers, plus edge endpoints. Node properties are read
 * for layout and for nothing else: no property map, no configuration, no credential reference and no
 * behavior parameter is copied into the payload, and the closed {@link EmbedGraphProjection} schema
 * has nowhere to put one if it were. The ordering is deterministic so that two provisions of the same
 * version produce byte-identical payloads, which is what makes the stored digest comparison mean
 * something.</p>
 */
public final class EmbedSnapshotProjector {

    private static final String LAYOUT_X = "layoutX";
    private static final String LAYOUT_Y = "layoutY";
    private static final String LAYOUT_WIDTH = "layoutWidth";
    private static final String LAYOUT_HEIGHT = "layoutHeight";
    private static final Set<String> LAYOUT_KEYS =
            Set.of(LAYOUT_X, LAYOUT_Y, LAYOUT_WIDTH, LAYOUT_HEIGHT);

    private EmbedSnapshotProjector() {
    }

    /** Closed outcome vocabulary; a refusal is a value, and the caller decides how to report it. */
    public sealed interface Result {
        record Projected(EmbedGraphProjection projection,
                         EmbedSnapshotLifecycle lifecycle) implements Result {
            public Projected {
                Objects.requireNonNull(projection, "projection");
                Objects.requireNonNull(lifecycle, "lifecycle");
            }
        }

        /** The version is neither PUBLISHED nor ACTIVE. */
        enum NotPublished implements Result { INSTANCE }

        /** Snapshot identity, digest or policy revision disagreed with the grant. */
        enum Incoherent implements Result { INSTANCE }

        /** The version exceeds a projection budget. */
        enum TooLarge implements Result { INSTANCE }

        /** A node carried a partial or non-numeric layout. */
        enum Invalid implements Result { INSTANCE }
    }

    public static Result project(GraphVersionRecord version, VerifiedEmbedGraphGrant grant,
                                 EmbedProjectionEligibility eligibility, EmbedProjectionBudget budget) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(budget, "budget");
        EmbedSnapshotLifecycle lifecycle = lifecycleOf(version.state());
        if (lifecycle == null) return Result.NotPublished.INSTANCE;
        if (!version.snapshot().key().graphId().equals(grant.graphId())
                || !version.snapshot().key().versionId().equals(grant.graphVersionId())
                || !version.snapshot().canonicalHash().equals(grant.canonicalDigest())
                || !eligibility.policyRevision().equals(grant.projectionPolicyRevision())) {
            return Result.Incoherent.INSTANCE;
        }
        try {
            return new Result.Projected(render(version, grant, budget), lifecycle);
        } catch (ProjectionTooLarge tooLarge) {
            return Result.TooLarge.INSTANCE;
        } catch (ProjectionInvalid invalid) {
            return Result.Invalid.INSTANCE;
        }
    }

    private static EmbedSnapshotLifecycle lifecycleOf(GraphVersionState state) {
        return switch (state) {
            case PUBLISHED -> EmbedSnapshotLifecycle.PUBLISHED;
            case ACTIVE -> EmbedSnapshotLifecycle.ACTIVE;
            case VALIDATED, RETIRED -> null;
        };
    }

    private static EmbedGraphProjection render(GraphVersionRecord version, VerifiedEmbedGraphGrant grant,
                                               EmbedProjectionBudget budget) {
        var definition = version.snapshot().definition();
        requireIdentifier(grant.graphId(), budget);
        requireIdentifier(grant.graphVersionId(), budget);
        requireIdentifier(grant.canonicalDigest(), budget);
        if (definition.nodes().size() > budget.maxNodes() || definition.edges().size() > budget.maxEdges()) {
            throw new ProjectionTooLarge();
        }
        var nodes = definition.nodes().stream()
                .sorted(Comparator.comparing(GraphNode::id))
                .map(node -> projectNode(node, budget))
                .toList();
        var edges = definition.edges().stream()
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .map(edge -> {
                    requireIdentifier(edge.source(), budget);
                    requireIdentifier(edge.target(), budget);
                    return new EmbedGraphProjection.Edge(edge.source(), edge.target());
                })
                .toList();
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                grant.graphId(), grant.graphVersionId(), grant.canonicalDigest(), nodes, edges);
        if (projection.jsonBytes() > budget.maxJsonBytes()) throw new ProjectionTooLarge();
        return projection;
    }

    private static EmbedGraphProjection.Node projectNode(GraphNode node, EmbedProjectionBudget budget) {
        requireIdentifier(node.id(), budget);
        return new EmbedGraphProjection.Node(node.id(), node.kind().name(), layout(node, budget));
    }

    private static EmbedGraphProjection.Layout layout(GraphNode node, EmbedProjectionBudget budget) {
        Map<String, Object> properties = node.properties();
        boolean any = LAYOUT_KEYS.stream().anyMatch(properties::containsKey);
        if (!any) return null;
        // All four or none. A node with three of them is a node whose layout someone edited by hand,
        // and inventing the fourth is how a viewer ends up drawing a box nobody positioned.
        if (!properties.keySet().containsAll(LAYOUT_KEYS)) throw new ProjectionInvalid();
        double x = number(properties.get(LAYOUT_X));
        double y = number(properties.get(LAYOUT_Y));
        double width = number(properties.get(LAYOUT_WIDTH));
        double height = number(properties.get(LAYOUT_HEIGHT));
        if (Math.abs(x) > budget.maxCoordinateMagnitude() || Math.abs(y) > budget.maxCoordinateMagnitude()
                || width <= 0 || height <= 0 || width > budget.maxDimension()
                || height > budget.maxDimension()) {
            throw new ProjectionTooLarge();
        }
        return new EmbedGraphProjection.Layout(x, y, width, height);
    }

    private static double number(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new ProjectionInvalid();
        }
        return number.doubleValue();
    }

    private static void requireIdentifier(String value, EmbedProjectionBudget budget) {
        if (value.length() > budget.maxIdentifierChars()) throw new ProjectionTooLarge();
    }

    private static final class ProjectionTooLarge extends RuntimeException {
        private ProjectionTooLarge() {
            super(null, null, false, false);
        }
    }

    private static final class ProjectionInvalid extends RuntimeException {
        private ProjectionInvalid() {
            super(null, null, false, false);
        }
    }
}
