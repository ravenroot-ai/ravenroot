package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real adapter contract for atomic bounded retained input custody and original SOURCE checkpoints. */
@Timeout(120)
public abstract class DeploymentEntryContract {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    protected abstract ExecutionStore createStore(String id, Clock clock, BusinessHistoryPolicy policy);
    private ExecutionStore createStore(String id) { return createStore(id, Clock.fixed(NOW, ZoneOffset.UTC), BusinessHistoryPolicy.DEFAULTS); }

    @Test void inputAndBusinessRelationCommitTogetherAndRetryRetainsOriginalIdentityAndTarget() throws Exception {
        try (var store = createStore(id())) {
            var entry = entry("operation", UUID.randomUUID(), "source-a", "original-input");
            var accepted = await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(DeploymentEntryAdmission.Outcome.ACCEPTED, accepted.outcome());
            assertEquals(entry, accepted.intent().orElseThrow());
            assertEquals(entry.context(), await(store.correlatedTraversal("tenant", entry.processInstanceId(), entry.traversalId())).orElseThrow().context());
            var retried = copy(entry, "source-a", "original-input", new SecurityContext("new-transport-request", "tenant", "source-owner", PrincipalType.WORKLOAD, "issuer"));
            var duplicate = await(store.acceptDeploymentEntry(retried, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(DeploymentEntryAdmission.Outcome.PENDING, duplicate.outcome());
            assertEquals("original-request", duplicate.intent().orElseThrow().identity().requestId());
            assertThrows(Exception.class, () -> await(store.acceptDeploymentEntry(copy(entry, "source-b", "original-input", entry.identity()), CorrelationMapping.Mode.ACCEPT_OR_CREATE)));
            assertThrows(Exception.class, () -> await(store.acceptDeploymentEntry(copy(entry, "source-a", "changed-input", entry.identity()), CorrelationMapping.Mode.ACCEPT_OR_CREATE)));
            assertEquals(entry, await(store.deploymentEntry(entry.key())).orElseThrow());
        }
    }

    @Test void requireExistingRefusalLeavesNeitherInputNorBusinessHistory() throws Exception {
        try (var store = createStore(id())) {
            var entry = entry("absent-history", UUID.randomUUID(), "source", "input");
            assertThrows(Exception.class, () -> await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.REQUIRE_EXISTING)));
            assertTrue(await(store.deploymentEntry(entry.key())).isEmpty());
            assertTrue(await(store.businessHistory(entry.scope(), "absent-history")).isEmpty());
        }
    }

    @Test void legacyAndSettledAdmissionNeverFabricateAnEmptyRetainedInputOrReopenWork() throws Exception {
        try (var store = createStore(id())) {
            var entry = entry("legacy", UUID.randomUUID(), "source", "not-the-original-input");
            await(store.recordCorrelatedTraversal(entry.traversal(), CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var unavailable = await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(DeploymentEntryAdmission.Outcome.INPUT_UNAVAILABLE, unavailable.outcome()); assertTrue(unavailable.intent().isEmpty());
            await(store.settleCorrelatedTraversal("tenant", entry.processInstanceId(), entry.traversalId(), TraversalStatus.COMPLETED, null));
            var settled = await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(DeploymentEntryAdmission.Outcome.SETTLED, settled.outcome()); assertTrue(settled.intent().isEmpty());
            assertTrue(await(store.deploymentEntry(entry.key())).isEmpty());
        }
    }

    @Test void concurrentAdmissionsEnforceScopeCountCapInsideTheTransaction() throws Exception {
        var d = BusinessHistoryPolicy.DEFAULTS;
        var policy = new BusinessHistoryPolicy(d.maxHistoriesPerScope(), d.maxOpenJoinsPerHistory(), d.maxRetainedJoinsPerHistory(),
                d.maxContributionsPerJoin(), d.maxJoinPayloadBytes(), d.maxPageSize(), d.historyRetention(), d.joinDeadline(),
                d.selection(), d.maxJoinCasAttempts(), d.readTimeout(), d.response(), 2, 1024 * 1024);
        try (var store = createStore(id(), Clock.fixed(NOW, ZoneOffset.UTC), policy); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(6); var futures = new ArrayList<Future<Boolean>>();
            var entries = new ArrayList<DeploymentEntryIntent>();
            for (int i = 0; i < 6; i++) {
                var entry = entry("bounded-" + i, UUID.randomUUID(), "source", "input"); entries.add(entry);
                futures.add(workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try { await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.ACCEPT_OR_CREATE)); return true; }
                    catch (ExecutionException limited) { return false; }
                }));
            }
            int accepted = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted);
            var page = await(store.pendingDeploymentEntries(entries.getFirst().scope(), Optional.empty(), 10));
            assertEquals(2, page.items().size());
            assertEquals(2, await(store.businessHistories(entries.getFirst().scope(), new BusinessHistoryQuery(
                    Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 10))).items().size());
        }
    }

    @Test void thousandPendingInputsHaveScalarKeysetPagesAndForeignCursorsCannotChangeScope() throws Exception {
        try (var store = createStore(id())) {
            var entries = new HashMap<UUID, DeploymentEntryIntent>();
            for (int i = 0; i < 1000; i++) {
                var entry = entry("ingestion-" + i, UUID.randomUUID(), "source", "private-custody-input-marker");
                entries.put(entry.processInstanceId(), entry);
                await(store.acceptDeploymentEntry(entry, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            }
            var scope = entries.values().iterator().next().scope();
            Optional<String> cursor = Optional.empty(); var selected = new HashSet<UUID>(); int pages = 0;
            do {
                var page = await(store.pendingDeploymentEntries(scope, cursor, 37));
                assertTrue(page.items().size() <= 37);
                for (var row : page.items()) {
                    assertTrue(selected.add(row.processInstanceId())); assertEquals("source", row.entryNodeId());
                    assertFalse(row.toString().contains("private-custody-input-marker")); assertFalse(row.entryStarted());
                }
                if (pages == 0) {
                    assertTrue(page.nextCursor().isPresent());
                    assertThrows(Exception.class, () -> await(store.pendingDeploymentEntries(new BusinessScope("foreign", "deployment", "graph-v1"), page.nextCursor(), 37)));
                    assertThrows(Exception.class, () -> await(store.pendingDeploymentEntries(scope, page.nextCursor(), 38)));
                }
                cursor = page.nextCursor(); pages++;
            } while (cursor.isPresent());
            assertEquals(1000, selected.size()); assertEquals(28, pages);
            var original = entries.values().iterator().next();
            assertEquals(original, await(store.deploymentEntry(original.key())).orElseThrow());
            assertTrue(await(store.deploymentEntry(new ExecutionKey("foreign", original.processInstanceId()))).isEmpty());
        }
    }

    @Test void durableReopenRetainsOriginalInputContextAndAuthenticatedOwner() throws Exception {
        String id = id(); var original = entry("reopened", UUID.randomUUID(), "source", "original-retained-input");
        try (var store = createStore(id)) {
            assumeTrue(store.supports(StoreCapability.DURABLE), "reference memory store cannot reopen durable custody");
            await(store.acceptDeploymentEntry(original, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
        }
        try (var store = createStore(id)) {
            assertEquals(original, await(store.deploymentEntry(original.key())).orElseThrow());
        }
    }
    @Test void sourceStartAndCompletionCommitOriginalCausalCheckpointWithoutRetiringInput() throws Exception { checkpointContract(false); }
    @Test void explicitCancellationSettlesHistoryAndRetiresInputInTheSameTransaction() throws Exception { checkpointContract(true); }
    private void checkpointContract(boolean cancel) throws Exception {
        String id = id(); var original = entry("checkpoint", UUID.randomUUID(), "source", "retained-through-source");
        UUID invocation = UUID.randomUUID(), attempt = UUID.randomUUID(), accepted = UUID.randomUUID(), started = UUID.randomUUID(), completed = UUID.randomUUID();
        try (var store = createStore(id)) {
            await(store.acceptDeploymentEntry(original, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var traversal = new Traversal(original.traversalId(), "source", TraversalStatus.ACCEPTED, Map.of());
            var created = await(store.apply(ExecutionBatch.to(original.key()).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(original.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                            Map.of(original.traversalId(), traversal)), new GraphVersionPin("graph-v1")))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(original.traversalId(), TraversalStatus.RUNNING)).build()));
            var lease = await(store.claim(original.key(), "entry-runtime", Duration.ofSeconds(30)));
            var rootStarted = ExecutionBatch.to(original.key()).expecting(RevisionExpectation.exactly(created.revision())).fencedBy(lease)
                    .apply(new ExecutionTransition.InvocationAdded(original.traversalId(), new NodeInvocation(invocation, "source", Set.of(),
                            NodeInvocationStatus.SCHEDULED, List.of(), ai.ravenroot.api.execution.NodeCommand.PROCESS)))
                    .apply(new ExecutionTransition.InvocationTransitioned(original.traversalId(), invocation, NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptAdded(original.traversalId(), invocation, new NodeAttempt(attempt, 1, NodeAttemptStatus.SCHEDULED)))
                    .apply(new ExecutionTransition.AttemptTransitioned(original.traversalId(), invocation, attempt, NodeAttemptStatus.RUNNING))
                    .publish(EventEnvelope.of(accepted, "tenant", "EXECUTION_STARTED", original.processInstanceId(), original.traversalId(),
                            null, null, null, "request", "graph-v1", NOW, OpaquePayload.empty("application/json")))
                    .publish(EventEnvelope.of(started, "tenant", "NODE_STARTED", original.processInstanceId(), original.traversalId(),
                            invocation, attempt, accepted, "request", "graph-v1", NOW, OpaquePayload.empty("application/json")))
                    .deploymentEntryRuntimeState(new DeploymentEntryRuntimeState(1, 0, 30, false));
            var running = await(store.apply(rootStarted.build()));
            var checkpoint = await(store.deploymentEntry(original.key())).orElseThrow();
            assertEquals(original.input(), checkpoint.input()); assertEquals(invocation, checkpoint.checkpoint().invocationId());
            assertEquals(started, checkpoint.checkpoint().startedEventId()); assertNull(checkpoint.checkpoint().completedEventId());
            var finished = await(store.apply(ExecutionBatch.to(original.key()).expecting(RevisionExpectation.exactly(running.revision())).fencedBy(lease)
                    .apply(new ExecutionTransition.AttemptTransitioned(original.traversalId(), invocation, attempt, NodeAttemptStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationTransitioned(original.traversalId(), invocation, NodeInvocationStatus.COMPLETED))
                    .publish(EventEnvelope.of(completed, "tenant", "NODE_COMPLETED", original.processInstanceId(), original.traversalId(), invocation,
                            attempt, started, "request", "graph-v1", NOW, NodeOutcomeEventData.of("continue")))
                    .deploymentEntryRuntimeState(new DeploymentEntryRuntimeState(1, 0, 30, false)).build()));
            var retained = await(store.deploymentEntry(original.key())).orElseThrow();
            assertEquals(original.input(), retained.input()); assertEquals(completed, retained.checkpoint().completedEventId());
            assertThrows(Exception.class, () -> await(store.apply(ExecutionBatch.to(original.key()).expecting(RevisionExpectation.exactly(finished.revision())).fencedBy(lease)
                    .recordOrigin(ExecutionOrigin.of("deployment", "workload", "request"))
                    .deploymentEntryRuntimeState(new DeploymentEntryRuntimeState(0, 0, 0, false)).build())));
            assertEquals(retained, await(store.deploymentEntry(original.key())).orElseThrow(), "failed checkpoint cannot partially replace custody");
            var status = cancel ? TraversalStatus.FAILED : TraversalStatus.COMPLETED;
            var reason = cancel ? ExecutionTerminationReason.CANCELLED : null;
            var terminal = await(store.apply(ExecutionBatch.to(original.key()).expecting(RevisionExpectation.exactly(finished.revision())).fencedBy(lease)
                    .apply(new ExecutionTransition.TraversalTransitioned(original.traversalId(), status, reason))
                    .apply(new ExecutionTransition.ProcessTransitioned(cancel ? ProcessInstanceStatus.FAILED : ProcessInstanceStatus.COMPLETED, reason)).build()));
            assertTrue(terminal.state().status().terminal()); assertTrue(await(store.deploymentEntry(original.key())).isEmpty());
            var relation = await(store.correlatedTraversal("tenant", original.processInstanceId(), original.traversalId())).orElseThrow();
            assertEquals(status, relation.status()); assertEquals(reason, relation.terminationReason());
        }
    }

    @Test void codecIsStrictAndNeverAcceptsTrailingOrUnknownFormatBytes() {
        var original = entry("codec", UUID.randomUUID(), "source", "retained-input"); var encoded = DeploymentEntryCodec.encode(original);
        assertEquals(original, DeploymentEntryCodec.decode(encoded));
        var trailing = Arrays.copyOf(encoded, encoded.length + 1); assertThrows(IllegalArgumentException.class, () -> DeploymentEntryCodec.decode(trailing));
        encoded[3] = 7; assertThrows(IllegalArgumentException.class, () -> DeploymentEntryCodec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> DeploymentEntryCodec.decode(new byte[]{0, 0, 0}));
    }

    private static DeploymentEntryIntent entry(String operation, UUID process, String node, String input) {
        return new DeploymentEntryIntent(new BusinessScope("tenant", "deployment", "graph-v1"), process,
                UUID.nameUUIDFromBytes((process + ":traversal").getBytes(StandardCharsets.UTF_8)), node,
                new ApplicationCorrelationContext(operation, Optional.empty(), Optional.empty(), "0", Optional.empty()),
                new SecurityContext("original-request", "tenant", "source-owner", PrincipalType.WORKLOAD, "issuer"),
                OpaquePayload.of(("\"" + input + "\"").getBytes(StandardCharsets.UTF_8), "application/json"),
                NOW, NOW.plus(Duration.ofDays(1)), CorrelatedTraversal.Cause.SOURCE_EVENT, null, "", null);
    }
    private static DeploymentEntryIntent copy(DeploymentEntryIntent original, String node, String input, SecurityContext identity) {
        return new DeploymentEntryIntent(original.scope(), original.processInstanceId(), original.traversalId(), node,
                original.context(), identity, OpaquePayload.of(("\"" + input + "\"").getBytes(StandardCharsets.UTF_8), "application/json"),
                original.acceptedAt(), original.expiresAt(), original.cause(), null, "", null);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(30, TimeUnit.SECONDS); }
}
