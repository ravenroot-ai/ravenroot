package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentityKind;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyLimits;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
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
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><strong>What the shipped server composes, stated as of this change.</strong>
 * {@code DefaultRavenrootApplication.registerDeployment} passes its own execution store, definition
 * store and manifest service to every deployment it builds, so a server started with a durable store
 * does durably record and bind deployment-hosted traversals. That was not true when this class was
 * written and the paragraph here said so; it is stated again rather than deleted because a reader
 * checking whether these assertions cover the default composition needs the answer, and the answer
 * has changed. What still bounds them is the store: a deployment composed without an execution store
 * enters no durable-recording path at all, which is what the last test below pins.</p>
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

    private static final String ONE_EFFECT_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="one-effect" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="effect">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">record-effect</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="effect">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="e2" source="effect" target="end">
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
        DeploymentId deploymentId = DeploymentId.of("bound-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE)) {

            var deployment = deployment(deploymentId, engine, executions, definitions,
                    fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
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
        DeploymentId deploymentId = DeploymentId.of("manifest-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE);
             var manifestStore = new ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore(
                     database, clock(), ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE)) {

            BehaviorRegistry behaviors = ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()));
            var manifests = new ai.ravenroot.core.manifest.ExecutionManifestService(manifestStore,
                    ai.ravenroot.core.manifest.ExecutionManifestResolver.from(engine,
                            executions.capabilities(), behaviors,
                            UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null),
                    clock());
            var deployment = new DefaultGraphDeployment(deploymentId,
                    engine, behaviors, new ExecutionMonitor(), fixedIdentities(processInstanceId),
                    graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-" + UUID.randomUUID(),
                    Duration.ofSeconds(30),
                    RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                    definitions, GraphExecutionLimits.DEFAULTS, null, manifests);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
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
        DeploymentId deploymentId = DeploymentId.of("bound-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        var pinExistedWhenTheDocumentWasWritten = new AtomicReference<Boolean>();

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var real = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE)) {

            // Asked from inside the definition write itself, which is the only place the ordering is
            // observable without inferring it from timing. If the pin were written first, this would
            // find the instance already present.
            var definitions = new ProbingDefinitionStore(real, () -> pinExistedWhenTheDocumentWasWritten
                    .set(instanceExists(executions, IDENTITY.tenantId(), processInstanceId)));

            var deployment = deployment(deploymentId, engine, executions, definitions,
                    fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertInstanceOf(IngressReceipt.DurablyCommitted.class,
                    deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
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
        DeploymentId deploymentId = DeploymentId.of("bound-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);

        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock())) {

            var deployment = deployment(deploymentId, engine, executions, new RefusingDefinitionStore(),
                    fixedIdentities(processInstanceId));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                    "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.Ambiguous.class, receipt,
                    "the inbox may already be durable, so a later prerequisite refusal must require "
                            + "reconciliation and must not license an external acknowledgement");

            assertFalse(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "a refused document must refuse the traversal with it; an execution recorded here "
                            + "would be one whose graph is retained nowhere");
            assertEquals(0, executions.leases(IDENTITY.tenantId()).toCompletableFuture().join().size(),
                    "and nothing holds a lease on an instance that does not exist");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aNeverCompletingDefinitionWriteReturnsAmbiguousWithinOneAdmissionDeadline() throws Exception {
        Path database = databaseDirectory.resolve("definition-timeout.db");
        DeploymentId deploymentId = DeploymentId.of("definition-timeout-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var realDefinitions = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE)) {
            var definitions = new DelayedDefinitionStore(realDefinitions);
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, null,
                    fixedIdentities(processInstanceId), Duration.ofMillis(120));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            long started = System.nanoTime();
            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                    "poller-1", "key-A");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertInstanceOf(IngressReceipt.Ambiguous.class, receipt);
            assertFalse(receipt.acknowledgeable());
            assertTrue(elapsedMillis < 2_000, () -> "definition wait was not bounded: " + elapsedMillis + "ms");
            assertFalse(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "definition timeout happens before execution acceptance");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aConcurrentSameKeyOfferCannotStealTheFirstOffersAdmissionTicket() throws Exception {
        Path database = databaseDirectory.resolve("concurrent-same-key.db");
        DeploymentId deploymentId = DeploymentId.of("concurrent-same-key-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var realDefinitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE)) {
            var definitions = new DelayedDefinitionStore(realDefinitions);
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, null,
                    fixedIdentities(processInstanceId), Duration.ofSeconds(2));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<IngressReceipt> first = CompletableFuture.supplyAsync(() ->
                    deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                            "poller-1", "key-A"));
            assertTrue(definitions.entered.await(10, TimeUnit.SECONDS),
                    "the first offer must own the event while its definition write is unresolved");

            IngressReceipt concurrent = deployment.ingress().offerDurably(IDENTITY,
                    IngressTarget.node("start"), "payload", "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.Ambiguous.class, concurrent,
                    "a second same-key offer must not install or remove the owner's admission ticket");
            assertFalse(concurrent.acknowledgeable());

            definitions.release();
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, first.get(10, TimeUnit.SECONDS),
                    "the event owner must retain its dispatch ticket and complete admission");
            var page = executions.listProcessInstances(IDENTITY.tenantId(),
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(10))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, page.items().size(), "the same event may create exactly one execution");
            assertEquals(processInstanceId, page.items().getFirst().key().processInstanceId());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void unresolvedFirstNodeStartCannotExtendAdmissionOrDispatchAfterCancellation() throws Exception {
        Path database = databaseDirectory.resolve("delayed-node-start.db");
        DeploymentId deploymentId = DeploymentId.of("delayed-node-start-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        var effects = new AtomicInteger();
        var terminal = new CountDownLatch(1);
        var monitor = new ExecutionMonitor();
        var behaviors = ManualSourceFixture.register(new BehaviorRegistry()).register("record-effect", message -> {
            effects.incrementAndGet();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        try (var engine = new JoinTestEngine();
             var realExecutions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE);
             var subscription = monitor.subscribe(event -> {
                 if (processInstanceId.equals(event.processInstanceId())
                         && (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED
                         || event.type() == ExecutionEventType.EXECUTION_CANCELLED)) {
                     terminal.countDown();
                 }
             })) {
            var executions = new DelayedNodeStartedStore(realExecutions);
            byte[] graph = ManualSourceFixture.graph(ONE_EFFECT_GRAPH).getBytes(StandardCharsets.UTF_8);
            var deployment = new DefaultGraphDeployment(deploymentId, engine, behaviors, monitor,
                    fixedIdentities(processInstanceId), graph,
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions.store(),
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-delayed-node-start",
                    Duration.ofSeconds(30),
                    RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                    definitions, GraphExecutionLimits.DEFAULTS, null, null, null, null,
                    GraphRunner.DEFAULT_SHUTDOWN_BOUND, Duration.ofMillis(300));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            long started = System.nanoTime();
            IngressReceipt receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"),
                    "payload", "poller-1", "key-A");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt,
                    "execution acceptance already owns custody before runner node persistence starts");
            assertTrue(elapsedMillis < 2_000,
                    () -> "inline NODE_STARTED persistence retained admission for " + elapsedMillis + "ms");
            assertTrue(executions.entered.await(10, TimeUnit.SECONDS));
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            executions.release();
            assertTrue(terminal.await(10, TimeUnit.SECONDS),
                    "the late NODE_STARTED result must settle the cancelled traversal");
            assertEquals(0, effects.get(), "the business node cannot run after stop has settled");

            var redelivery = new DefaultGraphDeployment(deploymentId, engine, behaviors, monitor,
                    fixedIdentities(processInstanceId), graph,
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions.store(),
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-delayed-node-redelivery",
                    Duration.ofSeconds(30),
                    RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                    definitions, GraphExecutionLimits.DEFAULTS, null, null, null, null,
                    GraphRunner.DEFAULT_SHUTDOWN_BOUND, Duration.ofMillis(300));
            redelivery.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.Duplicate.class,
                    redelivery.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                            "poller-1", "key-A"));
            redelivery.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

            var journal = realExecutions.readJournal(IDENTITY.tenantId(), 0, 100)
                    .toCompletableFuture().join();
            assertEquals(1, journal.stream()
                    .filter(record -> "NODE_STARTED".equals(record.envelope().eventType())).count(),
                    "redelivery must not start a second node attempt");
            assertTrue(journal.stream()
                            .noneMatch(record -> "NODE_COMPLETED".equals(record.envelope().eventType())),
                    "a late NODE_STARTED completion cannot dispatch the cancelled node effect");
        }
    }

    @Test
    void barrierCancellationBeforeDeferredRunnerRegistrationPreventsNodeStart() throws Exception {
        Path database = databaseDirectory.resolve("deferred-runner-cancel.db");
        DeploymentId deploymentId = DeploymentId.of("deferred-runner-cancel-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(),
                     GraphDefinitionReferences.NONE)) {
            var handoff = new QueuedExecutor();
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, null,
                    fixedIdentities(processInstanceId), Duration.ofSeconds(2),
                    ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())), handoff);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertInstanceOf(IngressReceipt.DurablyCommitted.class,
                    deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                            "poller-1", "key-A"));
            assertTrue(handoff.submitted.await(10, TimeUnit.SECONDS));

            var barrier = deployment.barrier(2).toCompletableFuture();
            barrier.get(2, TimeUnit.SECONDS);
            assertEquals(1, deployment.endedByLastBarrier(),
                    "the admitted ticket stays visible while runner registration is deferred");
            handoff.runSubmitted();

            assertTrue(executions.readJournal(IDENTITY.tenantId(), 0, 100).toCompletableFuture().join()
                            .stream().noneMatch(record -> "NODE_STARTED".equals(record.envelope().eventType())),
                    "cancellation published before registration must prevent the first node write");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void lateDefinitionAndManifestCommitsConvergeOnRedeliveryWithoutDuplicateExecution() throws Exception {
        Path database = databaseDirectory.resolve("late-prerequisites.db");
        DeploymentId deploymentId = DeploymentId.of("late-prerequisites-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var realDefinitions = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE);
             var realManifests = new ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore(
                     database, clock(), ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE)) {
            var definitions = new DelayedDefinitionStore(realDefinitions);
            var manifests = new DelayedManifestStore(realManifests, DelayOperation.PIN);
            BehaviorRegistry behaviors = ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()));
            var service = manifestService(engine, executions, behaviors, manifests);
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, service,
                    fixedIdentities(processInstanceId), Duration.ofMillis(120), behaviors);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            int residentSources = engine.spawnCount();
            assertEquals(1, residentSources, "activation owns one passive SOURCE actor and dispatches no traversal");

            var first = assertInstanceOf(IngressReceipt.Ambiguous.class,
                    deployment.ingress().offerDurably(
                            IDENTITY, IngressTarget.node("start"), "payload", "poller-1", "key-A"));
            assertFalse(first.acknowledgeable(), () -> "ambiguous definition result: " + first.reason());
            assertEquals(0, executions.inboxRecordCount(IDENTITY.tenantId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "a SOURCE receipt cannot record delivery before its durable graph/input prerequisites resolve");
            assertFalse(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "an execution cannot exist before the definition becomes visible");
            assertEquals(residentSources, engine.spawnCount(),
                    "the unresolved definition must prevent traversal dispatch");
            definitions.release();
            var second = assertInstanceOf(IngressReceipt.Ambiguous.class,
                    deployment.ingress().offerDurably(
                            IDENTITY, IngressTarget.node("start"), "payload", "poller-1", "key-A"));
            assertFalse(second.acknowledgeable(), () -> "ambiguous manifest result: " + second.reason());
            assertEquals(0, executions.inboxRecordCount(IDENTITY.tenantId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "an unresolved manifest still cannot transfer SOURCE input custody or acknowledge delivery");
            assertFalse(instanceExists(executions, IDENTITY.tenantId(), processInstanceId),
                    "an execution cannot exist before the manifest becomes visible");
            assertEquals(residentSources, engine.spawnCount(),
                    "the unresolved manifest must prevent traversal dispatch");
            manifests.release();
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

            var repairedService = manifestService(engine, executions, behaviors, realManifests);
            var handoff = new QueuedExecutor();
            var repairedDeployment = boundedDeployment(deploymentId, engine, executions, realDefinitions,
                    repairedService, fixedIdentities(processInstanceId),
                    DefaultGraphDeployment.DEFAULT_STORE_CALL_BOUND, behaviors, handoff);
            repairedDeployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            int repairedResidents = engine.spawnCount();
            assertEquals(residentSources + 1, repairedResidents, "reactivation owns one replacement SOURCE actor");

            var repaired = repairedDeployment.ingress().offerDurably(
                    IDENTITY, IngressTarget.node("start"), "payload", "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, repaired,
                    () -> "the prerequisites committed before the single repaired redelivery takes original custody; "
                            + "actual receipt: " + repaired
                            + (repaired instanceof IngressReceipt.Ambiguous ambiguous
                                    ? "; ambiguous reason: " + ambiguous.reason() : ""));
            var page = executions.listProcessInstances(IDENTITY.tenantId(),
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(10))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, page.items().size());
            assertEquals(processInstanceId, page.items().getFirst().key().processInstanceId());
            assertTrue(handoff.submitted.await(10, TimeUnit.SECONDS),
                    "the repaired delivery must submit its one durable traversal handoff");
            assertEquals(1, handoff.submissionCount(),
                    "the single repaired delivery must submit exactly one durable traversal handoff");
            assertEquals(repairedResidents, engine.spawnCount(),
                    "the deterministic executor must hold the intended traversal until its explicit boundary");
            handoff.runSubmitted();
            awaitExecutionStarted(executions, IDENTITY.tenantId(), 1,
                    "the repaired delivery must record exactly one execution start");
            awaitSpawnCount(engine, repairedResidents + 1,
                    "the repaired delivery must emit its SOURCE in the runtime and run END exactly once");
            repairedDeployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            // The committed source receipt survives redelivery without a second handoff.
            assertEquals(1, executions.inboxRecordCount(IDENTITY.tenantId()).toCompletableFuture().join());
            assertEquals(1, executionStartedCount(executions, IDENTITY.tenantId()),
                    "orderly stop must leave exactly one execution start");
            assertEquals(repairedResidents + 1, engine.spawnCount(),
                    "orderly stop must leave exactly one completed SOURCE-to-END traversal");
            assertEquals(1, handoff.submissionCount(),
                    "the repaired delivery must not submit an additional traversal handoff");
            assertFalse(handoff.hasPendingCommand(),
                    "the one submitted traversal handoff must have been consumed");
        }
    }

    @Test
    void lateExecutionCreateCommitReconcilesWithoutCreatingASecondExecution() throws Exception {
        Path database = databaseDirectory.resolve("late-execution-create.db");
        DeploymentId deploymentId = DeploymentId.of("late-execution-create-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        var delayed = new AtomicBoolean(true);
        var actual = new AtomicReference<CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance>>();
        var visible = new CompletableFuture<ai.ravenroot.api.persistence.StoredProcessInstance>();
        try (var engine = new JoinTestEngine();
             var realExecutions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE)) {
            ExecutionStore executions = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(
                    ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("apply")
                                && args[0] instanceof ai.ravenroot.api.persistence.ExecutionBatch batch
                                && batch.transitions().stream().anyMatch(transition -> transition
                                        instanceof ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated)
                                && delayed.compareAndSet(true, false)) {
                            @SuppressWarnings("unchecked")
                            CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> committed =
                                    (CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance>)
                                            method.invoke(realExecutions, args);
                            actual.set(committed);
                            return visible;
                        }
                        try {
                            return method.invoke(realExecutions, args);
                        } catch (java.lang.reflect.InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, null,
                    fixedIdentities(processInstanceId), Duration.ofMillis(120));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            IngressReceipt first = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"),
                    "payload", "poller-1", "key-A");
            assertInstanceOf(IngressReceipt.Ambiguous.class, first,
                    "a timed-out create may have committed and cannot license acknowledgement");
            assertFalse(first.acknowledgeable());

            CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> committed = actual.get();
            assertNotNull(committed, "the execution create must have reached the real durable store");
            committed.whenComplete((stored, failure) -> {
                if (failure == null) visible.complete(stored);
                else visible.completeExceptionally(failure);
            });

            assertInstanceOf(IngressReceipt.Duplicate.class, deployment.ingress().offerDurably(
                    IDENTITY, IngressTarget.node("start"), "payload", "poller-1", "key-A"),
                    "redelivery must adopt the late committed identity rather than create another execution");
            var page = realExecutions.listProcessInstances(IDENTITY.tenantId(),
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(10))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, page.items().size());
            assertEquals(processInstanceId, page.items().getFirst().key().processInstanceId());
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void hostedPolicyReadDoesNotHoldDeploymentLockAndBarrierCleanupReleasesRecorder() throws Exception {
        Path database = databaseDirectory.resolve("policy-read.db");
        DeploymentId deploymentId = DeploymentId.of("policy-read-" + UUID.randomUUID());
        UUID processInstanceId = durableProcessId(deploymentId);
        try (var engine = new JoinTestEngine();
             var executions = new SqliteExecutionStore(database, clock());
             var definitions = new SqliteGraphDefinitionStore(database, clock(), GraphDefinitionReferences.NONE);
             var realManifests = new ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore(
                     database, clock(), ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE)) {
            var manifests = new DelayedManifestStore(realManifests, DelayOperation.LOAD);
            BehaviorRegistry behaviors = ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()));
            var service = manifestService(engine, executions, behaviors, manifests);
            var deployment = boundedDeployment(deploymentId, engine, executions, definitions, service,
                    fixedIdentities(processInstanceId), Duration.ofSeconds(2), behaviors);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<IngressReceipt> offering = CompletableFuture.supplyAsync(() ->
                    deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
                            "poller-1", "key-A"));
            assertTrue(manifests.entered.await(10, TimeUnit.SECONDS), "policy load must reach the delayed store");
            deployment.barrier(2).toCompletableFuture().get(500, TimeUnit.MILLISECONDS);
            manifests.release();
            assertInstanceOf(IngressReceipt.Refused.class, offering.get(10, TimeUnit.SECONDS),
                    "the barrier's closed ticket stays closed after policy persistence returns");
            assertTrue(executions.leases(IDENTITY.tenantId()).toCompletableFuture().get(10, TimeUnit.SECONDS).isEmpty(),
                    "policy failure/refusal must release the recorder lease rather than waiting for expiry");

            deployment.openAdmission(3).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.Duplicate.class, deployment.ingress().offerDurably(
                    IDENTITY, IngressTarget.node("start"), "payload", "poller-1", "key-A"));
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
                    engine, ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())),
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, definitions);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.node("start"), "payload",
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

    private static long executionStartedCount(ExecutionStore executions, String tenantId) throws Exception {
        return executions.readJournal(tenantId, 0, 100).toCompletableFuture().get(10, TimeUnit.SECONDS)
                .stream()
                .filter(record -> ExecutionEventType.EXECUTION_STARTED.name()
                        .equals(record.envelope().eventType()))
                .count();
    }

    private static void awaitExecutionStarted(ExecutionStore executions, String tenantId, long expected,
            String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long observed = executionStartedCount(executions, tenantId);
        while (observed < expected && System.nanoTime() < deadline) {
            Thread.sleep(2);
            observed = executionStartedCount(executions, tenantId);
        }
        assertEquals(expected, observed, message);
    }

    private static void awaitSpawnCount(JoinTestEngine engine, int expected, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.spawnCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(expected, engine.spawnCount(), message);
    }

    private DefaultGraphDeployment deployment(DeploymentId deploymentId, JoinTestEngine engine, ExecutionStore executions,
                                              GraphDefinitionStore definitions,
                                              ExecutionIdentitySource identities) {
        return new DefaultGraphDeployment(deploymentId, engine,
                ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())), new ExecutionMonitor(),
                identities, graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                executions, DefaultGraphDeployment.DEFAULT_INBOX_RETENTION,
                "worker-" + UUID.randomUUID(), Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                definitions);
    }

    private static ai.ravenroot.core.manifest.ExecutionManifestService manifestService(
            JoinTestEngine engine, ExecutionStore executions, BehaviorRegistry behaviors,
            ai.ravenroot.api.persistence.ExecutionManifestStore store) {
        return new ai.ravenroot.core.manifest.ExecutionManifestService(store,
                ai.ravenroot.core.manifest.ExecutionManifestResolver.from(engine,
                        executions.capabilities(), behaviors, UnknownBehaviorPolicy.passThrough(),
                        GraphExecutionLimits.DEFAULTS, null), clock());
    }

    private DefaultGraphDeployment boundedDeployment(DeploymentId deploymentId, JoinTestEngine engine,
            ExecutionStore executions, GraphDefinitionStore definitions,
            ai.ravenroot.core.manifest.ExecutionManifestService manifests,
            ExecutionIdentitySource identities, Duration bound) {
        return boundedDeployment(deploymentId, engine, executions, definitions, manifests, identities, bound,
                ManualSourceFixture.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())));
    }

    private DefaultGraphDeployment boundedDeployment(DeploymentId deploymentId, JoinTestEngine engine,
            ExecutionStore executions, GraphDefinitionStore definitions,
            ai.ravenroot.core.manifest.ExecutionManifestService manifests,
            ExecutionIdentitySource identities, Duration bound, BehaviorRegistry behaviors) {
        return new DefaultGraphDeployment(deploymentId, engine, behaviors, new ExecutionMonitor(), identities,
                graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-" + UUID.randomUUID(),
                Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                definitions, GraphExecutionLimits.DEFAULTS, null, null, manifests, null,
                GraphRunner.DEFAULT_SHUTDOWN_BOUND, bound);
    }

    private DefaultGraphDeployment boundedDeployment(DeploymentId deploymentId, JoinTestEngine engine,
            ExecutionStore executions, GraphDefinitionStore definitions,
            ai.ravenroot.core.manifest.ExecutionManifestService manifests,
            ExecutionIdentitySource identities, Duration bound, BehaviorRegistry behaviors,
            Executor durableRunnerExecutor) {
        return new DefaultGraphDeployment(deploymentId, engine, behaviors, new ExecutionMonitor(), identities,
                graphBytes(), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "worker-" + UUID.randomUUID(),
                Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                definitions, GraphExecutionLimits.DEFAULTS, null, null, manifests, null,
                GraphRunner.DEFAULT_SHUTDOWN_BOUND, bound, durableRunnerExecutor);
    }

    private static UUID durableProcessId(DeploymentId deploymentId) {
        return UUID.nameUUIDFromBytes((IDENTITY.tenantId() + '\0' + deploymentId.value()
                + "/source/c3RhcnQ/poller-1" + '\0' + "key-A").getBytes(StandardCharsets.UTF_8));
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
        return ManualSourceFixture.graph(GRAPH).getBytes(StandardCharsets.UTF_8);
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

    /** Returns the first completed write through a gate so the caller observes an unknown outcome. */
    private static final class DelayedDefinitionStore implements GraphDefinitionStore {
        private final GraphDefinitionStore delegate;
        private final AtomicBoolean delayed = new AtomicBoolean();
        private final AtomicReference<CompletionStage<StoredGraphDefinition>> actual = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<StoredGraphDefinition>> gate = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        private DelayedDefinitionStore(GraphDefinitionStore delegate) {
            this.delegate = delegate;
        }

        void release() throws Exception {
            CompletionStage<StoredGraphDefinition> write = actual.get();
            CompletableFuture<StoredGraphDefinition> visible = gate.get();
            assertNotNull(write, "the delayed definition write was never entered");
            assertNotNull(visible, "the delayed definition gate was never installed");
            StoredGraphDefinition stored = write.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(visible.complete(stored), "the delayed definition gate was already completed");
            assertEquals(stored, visible.get(10, TimeUnit.SECONDS),
                    "the committed definition must be visible before release returns");
        }

        @Override public Set<StoreCapability> capabilities() { return delegate.capabilities(); }
        @Override public int maxDefinitionBytes() { return delegate.maxDefinitionBytes(); }

        @Override
        public CompletionStage<StoredGraphDefinition> put(String tenantId,
                GraphDefinitionIdentity identity, CanonicalGraphMl canonical) {
            CompletionStage<StoredGraphDefinition> write = delegate.put(tenantId, identity, canonical);
            if (!delayed.compareAndSet(false, true)) return write;
            var visible = new CompletableFuture<StoredGraphDefinition>();
            actual.set(write);
            gate.set(visible);
            entered.countDown();
            return visible;
        }

        @Override public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
            return delegate.load(key);
        }
        @Override public CompletionStage<StoredGraphDefinition> resolve(String tenantId,
                GraphDefinitionIdentity identity) { return delegate.resolve(tenantId, identity); }
        @Override public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
            return delegate.contains(key);
        }
        @Override public CompletionStage<Void> remove(GraphDefinitionKey key) {
            return delegate.remove(key);
        }
        @Override public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
            return delegate.purgeUnreferencedDefinitions(tenantId);
        }
        @Override public void close() { }
    }

    private enum DelayOperation { PIN, LOAD }

    private static final class QueuedExecutor implements Executor {
        private final AtomicReference<Runnable> command = new AtomicReference<>();
        private final CountDownLatch submitted = new CountDownLatch(1);
        private final AtomicInteger submissions = new AtomicInteger();

        @Override public void execute(Runnable submittedCommand) {
            submissions.incrementAndGet();
            assertTrue(command.compareAndSet(null, submittedCommand));
            submitted.countDown();
        }

        private int submissionCount() {
            return submissions.get();
        }

        private boolean hasPendingCommand() {
            return command.get() != null;
        }

        private void runSubmitted() {
            Runnable submittedCommand = command.getAndSet(null);
            assertNotNull(submittedCommand);
            submittedCommand.run();
        }
    }

    /** Commits NODE_STARTED but withholds its completion so inline runner startup is deterministic. */
    private static final class DelayedNodeStartedStore {
        private final ExecutionStore delegate;
        private final ExecutionStore store;
        private final AtomicBoolean delayed = new AtomicBoolean();
        private final AtomicReference<CompletionStage<StoredProcessInstance>> actual = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<StoredProcessInstance>> gate = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        private DelayedNodeStartedStore(ExecutionStore delegate) {
            this.delegate = delegate;
            this.store = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[] { ExecutionStore.class }, this::invoke);
        }

        private ExecutionStore store() {
            return store;
        }

        private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            try {
                Object result = method.invoke(delegate, arguments);
                if (!method.getName().equals("apply") || arguments == null || arguments.length != 1
                        || !(arguments[0] instanceof ExecutionBatch batch)
                        || batch.events().stream().noneMatch(event -> "NODE_STARTED".equals(event.eventType()))
                        || !delayed.compareAndSet(false, true)) {
                    return result;
                }
                @SuppressWarnings("unchecked")
                CompletionStage<StoredProcessInstance> committed =
                        (CompletionStage<StoredProcessInstance>) result;
                var visible = new CompletableFuture<StoredProcessInstance>();
                actual.set(committed);
                gate.set(visible);
                entered.countDown();
                return visible;
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }

        private void release() {
            CompletionStage<StoredProcessInstance> committed = actual.get();
            CompletableFuture<StoredProcessInstance> visible = gate.get();
            assertNotNull(committed);
            assertNotNull(visible);
            committed.whenComplete((stored, failure) -> {
                if (failure == null) visible.complete(stored);
                else visible.completeExceptionally(failure);
            });
        }
    }

    /** Delays one manifest result after the delegate has already committed or loaded it. */
    private static final class DelayedManifestStore implements ExecutionManifestStore {
        private final ExecutionManifestStore delegate;
        private final DelayOperation operation;
        private final AtomicBoolean delayed = new AtomicBoolean();
        private final AtomicReference<CompletionStage<StoredExecutionManifest>> actual = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<StoredExecutionManifest>> gate = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        private DelayedManifestStore(ExecutionManifestStore delegate, DelayOperation operation) {
            this.delegate = delegate;
            this.operation = operation;
        }

        void release() throws Exception {
            CompletionStage<StoredExecutionManifest> result = actual.get();
            CompletableFuture<StoredExecutionManifest> visible = gate.get();
            assertNotNull(result, "the delayed manifest operation was never entered");
            assertNotNull(visible, "the delayed manifest gate was never installed");
            StoredExecutionManifest stored = result.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(visible.complete(stored), "the delayed manifest gate was already completed");
            assertEquals(stored, visible.get(10, TimeUnit.SECONDS),
                    "the committed manifest result must be visible before release returns");
        }

        private CompletionStage<StoredExecutionManifest> maybeDelay(DelayOperation candidate,
                CompletionStage<StoredExecutionManifest> result) {
            if (operation != candidate || !delayed.compareAndSet(false, true)) return result;
            var visible = new CompletableFuture<StoredExecutionManifest>();
            actual.set(result);
            gate.set(visible);
            entered.countDown();
            return visible;
        }

        @Override public Set<StoreCapability> capabilities() { return delegate.capabilities(); }
        @Override public CompletionStage<StoredExecutionManifest> pin(ExecutionManifest manifest) {
            return maybeDelay(DelayOperation.PIN, delegate.pin(manifest));
        }
        @Override public CompletionStage<StoredExecutionManifest> load(ExecutionKey key) {
            return maybeDelay(DelayOperation.LOAD, delegate.load(key));
        }
        @Override public CompletionStage<Boolean> contains(ExecutionKey key) {
            return delegate.contains(key);
        }
        @Override public CompletionStage<Void> remove(ExecutionKey key) { return delegate.remove(key); }
        @Override public CompletionStage<Long> purgeUnreferencedManifests(String tenantId) {
            return delegate.purgeUnreferencedManifests(tenantId);
        }
        @Override public void close() { }
    }
}
