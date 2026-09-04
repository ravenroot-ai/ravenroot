package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentityKind;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyLimits;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphDefinitionStoreFailure;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment-hosted half of the acceptance binding: a traversal admitted through a deployment's
 * ingress is bound to a durably stored document, and the document is stored before the pin.
 *
 * <h2>What composition this covers, and what it does not</h2>
 * <p>Binding a definition happens inside the durable-recording path, which a deployment enters only
 * when it was composed with an execution store. These tests therefore compose a deployment with
 * <em>both</em> stores, through the public constructor that accepts both, and that is the only
 * composition in which a deployment-hosted acceptance is durably bound.</p>
 *
 * <p><strong>The shipped server does not compose deployments this way.</strong> Deployment
 * registration builds deployments with no execution store, so their traversals are not durably
 * recorded and not durably bound; giving deployments durable execution state is separate work. These
 * assertions are about the capability being real and correct where it is composed, not about the
 * default server composition, and must not be read as covering it.</p>
 */
class DeploymentHostedGraphDefinitionBindingTest {

    private static final SecurityContext IDENTITY = new SecurityContext("definition-binding-request",
            "definition-binding-tenant", "definition-binding-subject", PrincipalType.WORKLOAD,
            "urn:ravenroot:definition-binding");
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
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
    void aDeploymentHostedTraversalIsBoundToADurablyStoredDocument() throws Exception {
        Path database = databaseDirectory.resolve("bound.db");
        UUID processInstanceId = UUID.randomUUID();

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE)) {

            var deployment = deployment(engine, executions, definitions, fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt);

            var contentId = GraphContentId.of(graphBytes());
            var definitionKey = new GraphDefinitionKey(IDENTITY.tenantId(), contentId);
            StoredGraphDefinition stored = definitions.load(definitionKey).toCompletableFuture().join();
            assertArrayEquals(graphBytes(), stored.canonical().bytes(),
                    "the document the deployment hosts must be retained byte for byte");
            assertEquals(GraphDefinitionIdentity.forSubmission(contentId), stored.identity());

            var executionKey = new ExecutionKey(IDENTITY.tenantId(), processInstanceId);
            assertEquals(contentId.value(),
                    executions.load(executionKey).toCompletableFuture().join().graphVersionPin().reference(),
                    "and the accepted traversal must be pinned to the address that document is filed "
                            + "under, or recovery would have an identifier resolving to nothing");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * A deployment-hosted traversal is an accepted execution like any other, so it must carry the
     * same record of what it was resolved against.
     *
     * <p>The assertion is not decorative. Every recovery path that verifies refuses an execution with
     * no such record, so a deployment that accepted traversals without one would have produced work
     * that could never be resumed — visible only later, on the first restart, as a recovery that
     * declines forever.</p>
     */
    @Test
    void aDeploymentHostedTraversalRecordsTheDependencySetItWasAcceptedAgainst() throws Exception {
        Path database = databaseDirectory.resolve("manifest.db");
        UUID processInstanceId = UUID.randomUUID();

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE);
             var manifestStore = new ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore(
                     database, clock(), ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE)) {

            BehaviorRegistry behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults());
            var manifests = new ai.ravenroot.core.manifest.ExecutionManifestService(manifestStore,
                    ai.ravenroot.core.manifest.ExecutionManifestResolver.from(engine,
                            executions.capabilities(), behaviors,
                            UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null),
                    clock());
            var deployment = new DefaultGraphDeployment(DeploymentId.of("manifest-" + UUID.randomUUID()),
                    engine, behaviors, new ExecutionMonitor(), fixedIdentities(processInstanceId),
                    graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-" + UUID.randomUUID(),
                    Duration.ofSeconds(30),
                    RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                    definitions, GraphExecutionLimits.DEFAULTS, null, manifests);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt);

            var executionKey = new ExecutionKey(IDENTITY.tenantId(), processInstanceId);
            var recorded = manifestStore.load(executionKey).toCompletableFuture().join();
            assertEquals(GraphContentId.of(graphBytes()), recorded.manifest().graphContentId(),
                    "the record pins the same document address the traversal is pinned to");
            assertEquals("STANDARD", recorded.manifest().runtime().executionPolicy(),
                    "and the policy a deployment-hosted traversal actually runs under");

            // The verification a later recovery performs, run against this same runtime: it must pass,
            // or the deployment would be accepting work its own process could not resume.
            manifests.verify(executionKey, ai.ravenroot.api.application.ExecutionPolicy.STANDARD);

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theDocumentIsCommittedBeforeTheTraversalIsPinnedToIt() throws Exception {
        Path database = databaseDirectory.resolve("ordering.db");
        UUID processInstanceId = UUID.randomUUID();
        var pinExistedWhenTheDocumentWasWritten = new AtomicReference<Boolean>();

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var real = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE)) {

            // Asked from inside the definition write itself, which is the only place the ordering is
            // observable without inferring it from timing. If the pin were written first, this would
            // find the instance already present.
            var definitions = new ProbingDefinitionStore(real, () -> pinExistedWhenTheDocumentWasWritten
                    .set(instanceExists(executions, IDENTITY.tenantId(), processInstanceId)));

            var deployment = deployment(engine, executions, definitions, fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertInstanceOf(IngressReceipt.DurablyCommitted.class,
                    deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                            "poller-1", "key-A"));

            assertEquals(Boolean.FALSE, pinExistedWhenTheDocumentWasWritten.get(),
                    "the document must be durable before the acceptance that pins it: the reverse "
                            + "ordering leaves an execution nothing can recover, while this one leaves "
                            + "at worst an unreferenced document retention reclaims");
            assertTrue(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "and the pin must exist once the offer has been accepted, or the assertion above "
                            + "would pass against a deployment that recorded nothing at all");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aRefusedDocumentRefusesTheTraversalAndLeavesNoExecutionBehind() throws Exception {
        Path database = databaseDirectory.resolve("refused.db");
        UUID processInstanceId = UUID.randomUUID();

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock())) {

            var deployment = deployment(engine, executions, new RefusingDefinitionStore(),
                    fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var failure = assertThrows(GraphDefinitionStoreException.class,
                    () -> deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                            "poller-1", "key-A"));
            assertInstanceOf(GraphDefinitionStoreFailure.Unavailable.class, failure.failure());

            assertFalse(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "a refused document must refuse the traversal with it; an execution recorded here "
                            + "would be one whose graph is retained nowhere");
            assertEquals(0, executions.leases(IDENTITY.tenantId()).toCompletableFuture().join().size(),
                    "and nothing holds a lease on an instance that does not exist");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aDeploymentWithNoExecutionStoreRecordsNoDocumentBecauseThereIsNoPinToProtect() throws Exception {
        Path database = databaseDirectory.resolve("volatile.db");

        try (var engine = new JoinTestEngine();
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE)) {

            // The composition the shipped server actually uses for a registered deployment: a
            // definition store is threaded through, no execution store is.
            var deployment = new DefaultGraphDeployment(DeploymentId.of("volatile-" + UUID.randomUUID()),
                    engine, BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, definitions);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.VolatileCustody.class, receipt,
                    "nothing durable is claimed for this composition, and this test exists so that "
                            + "stays true rather than becoming an accidental promise");

            assertFalse(definitions.contains(new GraphDefinitionKey(IDENTITY.tenantId(),
                            GraphContentId.of(graphBytes()))).toCompletableFuture().join(),
                    "no document is retained, correctly: there is no pin for it to protect, and "
                            + "retaining one would claim a durability this composition does not have");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private static boolean instanceExists(ExecutionStore executions, String tenantId, UUID processInstanceId) {
        try {
            executions.load(new ExecutionKey(tenantId, processInstanceId)).toCompletableFuture().join();
            return true;
        } catch (CompletionException thrown) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
            assertNotNull(failure, () -> "unexpected failure reading the instance: " + thrown);
            assertInstanceOf(ExecutionStoreFailure.NotFound.class, failure.failure());
            return false;
        }
    }

    private DefaultGraphDeployment deployment(JoinTestEngine engine, ExecutionStore executions,
                                              GraphDefinitionStore definitions,
                                              ExecutionIdentitySource identities) {
        return new DefaultGraphDeployment(DeploymentId.of("bound-" + UUID.randomUUID()), engine,
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new ExecutionMonitor(),
                identities, graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                executions, DefaultGraphDeployment.DEFAULT_INBOX_RETENTION,
                "worker-" + UUID.randomUUID(), Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                definitions);
    }

    private static ExecutionIdentitySource fixedIdentities(UUID processInstanceId) {
        return kind -> kind == ExecutionIdentityKind.PROCESS_INSTANCE
                ? processInstanceId
                : UUID.randomUUID();
    }

    private static Clock clock() {
        return Clock.fixed(EPOCH, ZoneOffset.UTC);
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
    }

    /** Delegates everything, and runs a probe at the moment the document is written. */
    private static final class ProbingDefinitionStore implements GraphDefinitionStore {

        private final GraphDefinitionStore delegate;
        private final Runnable onPut;
        private final AtomicBoolean probed = new AtomicBoolean();

        private ProbingDefinitionStore(GraphDefinitionStore delegate, Runnable onPut) {
            this.delegate = delegate;
            this.onPut = onPut;
        }

        @Override
        public Set<StoreCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public int maxDefinitionBytes() {
            return delegate.maxDefinitionBytes();
        }

        @Override
        public CompletionStage<StoredGraphDefinition> put(String tenantId,
                                                          GraphDefinitionIdentity identity,
                                                          CanonicalGraphMl canonical) {
            if (probed.compareAndSet(false, true)) {
                onPut.run();
            }
            return delegate.put(tenantId, identity, canonical);
        }

        @Override
        public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
            return delegate.load(key);
        }

        @Override
        public CompletionStage<StoredGraphDefinition> resolve(String tenantId,
                                                              GraphDefinitionIdentity identity) {
            return delegate.resolve(tenantId, identity);
        }

        @Override
        public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
            return delegate.contains(key);
        }

        @Override
        public CompletionStage<Void> remove(GraphDefinitionKey key) {
            return delegate.remove(key);
        }

        @Override
        public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
            return delegate.purgeUnreferencedDefinitions(tenantId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** Refuses every write, so the acceptance path's ordering obligation is observable. */
    private static final class RefusingDefinitionStore implements GraphDefinitionStore {

        @Override
        public Set<StoreCapability> capabilities() {
            return Set.of();
        }

        @Override
        public int maxDefinitionBytes() {
            return 10 * 1024 * 1024;
        }

        @Override
        public CompletionStage<StoredGraphDefinition> put(String tenantId,
                                                          GraphDefinitionIdentity identity,
                                                          CanonicalGraphMl canonical) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.Unavailable("the definition store is down")));
        }

        @Override
        public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.NotFound(key)));
        }

        @Override
        public CompletionStage<StoredGraphDefinition> resolve(String tenantId,
                                                              GraphDefinitionIdentity identity) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.Unavailable("the definition store is down")));
        }

        @Override
        public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Void> remove(GraphDefinitionKey key) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.NotFound(key)));
        }

        @Override
        public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public void close() {
        }
    }
}
