package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import ai.ravenroot.core.manifest.ExecutionManifestIncompatibleException;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim the manifest exists to make, asserted above the port: an execution accepted by one
 * process is either reproduced exactly by the next one, or refused by it.
 *
 * <p>These assertions belong here rather than in the conformance suite because what they check is
 * the <em>acceptance path's</em> obligation and the <em>recovery path's</em> obligation, not an
 * adapter's: that a manifest is committed before the acceptance that references it, that a refused
 * manifest write refuses the acceptance with it, and that a runtime which resolves something
 * different refuses rather than substituting what it has today.</p>
 */
class DefaultRavenrootApplicationExecutionManifestTest {

    /**
     * A credential-shaped literal, carried in the graph document as a node property.
     *
     * <p>It exists to make the secret assertion falsifiable rather than vacuous: a manifest that
     * copied any part of the document would carry this string, and the assertion below would fail.
     * It is not a real credential and is not resolved by anything.</p>
     */
    private static final String SENTINEL = "Bearer s3nt1nel-must-never-reach-a-manifest";

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-secret" for="node" attr.name="authorization" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start">
                  <data key="node-kind">START</data>
                  <data key="node-secret">Bearer s3nt1nel-must-never-reach-a-manifest</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path databaseDirectory;

    @Test
    void anAcceptedExecutionPinsAManifestThatSurvivesTheProcessThatAcceptedIt() {
        Path database = databaseDirectory.resolve("store.db");
        UUID executionId = UUID.randomUUID();
        ExecutionSubmission submission;
        ExecutionKey key;

        try (var executions = new SqliteExecutionStore(database, Clock.systemUTC());
             var definitions = new SqliteGraphDefinitionStore(database, Clock.systemUTC(),
                     GraphDefinitionReferences.NONE);
             var manifests = new SqliteExecutionManifestStore(database, Clock.systemUTC(),
                     ExecutionManifestReferences.NONE)) {
            var application = applicationWith(executions, definitions, manifests,
                    GraphExecutionLimits.DEFAULTS);
            submission = application.startGraphMl(TestIdentities.TENANT_A, executionId,
                    new ByteArrayInputStream(graphBytes()), "payload");
            key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId());
            application.close();
        }

        // Nothing in memory survives that block. What is carried across is the execution key alone.
        try (var manifests = new SqliteExecutionManifestStore(database, Clock.systemUTC(),
                ExecutionManifestReferences.NONE)) {
            StoredExecutionManifest recovered = await(manifests.load(key));
            assertEquals(submission.graphVersion(), recovered.manifest().graphContentId().value(),
                    "the manifest pins the same document address the caller was handed, so there is "
                            + "one graph identity rather than two to keep in step");
            assertEquals(ExecutionManifest.CURRENT_FORMAT_VERSION, recovered.manifest().formatVersion());
            assertEquals(recovered.manifest().digest(), recovered.digest());
            assertEquals(ExecutionPolicy.STANDARD.name(), recovered.manifest().runtime().executionPolicy());
        }
    }

    @Test
    void acceptanceFailsAndRecordsNoExecutionWhenTheManifestCannotBePinned() {
        var executions = new InMemoryExecutionStore();
        // Pinned, so the assertion below can name the process instance that would have been created
        // and prove no row exists for it. Asserting only that the call threw would pass against an
        // implementation that created the instance and then failed to pin, which is the exact
        // half-committed state this ordering exists to make unreachable.
        UUID processInstanceId = UUID.randomUUID();
        var application = new DefaultRavenrootApplication(new SameThreadExecutionEngine(),
                new ExecutionMonitor(), BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                fixedIdentities(processInstanceId), executions, 0,
                UnknownBehaviorPolicy.passThrough(),
                new InMemoryGraphDefinitionStore(Clock.systemUTC()), null, null,
                GraphExecutionLimits.DEFAULTS, null, new RefusingManifestStore());

        ExecutionManifestStoreException refused = assertThrows(ExecutionManifestStoreException.class,
                () -> application.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                        new ByteArrayInputStream(graphBytes()), "payload"));
        assertInstanceOf(ExecutionManifestStoreFailure.Unavailable.class, refused.failure());

        var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), processInstanceId);
        var storeFailure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> executions.load(key).toCompletableFuture().join());
        assertInstanceOf(ai.ravenroot.api.persistence.ExecutionStoreFailure.NotFound.class,
                ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(storeFailure).failure(),
                "the manifest write is refused before the acceptance write is attempted, so no "
                        + "process instance was ever created");
        assertEquals(0, executions.leases(TestIdentities.TENANT_A.tenantId())
                        .toCompletableFuture().join().size(),
                "and nothing holds a lease on an instance that does not exist");
        application.close();
    }

    private static ai.ravenroot.api.application.ExecutionIdentitySource fixedIdentities(UUID instance) {
        return kind -> kind == ai.ravenroot.api.application.ExecutionIdentityKind.PROCESS_INSTANCE
                ? instance
                : UUID.randomUUID();
    }

    @Test
    void aRuntimeThatResolvesDifferentLimitsRefusesToReproduceTheExecution() {
        var executions = new InMemoryExecutionStore();
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC());
        var accepting = applicationWith(executions, definitions, manifests,
                GraphExecutionLimits.DEFAULTS);
        ExecutionSubmission submission = accepting.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId());
        accepting.close();

        // The operator halves the fan-out ceiling and restarts. Same document, same tenant, same
        // execution -- and a bound that decides what the same graph is allowed to do.
        GraphExecutionLimits tightened = new GraphExecutionLimits(
                GraphExecutionLimits.DEFAULTS.graphMl(), GraphExecutionLimits.DEFAULTS.payload(),
                GraphExecutionLimits.DEFAULTS.maxFanOut() / 2,
                GraphExecutionLimits.DEFAULTS.maxResidentActors(),
                GraphExecutionLimits.DEFAULTS.maxLiveActorsPerTraversal(),
                GraphExecutionLimits.DEFAULTS.maxInFlightHopsPerTraversal(),
                GraphExecutionLimits.DEFAULTS.maxQueuedAdmissionsPerNode(),
                GraphExecutionLimits.DEFAULTS.maxTraversalSteps(),
                GraphExecutionLimits.DEFAULTS.maxAmplifiedDeliveries(),
                GraphExecutionLimits.DEFAULTS.maxCumulativePayloadBytes(),
                GraphExecutionLimits.DEFAULTS.maxRecoveryDeliveriesPerAttempt());
        var recovering = applicationWith(executions, definitions, manifests, tightened);

        ExecutionManifestIncompatibleException refused = assertThrows(
                ExecutionManifestIncompatibleException.class,
                () -> recovering.executionManifests().verify(key, ExecutionPolicy.STANDARD));

        assertEquals(1, refused.report().differences().size());
        assertEquals(ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                refused.report().differences().get(0).dimension());
        assertFalse(refused.report().truncated());
        recovering.close();
    }

    @Test
    void aRuntimeThatWouldRunADifferentPolicyRefusesToReproduceTheExecution() {
        var executions = new InMemoryExecutionStore();
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC());
        var application = applicationWith(executions, definitions, manifests,
                GraphExecutionLimits.DEFAULTS);
        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload",
                ExecutionPolicy.TEST_PASSTHROUGH);
        var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId());

        // Every recovery path rebuilds its runner under STANDARD. Without a pin, an execution
        // admitted as structural evidence would come back invoking production behaviors.
        ExecutionManifestIncompatibleException refused = assertThrows(
                ExecutionManifestIncompatibleException.class,
                () -> application.executionManifests().verify(key, ExecutionPolicy.STANDARD));

        assertEquals(ExecutionManifestDifference.Dimension.EXECUTION_POLICY,
                refused.report().differences().get(0).dimension());
        assertTrue(refused.getMessage().contains("TEST_PASSTHROUGH")
                        && refused.getMessage().contains("STANDARD"),
                "the diagnostic names both values, and both are closed enum names rather than "
                        + "anything a document could have supplied");
        application.close();
    }

    @Test
    void anUnpinnedExecutionIsRefusedRatherThanResolvedFromTodaysEnvironment() {
        var executions = new InMemoryExecutionStore();
        var application = applicationWith(executions,
                new InMemoryGraphDefinitionStore(Clock.systemUTC()),
                new InMemoryExecutionManifestStore(Clock.systemUTC()), GraphExecutionLimits.DEFAULTS);

        var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), UUID.randomUUID());
        ExecutionManifestStoreException refused = assertThrows(ExecutionManifestStoreException.class,
                () -> application.executionManifests().verify(key, ExecutionPolicy.STANDARD));

        assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class, refused.failure());
        application.close();
    }

    @Test
    void nothingTheDocumentCarriesReachesTheManifestOrItsDiagnostics() {
        var executions = new InMemoryExecutionStore();
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC());
        var application = applicationWith(executions, definitions, manifests,
                GraphExecutionLimits.DEFAULTS);
        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId());

        StoredExecutionManifest stored = await(manifests.load(key));
        String rendered = stored.manifest().toString() + stored.digest()
                + application.executionManifests().describe(key, ExecutionPolicy.STANDARD).describe();

        assertFalse(rendered.contains(SENTINEL),
                "the credential-shaped literal the document carries must not appear in the manifest");
        assertFalse(rendered.contains("s3nt1nel"),
                "nor any fragment of it, which a partial copy would leave behind");
        assertFalse(rendered.contains("authorization"),
                "nor the property name that carried it");
        assertTrue(new String(graphBytes(), StandardCharsets.UTF_8).contains(SENTINEL),
                "and the document really did carry it, so this assertion can fail");
        application.close();
    }

    private DefaultRavenrootApplication applicationWith(ExecutionStore executions,
                                                        GraphDefinitionStore definitions,
                                                        ExecutionManifestStore manifests,
                                                        GraphExecutionLimits limits) {
        return new DefaultRavenrootApplication(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), executions, 0,
                UnknownBehaviorPolicy.passThrough(), definitions, null, null, limits, null, manifests);
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionManifestStoreException failure = ExecutionManifestStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    /** A store that is reachable, refuses every write, and provably applied nothing. */
    private static final class RefusingManifestStore implements ExecutionManifestStore {

        @Override
        public Set<StoreCapability> capabilities() {
            return Set.of();
        }

        @Override
        public CompletionStage<StoredExecutionManifest> pin(ExecutionManifest manifest) {
            return CompletableFuture.failedFuture(new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.Unavailable("refusing store")));
        }

        @Override
        public CompletionStage<StoredExecutionManifest> load(ExecutionKey key) {
            return CompletableFuture.failedFuture(new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.NotFound(key)));
        }

        @Override
        public CompletionStage<Boolean> contains(ExecutionKey key) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Void> remove(ExecutionKey key) {
            return CompletableFuture.failedFuture(new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.NotFound(key)));
        }

        @Override
        public void close() {
        }
    }
}
