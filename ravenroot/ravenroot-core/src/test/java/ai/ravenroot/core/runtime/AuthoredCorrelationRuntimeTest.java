package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import ai.ravenroot.core.graph.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned authored close/derive operations, rather than store-only simulations. */
@Timeout(45)
class AuthoredCorrelationRuntimeTest {
    @TempDir Path directory;
    private static final ai.ravenroot.api.security.SecurityContext OWNER = TestIdentities.TENANT_A;
    private static final String GRAPH = """
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
          <key id="kind" for="node" attr.name="kind" attr.type="string"/>
          <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
          <key id="cm" for="node" attr.name="correlation.mode" attr.type="string"/>
          <key id="cs" for="node" attr.name="correlation.selector" attr.type="string"/>
          <key id="cc" for="node" attr.name="correlation.contributionSelector" attr.type="string"/>
          <key id="reason" for="node" attr.name="auditReason" attr.type="string"/>
          <key id="jm" for="node" attr.name="correlatedJoin.mode" attr.type="string"/>
          <key id="jc" for="node" attr.name="correlatedJoin.expectedCount" attr.type="int"/>
          <key id="js" for="graph" attr.name="join.semantics" attr.type="string"/>
          <graph id="source-local" edgedefault="directed"><data key="js">declared</data>
            <node id="start"><data key="kind">start</data></node>
            <node id="source"><data key="kind">behavior</data><data key="behavior">source</data>
              <data key="cm">ACCEPT_OR_CREATE</data><data key="cs">/operation</data><data key="cc">/partition</data></node>
            <node id="closeSource"><data key="kind">behavior</data><data key="behavior">source</data>
              <data key="cm">REQUIRE_EXISTING</data><data key="cs">/operation</data></node>
            <node id="gate"><data key="kind">behavior</data><data key="behavior">gate</data></node>
            <node id="join"><data key="kind">behavior</data><data key="behavior">probe</data>
              <data key="jm">FIXED_COUNT</data><data key="jc">2</data></node>
            <node id="close"><data key="kind">behavior</data><data key="behavior">correlation-close</data><data key="reason">all-inputs-received</data></node>
            <node id="end"><data key="kind">end</data></node>
            <node id="error"><data key="kind">error</data></node>
            <edge source="start" target="end"/>
            <edge source="source" target="gate"/><edge source="gate" target="join"/><edge source="join" target="end"/>
            <edge source="closeSource" target="close"/><edge source="close" target="end"/>
          </graph>
        </graphml>
        """;

    @Test void closeBarrierAllowsPreAdmittedArrivalAndItsJoinContinuationToSettle() throws Exception {
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("close.db"), Clock.systemUTC())) {
            var sources = new ConcurrentHashMap<String, InboundSourceContext>(); var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor(); var terminals = new LinkedBlockingQueue<ExecutionEvent>();
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) terminals.add(event); });
            var secondEntered = new CountDownLatch(1); var second = new CompletableFuture<NodeResult>();
            var deployment = SourceLocalTraversalTest.deployment(engine, store, sources, effects, monitor, GRAPH, Clock.systemUTC(), registry ->
                authored(registry).register("gate", message -> {
                    if (((Map<?, ?>) message.payload()).get("partition").equals("part-2")) { secondEntered.countDown(); return second; }
                    return CompletableFuture.completedFuture(new NodeResult("continue", message.payload(), message.attributes()));
                }));
            deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            committed(sources, "source", Map.of("operation", "closing", "partition", "part-1"), "one");
            assertNotNull(terminals.poll(5, TimeUnit.SECONDS));
            committed(sources, "source", Map.of("operation", "closing", "partition", "part-2"), "two");
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            committed(sources, "closeSource", Map.of("operation", "closing"), "close");
            assertNotNull(terminals.poll(5, TimeUnit.SECONDS));
            var scope = scope(monitor); var closing = store.businessHistory(scope, "closing").toCompletableFuture().get().orElseThrow();
            assertNotNull(closing.closureSignal()); assertEquals(BusinessHistory.Lifecycle.OPEN, closing.lifecycle());
            assertEquals(1, closing.runningTraversals());
            assertInstanceOf(IngressReceipt.Refused.class, sources.get("source").ingress().offerDurably(OWNER, IngressTarget.node("source"),
                    Map.of("operation", "closing", "partition", "late"), "source", "late"));
            second.complete(new NodeResult("continue", Map.of("operation", "closing", "partition", "part-2"), Map.of()));
            assertNotNull(terminals.poll(5, TimeUnit.SECONDS)); assertNotNull(terminals.poll(15, TimeUnit.SECONDS));
            var key = new CorrelatedJoinKey(scope, "closing", "join", "0");
            var release = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            // Join acknowledgement follows its terminal monitor event; observe the persisted barrier.
            for (int i = 0; i < 100 && release.phase() == CorrelatedJoinRecord.Phase.READY; i++) {
                Thread.sleep(10); release = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            }
            assertEquals(CorrelatedJoinRecord.Phase.DISPATCHED, release.phase());
            var closed = store.businessHistory(scope, "closing").toCompletableFuture().get().orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, closed.lifecycle()); assertEquals(4, closed.completedTraversals());
            assertEquals(1, effects.size()); assertEquals("all-inputs-received", closed.closureSignal().auditReason());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void ordinaryTransformKeepsOriginalContextAndExplicitDeriveCreatesAuditedIndependentTraversal() throws Exception {
        String graph = derivedGraph();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("derive.db"), Clock.systemUTC())) {
            var sources = new ConcurrentHashMap<String, InboundSourceContext>(); var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor(); var originals = new LinkedBlockingQueue<NodeMessage>(); var child = new CompletableFuture<NodeMessage>();
            var terminal = new CountDownLatch(2); monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) terminal.countDown(); });
            var deployment = SourceLocalTraversalTest.deployment(engine, store, sources, effects, monitor, graph, Clock.systemUTC(), registry ->
                authored(registry).register("transform", message -> {
                    originals.add(message);
                    return CompletableFuture.completedFuture(new NodeResult("continue", Map.of("computed", "ingestion-42"), Map.of("mail", "structured")));
                }).register("publish-probe", message -> { child.complete(message); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }));
            deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            committed(sources, "source", Map.of("operation", "transport-business", "partition", "mail-1"), "mail-event");
            var delivered = child.get(20, TimeUnit.SECONDS); assertTrue(terminal.await(5, TimeUnit.SECONDS));
            var original = originals.poll(); assertEquals("transport-business", original.applicationCorrelation().applicationCorrelationId());
            assertEquals("ingestion-42", delivered.applicationCorrelation().applicationCorrelationId()); assertEquals("structured", delivered.attributes().get("mail"));
            assertNotEquals(original.processInstanceId(), delivered.processInstanceId()); assertNotEquals(original.traversalId(), delivered.traversalId());
            assertTrue(delivered.parentInvocationIds().isEmpty(), "cross-process derivation uses its own lineage");
            var relation = store.correlatedTraversal(OWNER.tenantId(), delivered.processInstanceId(), delivered.traversalId()).toCompletableFuture().get().orElseThrow();
            assertEquals(CorrelatedTraversal.Cause.DERIVED_CONTEXT, relation.cause()); assertEquals(original.processInstanceId(), relation.derivation().processInstanceId());
            assertEquals("normalized-mail-ingestion", relation.derivation().auditReason());
            var originalRelation = store.correlatedTraversal(OWNER.tenantId(), original.processInstanceId(), original.traversalId()).toCompletableFuture().get().orElseThrow();
            assertEquals("transport-business", originalRelation.context().applicationCorrelationId());
            assertTrue(store.deploymentEntry(new ExecutionKey(OWNER.tenantId(), delivered.processInstanceId())).toCompletableFuture().get().isEmpty());
            assertEquals(1, monitor.eventsAfter(0).stream().filter(event -> "publish".equals(event.nodeId()) && event.type() == ExecutionEventType.NODE_STARTED).count());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    static String derivedGraph() {
        String graph = GRAPH.replace("<data key=\"behavior\">gate</data>", "<data key=\"behavior\">transform</data>")
            .replace("<data key=\"behavior\">probe</data>\n      <data key=\"jm\">FIXED_COUNT</data><data key=\"jc\">2</data>", "unused");
        // Replace the join definition directly; its single outgoing edge becomes the derived entry.
        int begin = graph.indexOf("<node id=\"join\">"); int end = graph.indexOf("</node>", begin) + "</node>".length();
        graph = graph.substring(0, begin) + "<node id=\"join\"><data key=\"kind\">behavior</data><data key=\"behavior\">correlation-derive</data>"
            + "<data key=\"reason\">normalized-mail-ingestion</data><data key=\"cm\">ACCEPT_OR_CREATE</data><data key=\"cs\">/computed</data></node>" + graph.substring(end);
        graph = graph.replace("<edge source=\"join\" target=\"end\"/>", "<edge source=\"join\" target=\"publish\"/><edge source=\"publish\" target=\"end\"/>")
            .replace("<node id=\"end\">", "<node id=\"publish\"><data key=\"kind\">behavior</data><data key=\"behavior\">publish-probe</data></node><node id=\"end\">");
        return graph;
    }

    @Test void legacyUncorrelatedCloseAndDeriveRefuseWithoutInventingBusinessIdentity() throws Exception {
        for (String operation : List.of(CorrelationOperationProperties.CLOSE, CorrelationOperationProperties.DERIVE)) {
            try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve(operation + "-legacy.db"), Clock.systemUTC())) {
                var graph = new GraphDefinition(List.of(GraphNode.start("start"), new GraphNode("operation", NodeKind.BEHAVIOR, operation,
                        Map.of("auditReason", "explicit-legacy-test")), GraphNode.end("end"), GraphNode.error("error")),
                        List.of(GraphEdge.to("start", "operation"), GraphEdge.to("operation", "end")));
                UUID process = UUID.randomUUID(), traversal = UUID.randomUUID(); var key = new ExecutionKey(OWNER.tenantId(), process);
                var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                        .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(process, ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("legacy-v1")))
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                        .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING)).build()).toCompletableFuture().get();
                try (var manager = GraphManager.from(graph); var runner = new GraphRunner(manager, engine, authored(new BehaviorRegistry()), new ExecutionMonitor());
                        var recorder = ExecutionRecorder.open(store, key, "legacy", Duration.ofSeconds(30), created.revision())) {
                    assertThrows(Exception.class, () -> runner.execute(OWNER, process, traversal, Map.of("value", "unchanged"), "legacy-v1", "legacy-deployment", traversal.toString(), recorder)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS));
                }
                var stored = store.load(key).toCompletableFuture().get();
                assertEquals(TraversalStatus.FAILED, stored.state().traversals().get(traversal).status());
                assertEquals(NodeInvocationStatus.FAILED, stored.state().traversals().get(traversal).invocations().values().stream()
                        .filter(visit -> visit.nodeId().equals("operation")).findFirst().orElseThrow().status());
                assertTrue(store.correlatedTraversal(OWNER.tenantId(), process, traversal).toCompletableFuture().get().isEmpty());
                assertTrue(store.pendingDeploymentEntries(new BusinessScope(OWNER.tenantId(), "legacy-deployment", "legacy-v1"), Optional.empty(), 10)
                        .toCompletableFuture().get().items().isEmpty());
            }
        }
    }

    @Test void committedHandoffWithLostResponseReopensIntoOneOriginalChildWithoutProducerRetry() throws Exception {
        Path database = directory.resolve("derived-response-loss.db"); var sources = new ConcurrentHashMap<String, InboundSourceContext>();
        var input = Map.of("operation", "original", "partition", "mail-1"); var childEffects = new AtomicInteger();
        var child = new CompletableFuture<NodeMessage>() {
            @Override public boolean complete(NodeMessage message) { childEffects.incrementAndGet(); return super.complete(message); }
        };
        var committed = new CompletableFuture<DeploymentEntryIntent>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var lost = proxy(store, (method, args) -> {
                var result = invoke(store, method, args);
                if (method.getName().equals("apply") && ((ExecutionBatch) args[0]).derivedEntryHandoff().isPresent()) {
                    var handoff = ((ExecutionBatch) args[0]).derivedEntryHandoff().orElseThrow();
                    return ((CompletionStage<?>) result).thenCompose(value -> {
                        committed.complete(handoff.intent());
                        return CompletableFuture.failedFuture(new ExecutionStoreException(new ExecutionStoreFailure.OutcomeUnknown(
                                ((ExecutionBatch) args[0]).key(), "simulated lost derived handoff response after commit")));
                    });
                }
                return result;
            });
            var deployment = derivedDeployment(engine, lost, sources, child, Clock.systemUTC());
            deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            committed(sources, "source", input, "one-original-event"); var intent = committed.get(5, TimeUnit.SECONDS);
            assertTrue(store.deploymentEntry(intent.key()).toCompletableFuture().get().isPresent()); assertFalse(child.isDone());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        var intent = committed.get();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var originalKey = new ExecutionKey(OWNER.tenantId(), intent.derivedFrom().processInstanceId());
            var recoveryClaimed = new CountDownLatch(1);
            var releaseRecovery = new CompletableFuture<Void>();
            var cleanupTask = new CompletableFuture<Thread>();
            var originalLease = new AtomicReference<LeaseHandle>();
            var observed = proxy(store, (method, args) -> {
                var result = invoke(store, method, args);
                if (method.getName().equals("claim") && args[0].equals(originalKey))
                    return ((CompletionStage<?>) result).thenCompose(lease -> {
                        assertTrue(originalLease.compareAndSet(null, (LeaseHandle) lease));
                        recoveryClaimed.countDown();
                        return releaseRecovery.thenApply(ignored -> lease);
                    });
                if (method.getName().equals("release") && ((LeaseHandle) args[0]).key().equals(originalKey)) {
                    var lease = (LeaseHandle) args[0];
                    assertEquals(originalLease.get().workerId(), lease.workerId());
                    assertEquals(originalLease.get().fencingToken(), lease.fencingToken());
                    cleanupTask.complete(Thread.currentThread());
                }
                return result;
            });
            var deployment = derivedDeployment(engine, observed, sources, child, Clock.systemUTC());
            deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            try {
                assertTrue(recoveryClaimed.await(5, TimeUnit.SECONDS));
                var retained = store.deploymentEntry(originalKey).toCompletableFuture().get().orElseThrow();
                assertEquals(intent.derivedFrom().traversalId(), retained.traversalId());
                assertEquals(input, ai.ravenroot.api.payload.PayloadJson.read(retained.input().bytes(), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava());
                var owned = sources.get("source").ingress().offerDurably(OWNER, IngressTarget.node("source"), input, "source", "one-original-event");
                assertEquals(new IngressReceipt.Ambiguous("one-original-event", "the same durable traversal is already admitted"), owned);
                assertFalse(owned.acknowledgeable());
                assertEquals(retained, store.deploymentEntry(originalKey).toCompletableFuture().get().orElseThrow());
            } finally { releaseRecovery.complete(null); }
            // With this same-thread engine, teardown finishes on its per-task virtual worker.
            // A child handler or lease-release signal alone precedes admission cleanup; joining
            // that exact lease's worker is test synchronization, not a production receipt contract.
            var cleanup = cleanupTask.get(15, TimeUnit.SECONDS);
            assertTrue(cleanup.isVirtual());
            assertTrue(cleanup.join(Duration.ofSeconds(5)));
            var delivered = child.get(15, TimeUnit.SECONDS);
            assertEquals(intent.processInstanceId(), delivered.processInstanceId()); assertEquals(intent.traversalId(), delivered.traversalId());
            assertEquals(intent.context(), delivered.applicationCorrelation()); assertEquals("structured", delivered.attributes().get("mail"));
            assertEquals(Map.of("computed", "ingestion-42"), delivered.payload());
            var original = store.correlatedTraversal(OWNER.tenantId(), intent.derivedFrom().processInstanceId(), intent.derivedFrom().traversalId()).toCompletableFuture().get().orElseThrow();
            assertEquals("original", original.context().applicationCorrelationId());
            var originalVisit = store.load(new ExecutionKey(OWNER.tenantId(), intent.derivedFrom().processInstanceId())).toCompletableFuture().get().state()
                    .traversals().get(intent.derivedFrom().traversalId()).invocations().get(intent.derivedFrom().invocationId());
            assertEquals(NodeInvocationStatus.COMPLETED, originalVisit.status());
            assertEquals(intent.derivedFrom().attemptId(), originalVisit.attempts().getFirst().attemptId());
            assertInstanceOf(IngressReceipt.Duplicate.class, sources.get("source").ingress().offerDurably(OWNER, IngressTarget.node("source"), input, "source", "one-original-event"));
            assertEquals(1, store.correlatedTraversals(intent.scope(), intent.context().applicationCorrelationId(), Optional.empty(), 10).toCompletableFuture().get().items().size());
            assertEquals(1, childEffects.get());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void derivedChildExpirySettlesVisiblyBeforeAnyRealTargetEffect() throws Exception {
        var instant = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return instant.get(); }
        };
        var intent = new CompletableFuture<DeploymentEntryIntent>(); var settled = new CompletableFuture<ExecutionKey>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("derived-expiry.db"), clock)) {
            var observed = proxy(store, (method, args) -> {
                Object result = invoke(store, method, args);
                if (method.getName().equals("apply")) {
                    var batch = (ExecutionBatch) args[0];
                    if (batch.derivedEntryHandoff().isPresent()) return ((CompletionStage<?>) result).thenApply(value -> {
                        var child = batch.derivedEntryHandoff().orElseThrow().intent(); intent.complete(child); instant.set(child.expiresAt().plusSeconds(1)); return value;
                    });
                    if (intent.isDone() && batch.key().equals(intent.join().key())) return ((CompletionStage<?>) result).thenApply(value -> {
                        if (((StoredProcessInstance) value).state().status().terminal()) settled.complete(batch.key()); return value;
                    });
                }
                return result;
            });
            var child = new CompletableFuture<NodeMessage>(); var sources = new ConcurrentHashMap<String, InboundSourceContext>();
            var deployment = derivedDeployment(engine, observed, sources, child, clock); deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            committed(sources, "source", Map.of("operation", "expires", "partition", "mail-1"), "expiring-derived-event");
            assertEquals(intent.get(5, TimeUnit.SECONDS).key(), settled.get(15, TimeUnit.SECONDS)); assertFalse(child.isDone());
            assertEquals(TraversalStatus.FAILED, store.executionTraversal(intent.join().key(), intent.join().traversalId()).toCompletableFuture().get().orElseThrow().status());
            assertTrue(store.deploymentEntry(intent.join().key()).toCompletableFuture().get().isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    @Test void dynamicExpectationAuthorityCanNameOnlyAnAuthoredPinnedDerivationEntry() throws Exception {
        String graph = derivedGraph().replace("<graph id=\"source-local\"", "<key id=\"manifest\" for=\"node\" attr.name=\"correlatedJoin.manifestSelector\" attr.type=\"string\"/>"
                + "<key id=\"seal\" for=\"node\" attr.name=\"correlatedJoin.sealSelector\" attr.type=\"string\"/>"
                + "<key id=\"producer\" for=\"node\" attr.name=\"correlatedJoin.trustedProducerEntries\" attr.type=\"string\"/><graph id=\"source-local\"")
                .replace("<data key=\"behavior\">publish-probe</data>", "<data key=\"behavior\">publish-probe</data><data key=\"jm\">DYNAMIC_MANIFEST</data>"
                    + "<data key=\"manifest\">/expected</data><data key=\"seal\">/sealed</data><data key=\"producer\">[\"publish\"]</data>");
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("derived-producer.db"), Clock.systemUTC())) {
            var sources = new ConcurrentHashMap<String, InboundSourceContext>(); var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor(); var completed = new CountDownLatch(3); var child = new CompletableFuture<NodeMessage>();
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) completed.countDown(); });
            var deployment = SourceLocalTraversalTest.deployment(engine, store, sources, effects, monitor, graph, Clock.systemUTC(), registry ->
                    authored(registry).register("transform", message -> CompletableFuture.completedFuture(new NodeResult("continue",
                        Map.of("computed", "manifest-business", "expected", List.of(), "sealed", true), Map.of())))
                        .register("publish-probe", message -> { child.complete(message); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }));
            deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
            committed(sources, "source", Map.of("operation", "original-producer", "partition", "mail-1"), "producer-event");
            var output = child.get(15, TimeUnit.SECONDS); assertTrue(completed.await(5, TimeUnit.SECONDS));
            var key = new CorrelatedJoinKey(scope(monitor), "manifest-business", "publish", "0");
            assertEquals(key.continuationProcessId(), output.processInstanceId());
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow(); assertTrue(record.sealed()); assertEquals(Set.of(), record.expectedIds());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        String invalid = graph.replace("[\"publish\"]", "[\"gate\"]");
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("untrusted-worker.db"), Clock.systemUTC())) {
            var sources = new ConcurrentHashMap<String, InboundSourceContext>();
            var deployment = SourceLocalTraversalTest.deployment(engine, store, sources, new CopyOnWriteArrayList<>(), new ExecutionMonitor(), invalid,
                    Clock.systemUTC(), registry -> authored(registry).register("transform", message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())))
                            .register("publish-probe", message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()))));
            assertThrows(Exception.class, () -> deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS)); assertTrue(sources.isEmpty());
        }
    }

    @Test void killedDerivedRootAndDownstreamEffectsRemainUnderOriginalRecoveryAuthority() throws Exception {
        for (String boundary : List.of("root", "downstream")) {
            Path database = directory.resolve("derived-" + boundary + ".db"), effects = directory.resolve(boundary + "-effects.txt");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                    DerivedCorrelationCrashChild.class.getName(), database.toString(), boundary, effects.toString()).redirectErrorStream(true).start();
            try (var reader = child.inputReader(); var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var ready = readers.submit(() -> {
                    var output = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null) { output.append(line).append('\n'); if (line.equals("DERIVED_EFFECT_READY")) return output.toString(); }
                    throw new AssertionError("child exited before original derived effect: " + output);
                });
                ready.get(20, TimeUnit.SECONDS); child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            } finally { if (child.isAlive()) child.destroyForcibly(); }
            var clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(31));
            try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, clock)) {
                String graph = DerivedCorrelationCrashChild.graph(boundary);
                var graphVersion = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(graph.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var relation = store.correlatedTraversals(new BusinessScope(OWNER.tenantId(), "source-local", graphVersion), "ingestion-42", Optional.empty(), 10)
                        .toCompletableFuture().get().items().getFirst();
                var key = new ExecutionKey(OWNER.tenantId(), relation.processInstanceId());
                var before = store.load(key).toCompletableFuture().get(); var intent = store.deploymentEntry(key).toCompletableFuture().get().orElseThrow();
                assertNotNull(intent.checkpoint()); assertEquals("structured", ai.ravenroot.api.payload.PayloadJson.read(intent.attributes().bytes(), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava() instanceof Map<?, ?> attrs ? attrs.get("mail") : null);
                assertEquals(1, Files.readAllLines(effects).size()); var reconciled = new CountDownLatch(1); var repeated = new AtomicInteger();
                var observed = proxy(store, (method, args) -> {
                    var result = invoke(store, method, args);
                    if (method.getName().equals("release") && ((LeaseHandle) args[0]).key().equals(key))
                        return ((CompletionStage<?>) result).thenApply(value -> { reconciled.countDown(); return value; });
                    return result;
                });
                var sources = new ConcurrentHashMap<String, InboundSourceContext>();
                var deployment = SourceLocalTraversalTest.deployment(engine, observed, sources, new CopyOnWriteArrayList<>(), new ExecutionMonitor(), graph, clock,
                        registry -> authored(registry).register("transform", message -> CompletableFuture.completedFuture(new NodeResult("continue", Map.of("computed", "ingestion-42"), Map.of("mail", "structured"))))
                            .register("publish-probe", message -> { repeated.incrementAndGet(); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); })
                            .register("downstream-probe", message -> { repeated.incrementAndGet(); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }));
                deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS); assertTrue(reconciled.await(10, TimeUnit.SECONDS));
                var after = store.load(key).toCompletableFuture().get();
                assertEquals(before.state(), after.state()); assertEquals(TraversalStatus.RUNNING, after.state().traversals().get(relation.traversalId()).status());
                assertEquals(0, repeated.get()); assertEquals(1, Files.readAllLines(effects).size());
                assertEquals(intent, store.deploymentEntry(key).toCompletableFuture().get().orElseThrow());
                for (var visit : after.state().traversals().get(relation.traversalId()).invocations().values()) assertEquals(1, visit.attempts().size());
                deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static DefaultGraphDeployment derivedDeployment(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> sources, CompletableFuture<NodeMessage> child, Clock clock) {
        return SourceLocalTraversalTest.deployment(engine, store, sources, new CopyOnWriteArrayList<>(), new ExecutionMonitor(), derivedGraph(), clock,
                registry -> authored(registry).register("transform", message -> CompletableFuture.completedFuture(new NodeResult("continue", Map.of("computed", "ingestion-42"), Map.of("mail", "structured"))))
                        .register("publish-probe", message -> { child.complete(message); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }));
    }
    private interface Interceptor { Object call(Method method, Object[] args) throws Throwable; }
    private static ExecutionStore proxy(ExecutionStore store, Interceptor interceptor) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (object, method, args) -> interceptor.call(method, args));
    }
    private static Object invoke(ExecutionStore store, Method method, Object[] args) throws Throwable {
        try { return method.invoke(store, args); } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    static BehaviorRegistry authored(BehaviorRegistry registry) {
        ai.ravenroot.core.runtime.builtin.StandardBehaviorFactories.all(BehaviorEnvironment.safeDefaults()).stream()
                .filter(factory -> Set.of(CorrelationOperationProperties.CLOSE, CorrelationOperationProperties.DERIVE).contains(factory.descriptor().behavior()))
                .forEach(registry::registerFactory);
        return registry;
    }
    static void committed(Map<String, InboundSourceContext> sources, String source, Object payload, String id) {
        assertInstanceOf(IngressReceipt.DurablyCommitted.class, sources.get(source).ingress().offerDurably(OWNER, IngressTarget.node(source), payload, source, id));
    }
    private static BusinessScope scope(ExecutionMonitor monitor) {
        return new BusinessScope(OWNER.tenantId(), "source-local", monitor.eventsAfter(0).getFirst().graphVersion());
    }
}

/** Child JVM ends only by the parent fault injection after a real derived effect has begun. */
class DerivedCorrelationCrashChild {
    static String graph(String boundary) {
        String graph = AuthoredCorrelationRuntimeTest.derivedGraph();
        return boundary.equals("root") ? graph : graph.replace("<node id=\"end\">", "<node id=\"downstream\"><data key=\"kind\">behavior</data><data key=\"behavior\">downstream-probe</data></node><node id=\"end\">")
                .replace("<edge source=\"publish\" target=\"end\"/>", "<edge source=\"publish\" target=\"downstream\"/><edge source=\"downstream\" target=\"end\"/>");
    }
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]), effects = Path.of(args[2]); String boundary = args[1];
        var entered = new CountDownLatch(1); var never = new CompletableFuture<NodeResult>();
        var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC());
        var sources = new ConcurrentHashMap<String, InboundSourceContext>();
        java.util.function.Function<NodeMessage, CompletionStage<NodeResult>> effect = message -> {
            try { Files.writeString(effects, message.nodeId() + ":" + message.invocationId() + ":" + message.attemptId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException failed) { return CompletableFuture.failedFuture(failed); }
            entered.countDown(); return never;
        };
        var deployment = SourceLocalTraversalTest.deployment(engine, store, sources, new CopyOnWriteArrayList<>(), new ExecutionMonitor(), graph(boundary), Clock.systemUTC(), registry ->
            AuthoredCorrelationRuntimeTest.authored(registry).register("transform", message -> CompletableFuture.completedFuture(new NodeResult("continue", Map.of("computed", "ingestion-42"), Map.of("mail", "structured"))))
                .register("publish-probe", message -> boundary.equals("root") ? effect.apply(message) : CompletableFuture.completedFuture(new NodeResult("continue", message.payload(), message.attributes())))
                .register("downstream-probe", effect::apply));
        deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
        AuthoredCorrelationRuntimeTest.committed(sources, "source", Map.of("operation", "original", "partition", "mail-1"), "committed-original-event");
        if (!entered.await(15, TimeUnit.SECONDS)) throw new AssertionError("real child effect did not start");
        System.out.println("DERIVED_EFFECT_READY"); System.out.flush(); new CountDownLatch(1).await();
    }
}
