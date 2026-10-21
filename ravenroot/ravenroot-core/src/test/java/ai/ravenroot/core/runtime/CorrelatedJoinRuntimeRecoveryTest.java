package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual source admissions, journal writes and independent continuation dispatch. */
class CorrelatedJoinRuntimeRecoveryTest {
    @TempDir Path directory;
    private static final ai.ravenroot.api.security.SecurityContext IDENTITY = TestIdentities.TENANT_A;
    static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="js" for="graph" attr.name="join.semantics" attr.type="string"/>
              <key id="cm" for="node" attr.name="correlation.mode" attr.type="string"/>
              <key id="cs" for="node" attr.name="correlation.selector" attr.type="string"/>
              <key id="cc" for="node" attr.name="correlation.contributionSelector" attr.type="string"/>
              <key id="jm" for="node" attr.name="correlatedJoin.mode" attr.type="string"/>
              <key id="jc" for="node" attr.name="correlatedJoin.expectedCount" attr.type="int"/>
              <key id="mf" for="node" attr.name="correlatedJoin.manifestSelector" attr.type="string"/>
              <key id="seal" for="node" attr.name="correlatedJoin.sealSelector" attr.type="string"/>
              <key id="producer" for="node" attr.name="correlatedJoin.trustedProducerEntries" attr.type="string"/>
              <graph id="source-local" edgedefault="directed">
                <data key="js">declared</data>
                <node id="start"><data key="kind">start</data></node>
                <node id="a"><data key="kind">behavior</data><data key="behavior">source</data>
                  <data key="cm">ACCEPT_OR_CREATE</data><data key="cs">/operation</data><data key="cc">/partition</data></node>
                <node id="producer"><data key="kind">behavior</data><data key="behavior">source</data>
                  <data key="cm">ACCEPT_OR_CREATE</data><data key="cs">/operation</data></node>
                <node id="join"><data key="kind">behavior</data><data key="behavior">probe</data>
                  <data key="jm">FIXED_COUNT</data><data key="jc">6</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="end"/>
                <edge source="a" target="join"/><edge source="producer" target="join"/>
                <edge source="join" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void committedReadySurvivesLostResponseAndStoreReopenWithoutAnotherEvent() throws Exception {
        Path database = directory.resolve("ready-crash.db");
        var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
        var effects = new CopyOnWriteArrayList<String>();
        var monitor = new ExecutionMonitor();
        var originals = terminals(monitor, 6);
        var readyCommitted = new CountDownLatch(1);
        CorrelatedJoinKey key;
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            ExecutionStore lostResponse = proxy(store, (method, args) -> {
                Object result = invoke(store, method, args);
                if (method.getName().equals("compareAndSetCorrelatedJoin")
                        && ((CorrelatedJoinRecord) args[0]).phase() == CorrelatedJoinRecord.Phase.READY) {
                    return ((CompletionStage<?>) result).thenCompose(committed -> {
                        readyCommitted.countDown();
                        return CompletableFuture.failedFuture(new ExecutionStoreException(
                                new ExecutionStoreFailure.OutcomeUnknown(new ExecutionKey(IDENTITY.tenantId(),
                                        ((CorrelatedJoinRecord) args[0]).openedBy().processInstanceId()),
                                        "simulated response loss after READY commit")));
                    });
                }
                return result;
            });
            var deployment = deploy(engine, lostResponse, contexts, effects, monitor, GRAPH);
            for (int i = 1; i <= 6; i++) offer(contexts, "a", input("recover", i), "event-" + i);
            assertTrue(readyCommitted.await(10, TimeUnit.SECONDS));
            assertTrue(originals.await(10, TimeUnit.SECONDS));
            key = key(monitor, "recover");
            assertEquals(CorrelatedJoinRecord.Phase.READY, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            assertTrue(effects.isEmpty(), "the producer never received the READY response");
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        var recovered = new ExecutionMonitor();
        var continuation = terminals(recovered, 1);
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var acknowledged = new CountDownLatch(1);
            var observed = observePhase(store, CorrelatedJoinRecord.Phase.DISPATCHED, acknowledged);
            var deployment = deploy(engine, observed, contexts, effects, recovered, GRAPH);
            assertTrue(continuation.await(10, TimeUnit.SECONDS));
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(1, effects.size());
            var actual = store.executionTraversal(new ExecutionKey(IDENTITY.tenantId(), key.continuationProcessId()),
                    key.continuationTraversalId()).toCompletableFuture().get().orElseThrow();
            assertEquals(TraversalStatus.COMPLETED, actual.status());
            assertEquals(CorrelatedJoinRecord.Phase.DISPATCHED, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void concurrentFinalContributionsAndDuplicatesReleaseExactlyOneContinuation() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("concurrent.db"), Clock.systemUTC());
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var originalsAndContinuation = terminals(monitor, 9);
            var acknowledged = new CountDownLatch(1);
            var deployment = deploy(engine, observePhase(store, CorrelatedJoinRecord.Phase.DISPATCHED, acknowledged), contexts, effects, monitor, GRAPH);
            for (int i = 1; i <= 4; i++) offer(contexts, "a", input("concurrent", i), "event-" + i);
            var barrier = new CyclicBarrier(4);
            var tasks = new ArrayList<Future<?>>();
            for (int index = 0; index < 4; index++) {
                int n = index;
                tasks.add(threads.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    offer(contexts, "a", input("concurrent", 5 + n % 2), "final-transport-" + n);
                    return null;
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertTrue(originalsAndContinuation.await(10, TimeUnit.SECONDS));
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            var key = key(monitor, "concurrent");
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            assertEquals(6, record.contributions().size());
            assertEquals(1, effects.size());
            assertEquals(1, monitor.eventsAfter(0).stream().filter(event -> event.type() == ExecutionEventType.EXECUTION_STARTED
                    && event.processInstanceId().equals(key.continuationProcessId())).count());
            for (var item : record.contributions().values()) {
                var original = store.executionTraversal(new ExecutionKey(IDENTITY.tenantId(), item.processInstanceId()),
                        item.traversalId()).toCompletableFuture().get().orElseThrow();
                assertEquals("a", original.entryNodeId());
                assertNotEquals(key.continuationProcessId(), original.processInstanceId());
                var visit = store.executionVisits(new ExecutionKey(IDENTITY.tenantId(), item.processInstanceId()),
                        item.traversalId(), Optional.of("join"), Optional.empty(), 10).toCompletableFuture().get().items().getFirst();
                assertTrue(Set.of("correlated-ready", "correlated-waiting").contains(visit.outcome()));
            }
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void onlyAuthoredProducerCanAnnounceOrSealAndExpectationNeverShrinks() throws Exception {
        String dynamic = GRAPH.replace("<data key=\"jm\">FIXED_COUNT</data><data key=\"jc\">6</data>", """
                <data key="jm">DYNAMIC_MANIFEST</data><data key="mf">/expected</data>
                <data key="seal">/sealed</data><data key="producer">["producer"]</data>
                """);
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("trusted.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var settled = terminals(monitor, 7);
            var completed = new LinkedBlockingQueue<ExecutionEvent>();
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) completed.add(event); });
            var acknowledged = new CountDownLatch(1);
            var deployment = deploy(engine, observePhase(store, CorrelatedJoinRecord.Phase.DISPATCHED, acknowledged), contexts, effects, monitor, dynamic);
            offer(contexts, "a", Map.of("operation", "trusted", "partition", "part-1", "expected", List.of("part-1"), "sealed", true), "untrusted-forgery");
            assertNotNull(completed.poll(5, TimeUnit.SECONDS));
            var key = key(monitor, "trusted");
            var first = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            assertFalse(first.sealed()); assertTrue(first.expectedIds().isEmpty()); assertTrue(effects.isEmpty());
            offer(contexts, "producer", Map.of("operation", "trusted", "expected", List.of("part-1", "part-2", "part-3"), "sealed", false), "manifest");
            assertNotNull(completed.poll(5, TimeUnit.SECONDS));
            offer(contexts, "producer", Map.of("operation", "trusted", "expected", List.of("part-1"), "sealed", false), "smaller-manifest");
            assertNotNull(completed.poll(5, TimeUnit.SECONDS));
            assertEquals(Set.of("part-1", "part-2", "part-3"), store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().expectedIds());
            offer(contexts, "a", input("trusted", 3), "part-3");
            assertNotNull(completed.poll(5, TimeUnit.SECONDS));
            offer(contexts, "a", input("trusted", 2), "part-2");
            assertNotNull(completed.poll(5, TimeUnit.SECONDS));
            assertTrue(effects.isEmpty(), "all results without producer seal remain pending");
            offer(contexts, "producer", Map.of("operation", "trusted", "expected", List.of("part-1", "part-2", "part-3"), "sealed", true), "seal");
            assertTrue(settled.await(10, TimeUnit.SECONDS));
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(1, effects.size());
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            assertTrue(record.sealed()); assertEquals(3, record.contributions().size());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void aggregatePayloadRefusalSettlesJoinWithoutCreatingGhostContinuation() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("aggregate.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var originals = terminals(monitor, 6);
            var failed = new CountDownLatch(1);
            var deployment = deploy(engine, observePhase(store, CorrelatedJoinRecord.Phase.FAILED, failed), contexts, effects, monitor, GRAPH);
            for (int i = 1; i <= 6; i++) offer(contexts, "a", Map.of("operation", "aggregate", "partition", "part-" + i,
                    "data", Map.of("first", "x".repeat(30_000), "second", "y".repeat(30_000))), "part-" + i);
            assertTrue(originals.await(10, TimeUnit.SECONDS));
            assertTrue(failed.await(5, TimeUnit.SECONDS), "permanent payload refusal must not remain READY");
            var key = key(monitor, "aggregate");
            assertEquals(CorrelatedJoinRecord.Phase.FAILED, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            assertEquals(BusinessHistory.State.FAILED, store.businessHistory(key.scope(), "aggregate").toCompletableFuture().get().orElseThrow().state());
            assertTrue(store.executionTraversal(new ExecutionKey(IDENTITY.tenantId(), key.continuationProcessId()),
                    key.continuationTraversalId()).toCompletableFuture().get().isEmpty());
            assertTrue(effects.isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void retiredReleaseCannotDispatchAndResumeReconcilesSameContinuation() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("generation.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var readyRead = new CountDownLatch(1);
            var releaseRead = new CompletableFuture<Void>();
            var gatedOnce = new AtomicBoolean();
            var acknowledged = new CountDownLatch(1);
            ExecutionStore observed = observePhase(store, CorrelatedJoinRecord.Phase.DISPATCHED, acknowledged);
            ExecutionStore gated = proxy(observed, (method, args) -> {
                Object result = invoke(observed, method, args);
                if (method.getName().equals("correlatedJoin")) return ((CompletionStage<?>) result).thenCompose(value -> {
                    if (value instanceof Optional<?> item && item.isPresent()
                            && ((CorrelatedJoinRecord) item.orElseThrow()).phase() == CorrelatedJoinRecord.Phase.READY
                            && gatedOnce.compareAndSet(false, true)) {
                        readyRead.countDown();
                        return releaseRead.thenApply(ignored -> value);
                    }
                    return CompletableFuture.completedFuture(value);
                });
                return result;
            });
            var deployment = deploy(engine, gated, contexts, effects, monitor, GRAPH);
            for (int i = 1; i <= 6; i++) offer(contexts, "a", input("generation", i), "part-" + i);
            assertTrue(readyRead.await(5, TimeUnit.SECONDS));
            deployment.closeAdmission(2).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(effects.isEmpty());
            // Deliberately reopen while the old generation still owns the coalesced release slot.
            deployment.openAdmission(3).toCompletableFuture().get(5, TimeUnit.SECONDS);
            releaseRead.complete(null);
            assertTrue(acknowledged.await(10, TimeUnit.SECONDS));
            var key = key(monitor, "generation");
            assertEquals(1, effects.size());
            assertEquals(key.continuationProcessId(), monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.EXECUTION_STARTED && key.continuationProcessId().equals(event.processInstanceId()))
                    .findFirst().orElseThrow().processInstanceId());
            assertEquals(CorrelatedJoinRecord.Phase.DISPATCHED, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void cancellationWinsAgainstReadyReleaseAndSurvivesRestart() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("cancel.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var readyRead = new CountDownLatch(1);
            var releaseRead = new CompletableFuture<Void>();
            var gatedOnce = new AtomicBoolean();
            var cancellationObserved = new CountDownLatch(1);
            ExecutionStore gated = proxy(store, (method, args) -> {
                Object result = invoke(store, method, args);
                if (method.getName().equals("correlatedJoin")) return ((CompletionStage<?>) result).thenCompose(value -> {
                    if (value instanceof Optional<?> item && item.isPresent()
                            && ((CorrelatedJoinRecord) item.orElseThrow()).phase() == CorrelatedJoinRecord.Phase.READY
                            && gatedOnce.compareAndSet(false, true)) {
                        readyRead.countDown(); return releaseRead.thenApply(ignored -> value);
                    }
                    return CompletableFuture.completedFuture(value);
                });
                if (method.getName().equals("businessHistory")) return ((CompletionStage<?>) result).thenApply(value -> {
                    if (value instanceof Optional<?> item && item.isPresent()
                            && ((BusinessHistory) item.orElseThrow()).lifecycle() == BusinessHistory.Lifecycle.CANCELLED)
                        cancellationObserved.countDown();
                    return value;
                });
                return result;
            });
            var deployment = deploy(engine, gated, contexts, effects, monitor, GRAPH);
            for (int i = 1; i <= 6; i++) offer(contexts, "a", input("cancel", i), "part-" + i);
            assertTrue(readyRead.await(5, TimeUnit.SECONDS));
            var key = key(monitor, "cancel");
            var history = store.businessHistory(key.scope(), "cancel").toCompletableFuture().get().orElseThrow();
            store.closeBusinessHistory(key.scope(), "cancel", history.revision(), BusinessHistory.Lifecycle.CANCELLED).toCompletableFuture().get();
            releaseRead.complete(null);
            assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            deployment = deploy(engine, store, contexts, effects, monitor, GRAPH);
            assertEquals(CorrelatedJoinRecord.Phase.CANCELLED, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            assertTrue(effects.isEmpty());
            assertTrue(store.executionTraversal(new ExecutionKey(IDENTITY.tenantId(), key.continuationProcessId()),
                    key.continuationTraversalId()).toCompletableFuture().get().isEmpty());
            assertInstanceOf(IngressReceipt.Refused.class, contexts.get("a").ingress().offerDurably(IDENTITY,
                    IngressTarget.node("a"), input("cancel", 7), "a", "late-after-cancel"));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void preManifestResultsStopAtConfiguredBoundWithoutTrustingForgedSeal() throws Exception {
        var defaults = BusinessHistoryPolicy.DEFAULTS;
        var bounded = new BusinessHistoryPolicy(defaults.maxHistoriesPerScope(), defaults.maxOpenJoinsPerHistory(),
                defaults.maxRetainedJoinsPerHistory(), 3, defaults.maxJoinPayloadBytes(), defaults.maxPageSize(),
                defaults.historyRetention(), defaults.joinDeadline(), defaults.selection());
        String dynamic = GRAPH.replace("<data key=\"jm\">FIXED_COUNT</data><data key=\"jc\">6</data>", """
                <data key="jm">DYNAMIC_MANIFEST</data><data key="mf">/expected</data>
                <data key="seal">/sealed</data><data key="producer">["producer"]</data>
                """);
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("premanifest.db"),
                Clock.systemUTC(), ai.ravenroot.persistence.sqlite.SqliteStoreConfig.DEFAULTS.withBusinessHistories(bounded))) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var settled = terminals(monitor, 4);
            var deployment = deploy(engine, store, contexts, effects, monitor, dynamic);
            for (int i = 1; i <= 4; i++) offer(contexts, "a", input("bounded", i), "part-" + i);
            assertTrue(settled.await(10, TimeUnit.SECONDS));
            var key = key(monitor, "bounded");
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            assertEquals(3, record.contributions().size());
            assertEquals(CorrelatedJoinRecord.Phase.OPEN, record.phase());
            assertFalse(record.sealed()); assertTrue(record.expectedIds().isEmpty()); assertTrue(effects.isEmpty());
            assertEquals(1, monitor.eventsAfter(0).stream().filter(event -> event.type() == ExecutionEventType.EXECUTION_FAILED).count());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void expiredCollectionCannotReleaseAfterRestartAndRetainsMemberFacts() throws Exception {
        var instant = new AtomicReference<>(java.time.Instant.now());
        Clock storeClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return instant.get(); }
        };
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("deadline.db"), storeClock)) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var settled = terminals(monitor, 5);
            var expired = new CountDownLatch(1);
            var observed = observePhase(store, CorrelatedJoinRecord.Phase.EXPIRED, expired);
            var deployment = SourceLocalTraversalTest.deployment(engine, observed, contexts, effects, monitor, GRAPH, storeClock);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (int i = 1; i <= 5; i++) offer(contexts, "a", input("deadline", i), "part-" + i);
            assertTrue(settled.await(10, TimeUnit.SECONDS));
            var key = key(monitor, "deadline");
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            instant.set(record.deadlineAt().plusSeconds(1));
            deployment = SourceLocalTraversalTest.deployment(engine, observed, contexts, effects, monitor, GRAPH, storeClock);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(expired.await(5, TimeUnit.SECONDS));
            var summary = store.correlatedJoins(key.scope(), "deadline", Set.of(), Optional.empty(), 10)
                    .toCompletableFuture().get().items().getFirst();
            assertEquals(CorrelatedJoinRecord.Phase.EXPIRED, summary.phase());
            assertEquals(5, store.correlatedJoinMembers(key, JoinMemberFilter.RECEIVED, Optional.empty(), 10)
                    .toCompletableFuture().get().items().size());
            assertEquals(BusinessHistory.State.INCOMPLETE_HISTORY,
                    store.businessHistory(key.scope(), "deadline").toCompletableFuture().get().orElseThrow().state());
            assertTrue(effects.isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void drainDoesNotInventBusinessCompletionAndRetiredSourceCannotFinishRound() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("drain.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var settled = terminals(monitor, 5);
            var acknowledged = new CountDownLatch(1);
            var observed = observePhase(store, CorrelatedJoinRecord.Phase.DISPATCHED, acknowledged);
            var deployment = deploy(engine, observed, contexts, effects, monitor, GRAPH);
            var retired = contexts.get("a");
            for (int i = 1; i <= 5; i++) offer(contexts, "a", input("drain", i), "part-" + i);
            assertTrue(settled.await(10, TimeUnit.SECONDS));
            var key = key(monitor, "drain");
            assertTrue(deployment.drain(java.time.Duration.ofSeconds(2), 2).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(CorrelatedJoinRecord.Phase.OPEN, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().phase());
            assertEquals(BusinessHistory.State.AWAITING_EXTERNAL_EVENT,
                    store.businessHistory(key.scope(), "drain").toCompletableFuture().get().orElseThrow().state());
            assertInstanceOf(IngressReceipt.Refused.class, retired.ingress().offerDurably(IDENTITY,
                    IngressTarget.node("a"), input("drain", 6), "a", "retired-final"));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            deployment = deploy(engine, observed, contexts, effects, monitor, GRAPH);
            assertInstanceOf(IngressReceipt.Refused.class, retired.ingress().offerDurably(IDENTITY,
                    IngressTarget.node("a"), input("drain", 6), "a", "retired-after-stop"));
            offer(contexts, "a", input("drain", 6), "fresh-final");
            assertTrue(acknowledged.await(10, TimeUnit.SECONDS));
            assertEquals(1, effects.size());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static DefaultGraphDeployment deploy(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> contexts, List<String> effects, ExecutionMonitor monitor, String graph) throws Exception {
        var deployment = SourceLocalTraversalTest.deployment(engine, store, contexts, effects, monitor, graph);
        deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return deployment;
    }
    private static Map<String, Object> input(String operation, int part) { return Map.of("operation", operation, "partition", "part-" + part); }
    private static void offer(Map<String, InboundSourceContext> contexts, String source, Object input, String transport) {
        var receipt = contexts.get(source).ingress().offerDurably(IDENTITY, IngressTarget.node(source), input, source, transport);
        assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt, receipt.toString());
    }
    private static CountDownLatch terminals(ExecutionMonitor monitor, int count) {
        var latch = new CountDownLatch(count);
        monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) latch.countDown(); });
        return latch;
    }
    private static CorrelatedJoinKey key(ExecutionMonitor monitor, String operation) {
        var first = monitor.eventsAfter(0).stream().filter(e -> e.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow();
        return new CorrelatedJoinKey(new BusinessScope(IDENTITY.tenantId(), "source-local", first.graphVersion()), operation, "join", "0");
    }
    private interface Interceptor { Object invoke(Method method, Object[] args) throws Throwable; }
    private static ExecutionStore proxy(ExecutionStore store, Interceptor interceptor) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (object, method, args) -> interceptor.invoke(method, args));
    }
    private static Object invoke(ExecutionStore store, Method method, Object[] args) throws Throwable {
        try { return method.invoke(store, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }
    private static ExecutionStore observePhase(ExecutionStore store, CorrelatedJoinRecord.Phase phase, CountDownLatch latch) {
        return proxy(store, (method, args) -> {
            Object result = invoke(store, method, args);
            if (method.getName().equals("compareAndSetCorrelatedJoin") && ((CorrelatedJoinRecord) args[0]).phase() == phase)
                return ((CompletionStage<?>) result).thenApply(value -> { latch.countDown(); return value; });
            return result;
        });
    }
}
