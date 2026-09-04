package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentityKind;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionSubmission;
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
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end claim the definition store exists to make: an execution accepted by one process is
 * recoverable by the next one, from the durable store alone, with no caller re-upload.
 *
 * <p>These assertions sit above the port rather than inside the conformance suite because what they
 * check is the <em>acceptance path's</em> obligation, not an adapter's: that the document is
 * committed before the pin, that a refused definition write refuses the acceptance with it, and that
 * the pin an execution carries addresses bytes the store actually holds.</p>
 */
class DefaultRavenrootApplicationGraphDefinitionTest {

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
    void aServerThatLosesEveryInMemoryGraphObjectRecoversTheDocumentByteForByte() {
        Path database = databaseDirectory.resolve("store.db");
        UUID executionId = UUID.randomUUID();
        ExecutionSubmission submission;
        ExecutionKey executionKey;

        // The accepting process.
        try (var executions = new SqliteExecutionStore(database, Clock.systemUTC());
             var definitions = new SqliteGraphDefinitionStore(database, Clock.systemUTC(),
                     GraphDefinitionReferences.NONE)) {
            var application = applicationWith(executions, definitions);
            submission = application.startGraphMl(TestIdentities.TENANT_A, executionId,
                    new ByteArrayInputStream(graphBytes()), "payload");
            executionKey = new ExecutionKey(TestIdentities.TENANT_A.tenantId(),
                    submission.processInstanceId());
            application.close();
        }

        // Nothing in memory survives that block: the application, its GraphManager, its runner and
        // both store connections are gone. This is the "lost every in-memory graph object, restarted"
        // half of the claim, and the only thing carried across is the pin the caller was handed.
        try (var executions = new SqliteExecutionStore(database, Clock.systemUTC());
             var definitions = new SqliteGraphDefinitionStore(database, Clock.systemUTC(),
                     GraphDefinitionReferences.NONE)) {
            String pin = executions.load(executionKey).toCompletableFuture().join()
                    .graphVersionPin().reference();
            assertEquals(submission.graphVersion(), pin,
                    "the pin the caller was handed is the address the definition is filed under; if "
                            + "those two identities ever diverge, recovery has to guess which one to "
                            + "look up");

            StoredGraphDefinition recovered = definitions.load(
                    new GraphDefinitionKey(executionKey.tenantId(), new GraphContentId(pin)))
                    .toCompletableFuture().join();

            assertArrayEquals(graphBytes(), recovered.canonical().bytes(),
                    "byte for byte, and without asking the caller for the document again");
            assertEquals(GraphDefinitionIdentity.forSubmission(new GraphContentId(pin)),
                    recovered.identity());
        }
    }

    @Test
    void acceptanceFailsAndRecordsNoExecutionWhenTheDefinitionCannotBeCommitted() {
        var executions = new InMemoryExecutionStore();
        // Pinned, so the assertion below can name the process instance that would have been created
        // and prove no row exists for it. Asserting only that no lease is held would pass against an
        // implementation that wrote the instance and then failed to lease it, which is the exact
        // half-committed state this ordering exists to make unreachable.
        UUID processInstanceId = UUID.randomUUID();
        var application = applicationWith(executions, new RefusingDefinitionStore(),
                fixedIdentities(processInstanceId));

        var failure = assertThrows(GraphDefinitionStoreException.class,
                () -> application.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                        new ByteArrayInputStream(graphBytes()), "payload"));
        assertInstanceOf(GraphDefinitionStoreFailure.Unavailable.class, failure.failure());

        var executionKey = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), processInstanceId);
        var storeFailure = assertThrows(CompletionException.class,
                () -> executions.load(executionKey).toCompletableFuture().join());
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                ExecutionStoreException.unwrap(storeFailure).failure(),
                "the definition write is refused before the acceptance write is attempted, so no "
                        + "process instance was ever created");
        assertEquals(0, executions.leases(TestIdentities.TENANT_A.tenantId())
                        .toCompletableFuture().join().size(),
                "and nothing holds a lease on an instance that does not exist");
        application.close();
    }

    @Test
    void anOversizedDocumentIsRefusedBeforeAnyExecutionIsRecorded() {
        var executions = new InMemoryExecutionStore();
        // A bound below the document, so the refusal is the store's classified one rather than an
        // ingest rejection: this asserts the acceptance path propagates the store's verdict.
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC(),
                GraphDefinitionReferences.NONE, graphBytes().length - 1);
        var application = applicationWith(executions, definitions);

        var failure = assertThrows(GraphDefinitionStoreException.class,
                () -> application.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                        new ByteArrayInputStream(graphBytes()), "payload"));
        var tooLarge = assertInstanceOf(GraphDefinitionStoreFailure.DefinitionTooLarge.class,
                failure.failure());
        assertEquals(graphBytes().length, tooLarge.actualBytes());
        application.close();
    }

    @Test
    void repeatedSubmissionsOfOneDocumentConvergeOnOneStoredDefinition() {
        var executions = new InMemoryExecutionStore();
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        var application = applicationWith(executions, definitions);

        ExecutionSubmission first = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        ExecutionSubmission second = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");

        assertEquals(first.graphVersion(), second.graphVersion());
        var key = new GraphDefinitionKey(TestIdentities.TENANT_A.tenantId(),
                new GraphContentId(first.graphVersion()));
        StoredGraphDefinition stored = definitions.load(key).toCompletableFuture().join();
        assertArrayEquals(graphBytes(), stored.canonical().bytes());

        // Two accepted executions, one definition, and the second acceptance did not restamp it.
        assertEquals(stored.storedAt(), definitions.load(key).toCompletableFuture().join().storedAt());
        application.close();
    }

    @Test
    void anApplicationComposedWithoutADefinitionStoreKeepsItsEarlierBehaviour() {
        var executions = new InMemoryExecutionStore();
        var application = applicationWith(executions, null);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");

        assertNotNull(submission.graphVersion());
        assertFalse(submission.graphVersion().isBlank(),
                "an embedded caller composing no persistence at all is still a supported mode, and "
                        + "must not start failing because a definition store is now available");
        application.close();
    }

    @Test
    void aRetainedExecutionKeepsItsDefinitionAliveThroughRetention() {
        var executions = new InMemoryExecutionStore();
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC(),
                executions.graphDefinitionReferences());
        var application = applicationWith(executions, definitions);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        var key = new GraphDefinitionKey(TestIdentities.TENANT_A.tenantId(),
                new GraphContentId(submission.graphVersion()));

        assertEquals(0L, definitions.purgeUnreferencedDefinitions(TestIdentities.TENANT_A.tenantId())
                .toCompletableFuture().join());
        assertTrue(definitions.contains(key).toCompletableFuture().join(),
                "an accepted execution still names this definition, so retention must leave it");
        application.close();
    }

    private DefaultRavenrootApplication applicationWith(ExecutionStore executions,
                                                        GraphDefinitionStore definitions) {
        return applicationWith(executions, definitions,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids());
    }

    private DefaultRavenrootApplication applicationWith(ExecutionStore executions,
                                                        GraphDefinitionStore definitions,
                                                        ExecutionIdentitySource identities) {
        return new DefaultRavenrootApplication(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                identities, executions, 0, UnknownBehaviorPolicy.passThrough(), definitions);
    }

    /** Fixes the process-instance identity so a test can assert about the row that was not written. */
    private static ExecutionIdentitySource fixedIdentities(UUID processInstanceId) {
        return kind -> kind == ExecutionIdentityKind.PROCESS_INSTANCE
                ? processInstanceId
                : UUID.randomUUID();
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
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
