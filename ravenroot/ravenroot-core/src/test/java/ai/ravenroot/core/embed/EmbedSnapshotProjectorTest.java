package ai.ravenroot.core.embed;

import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionRecord;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.graph.GraphVersionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the browser may be shown, decided once at provision time.
 *
 * <p>The provision-time snapshot replaced the read-time projection authority this module used to hold, and its suite; the
 * assertions below about lifecycle, policy gates, coordinate pinning, budgets and the closed payload
 * are that suite's, carried forward. What changed is when they apply: the same rules used to run on
 * every browser read, from a registry keyed on graph coordinates; they now run once, on an operator
 * command, and the result is frozen into the registration aggregate. The read-side half of these
 * properties is asserted in {@code EmbedRegistrationAuthorityTest}.</p>
 */
class EmbedSnapshotProjectorTest {

    @Test
    void publishedAndActiveVersionsProduceAClosedRenderOnlyProjection() {
        for (GraphVersionState state : List.of(GraphVersionState.PUBLISHED, GraphVersionState.ACTIVE)) {
            var projected = assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                    project(state, EmbedProjectionEligibility.allowed("policy-1"),
                            EmbedProjectionBudget.DEFAULTS));
            assertEquals(state == GraphVersionState.PUBLISHED
                    ? EmbedSnapshotLifecycle.PUBLISHED : EmbedSnapshotLifecycle.ACTIVE,
                    projected.lifecycle());
            String json = projected.projection().toJson();
            assertTrue(json.contains("\"kind\":\"BEHAVIOR\""));
            assertTrue(json.contains("\"layout\":{\"x\":10.0"));
            for (String forbidden : List.of("behavior.impl", "prompt-secret", "https://internal",
                    "header-secret", "createdBy", "tenant-a", "deployment-a", "outcome", "properties")) {
                assertTrue(!json.contains(forbidden), forbidden);
            }
        }
    }

    /**
     * The lifecycle gate. A VALIDATED or RETIRED version has no {@link EmbedSnapshotLifecycle} member
     * to map onto, so it cannot become a provisioned registration at all — which is what makes
     * «PUBLISHED or ACTIVE» a property of the type rather than of a comparison someone might drop.
     */
    @Test
    void anUnpublishedOrRetiredVersionCannotBeProjectedAtAll() {
        for (GraphVersionState state : List.of(GraphVersionState.VALIDATED, GraphVersionState.RETIRED)) {
            assertInstanceOf(EmbedSnapshotProjector.Result.NotPublished.class,
                    project(state, EmbedProjectionEligibility.allowed("policy-1"),
                            EmbedProjectionBudget.DEFAULTS));
        }
    }

    @Test
    void everyDeniedPolicyGateIsCarriedForwardAndBlocksTheRegistration() {
        List<EmbedProjectionEligibility> denied = List.of(
                new EmbedProjectionEligibility("policy-1", false, true, true, true, true, true, true),
                new EmbedProjectionEligibility("policy-1", true, false, true, true, true, true, true),
                new EmbedProjectionEligibility("policy-1", true, true, false, true, true, true, true),
                new EmbedProjectionEligibility("policy-1", true, true, true, false, true, true, true),
                new EmbedProjectionEligibility("policy-1", true, true, true, true, false, true, true),
                new EmbedProjectionEligibility("policy-1", true, true, true, true, true, false, true),
                new EmbedProjectionEligibility("policy-1", true, true, true, true, true, true, false));
        for (var eligibility : denied) {
            // The projector renders; the gate that refuses a denied policy is EmbedRegistrationRules,
            // which every adapter shares. Both halves are asserted so neither can quietly disappear.
            assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                    project(GraphVersionState.PUBLISHED, eligibility, EmbedProjectionBudget.DEFAULTS));
            assertTrue(!eligibility.allowsProjection());
        }
    }

    @Test
    void everyGrantCoordinateIsPinnedAndCannotBeSubstituted() {
        var snapshot = snapshot();
        String digest = snapshot.canonicalHash();
        List<VerifiedEmbedGraphGrant> substitutions = List.of(
                grant("other", "resource-a", "deployment-a", 7, "graph-a", "v1", digest, "policy-1"),
                grant("tenant-a", "resource-a", "deployment-a", 7, "other", "v1", digest, "policy-1"),
                grant("tenant-a", "resource-a", "deployment-a", 7, "graph-a", "v2", digest, "policy-1"),
                grant("tenant-a", "resource-a", "deployment-a", 7, "graph-a", "v1", "wrong", "policy-1"),
                grant("tenant-a", "resource-a", "deployment-a", 7, "graph-a", "v1", digest, "policy-2"));
        var version = new GraphVersionRecord(snapshot, GraphVersionState.PUBLISHED);
        var eligibility = EmbedProjectionEligibility.allowed("policy-1");
        for (var substitution : substitutions) {
            var result = EmbedSnapshotProjector.project(version, substitution, eligibility,
                    EmbedProjectionBudget.DEFAULTS);
            if (substitution.tenantId().equals("tenant-a")) {
                assertInstanceOf(EmbedSnapshotProjector.Result.Incoherent.class, result);
            } else {
                // A foreign tenant is refused by EmbedRegistrationRules, which compares the command's
                // tenant with the grant's; the projector is not the place that knows the operator's.
                assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class, result);
            }
        }
    }

    @Test
    void nodeEdgeByteAndLayoutBudgetsFailWithoutPartialData() {
        var full = assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                project(GraphVersionState.PUBLISHED, EmbedProjectionEligibility.allowed("policy-1"),
                        EmbedProjectionBudget.DEFAULTS)).projection();
        var exact = new EmbedProjectionBudget(3, 2, full.jsonBytes(), 256, 20, 40);
        assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                project(GraphVersionState.PUBLISHED, EmbedProjectionEligibility.allowed("policy-1"), exact));

        List<EmbedProjectionBudget> limits = List.of(
                new EmbedProjectionBudget(2, 10, 10_000, 256, 1_000, 1_000),
                new EmbedProjectionBudget(10, 1, 10_000, 256, 1_000, 1_000),
                new EmbedProjectionBudget(10, 10, full.jsonBytes() - 1, 256, 1_000, 1_000),
                new EmbedProjectionBudget(10, 10, 10_000, 4, 1_000, 1_000),
                new EmbedProjectionBudget(10, 10, 10_000, 256, 5, 1_000),
                new EmbedProjectionBudget(10, 10, 10_000, 256, 1_000, 39));
        for (var limit : limits) {
            assertInstanceOf(EmbedSnapshotProjector.Result.TooLarge.class,
                    project(GraphVersionState.PUBLISHED, EmbedProjectionEligibility.allowed("policy-1"),
                            limit));
        }
    }

    @Test
    void aPartialOrNonNumericLayoutIsInvalidRatherThanCompleted() {
        var definition = new GraphDefinition(List.of(
                new GraphNode("start", ai.ravenroot.core.graph.NodeKind.START, null,
                        Map.of("layoutX", 10, "layoutY", 20, "layoutWidth", 30)),
                GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        var snapshot = GraphVersionSnapshot.create(new GraphVersionKey("graph-a", "v1"), definition);
        assertInstanceOf(EmbedSnapshotProjector.Result.Invalid.class, EmbedSnapshotProjector.project(
                new GraphVersionRecord(snapshot, GraphVersionState.PUBLISHED),
                grant("tenant-a", "resource-a", "deployment-a", 7, "graph-a", "v1",
                        snapshot.canonicalHash(), "policy-1"),
                EmbedProjectionEligibility.allowed("policy-1"), EmbedProjectionBudget.DEFAULTS));
    }

    /** Two provisions of the same version must produce byte-identical payloads, or the digest lies. */
    @Test
    void projectionIsDeterministic() {
        var first = assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                project(GraphVersionState.PUBLISHED, EmbedProjectionEligibility.allowed("policy-1"),
                        EmbedProjectionBudget.DEFAULTS)).projection();
        var second = assertInstanceOf(EmbedSnapshotProjector.Result.Projected.class,
                project(GraphVersionState.PUBLISHED, EmbedProjectionEligibility.allowed("policy-1"),
                        EmbedProjectionBudget.DEFAULTS)).projection();
        assertEquals(first.toJson(), second.toJson());
        assertEquals(List.of("end", "start", "work"),
                first.nodes().stream().map(EmbedGraphProjection.Node::id).toList());
    }

    private static EmbedSnapshotProjector.Result project(GraphVersionState state,
                                                          EmbedProjectionEligibility eligibility,
                                                          EmbedProjectionBudget budget) {
        var snapshot = snapshot();
        return EmbedSnapshotProjector.project(new GraphVersionRecord(snapshot, state),
                grant("tenant-a", "resource-a", "deployment-a", 7, "graph-a", "v1",
                        snapshot.canonicalHash(), eligibility.policyRevision()),
                eligibility, budget);
    }

    private static GraphVersionSnapshot snapshot() {
        return GraphVersionSnapshot.create(new GraphVersionKey("graph-a", "v1"), definition());
    }

    private static GraphDefinition definition() {
        return new GraphDefinition(List.of(
                new GraphNode("start", ai.ravenroot.core.graph.NodeKind.START, null,
                        Map.of("layoutX", 10, "layoutY", 20, "layoutWidth", 30, "layoutHeight", 40,
                                "prompt", "prompt-secret", "url", "https://internal")),
                new GraphNode("work", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "behavior.impl",
                        Map.of("header", "header-secret")),
                GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
    }

    private static VerifiedEmbedGraphGrant grant(String tenant, String resource, String deployment,
                                                  long deploymentVersion, String graph, String graphVersion,
                                                  String digest, String policy) {
        return new VerifiedEmbedGraphGrant(tenant, resource, deployment, deploymentVersion, graph,
                graphVersion, digest, policy);
    }
}
