package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real process death after a committed receipt, with no producer redelivery and capture disabled. */
@Timeout(90)
class SourceCustodyRecoveryTest {
    @TempDir Path directory;
    static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="k" for="node" attr.name="kind" attr.type="string"/>
              <key id="b" for="node" attr.name="behavior" attr.type="string"/>
              <key id="j" for="node" attr.name="joinPolicy" attr.type="string"/>
              <key id="m" for="node" attr.name="correlation.mode" attr.type="string"/>
              <key id="s" for="node" attr.name="correlation.selector" attr.type="string"/>
              <graph id="source-custody" edgedefault="directed">
                <node id="start"><data key="k">start</data></node>
                <node id="source"><data key="k">behavior</data><data key="b">custody.source</data><data key="m">ACCEPT_OR_CREATE</data><data key="s">/operation</data></node>
                <node id="worker"><data key="k">behavior</data><data key="b">custody.worker</data></node>
                <node id="end"><data key="k">end</data><data key="j">each</data></node>
                <node id="error"><data key="k">error</data></node>
                <edge id="start-end" source="start" target="end"/>
                <edge id="source-worker" source="source" target="worker"/>
                <edge id="worker-end" source="worker" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void immutableReceiptSnapshotIsUsedByTheOriginalDeferredDispatch() throws Exception {
        var queued = new LinkedBlockingQueue<Runnable>(); var effects = new CopyOnWriteArrayList<Object>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("snapshot.db"), Clock.systemUTC())) {
            var monitor = new ExecutionMonitor(); var completed = terminal(monitor);
            var deployment = deployment(engine, store, monitor, queued::add, effects::add);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var input = new HashMap<String, Object>(); input.put("operation", "original-operation");
            var body = new HashMap<String, Object>(); body.put("value", "original"); input.put("body", body);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, deployment.ingress().offerDurably(TestIdentities.TENANT_A,
                    IngressTarget.node("source"), input, "source", "mutable-event"));
            body.put("value", "caller-mutated"); input.put("operation", "caller-mutated");
            queued.poll(5, TimeUnit.SECONDS).run(); assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(Map.of("operation", "original-operation", "body", Map.of("value", "original"))), effects);
            assertTrue(store.pendingDeploymentEntries(scope(deployment), Optional.empty(), 10).toCompletableFuture().get().items().isEmpty(),
                    "only terminal settlement retires the retained input");
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void cancellationBeforeDeferredDispatchSettlesCustodyWithoutAnyRealEffect() throws Exception {
        var queued = new LinkedBlockingQueue<Runnable>(); var effects = new CopyOnWriteArrayList<Object>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("cancel.db"), Clock.systemUTC())) {
            var monitor = new ExecutionMonitor(); var done = terminal(monitor);
            var deployment = deployment(engine, store, monitor, queued::add, effects::add);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, deployment.ingress().offerDurably(TestIdentities.TENANT_A,
                    IngressTarget.node("source"), Map.of("operation", "cancelled"), "source", "cancel-before-dispatch"));
            var pending = store.pendingDeploymentEntries(scope(), Optional.empty(), 10).toCompletableFuture().get().items().getFirst();
            deployment.closeAdmission(100).toCompletableFuture().get(); deployment.barrier(100).toCompletableFuture().get();
            queued.poll(5, TimeUnit.SECONDS).run(); assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(effects.isEmpty());
            var key = new ExecutionKey(pending.scope().tenantId(), pending.processInstanceId());
            assertTrue(store.deploymentEntry(key).toCompletableFuture().get().isEmpty());
            var relation = store.correlatedTraversal(key.tenantId(), key.processInstanceId(), pending.traversalId()).toCompletableFuture().get().orElseThrow();
            assertEquals(TraversalStatus.FAILED, relation.status()); assertEquals(ExecutionTerminationReason.CANCELLED, relation.terminationReason());
            deployment.openAdmission(101).toCompletableFuture().get();
            assertTrue(store.pendingDeploymentEntries(scope(), Optional.empty(), 10).toCompletableFuture().get().items().isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void drainAllowsAlreadyAcceptedDeferredWorkToFinishAndRetireCustody() throws Exception {
        var queued = new LinkedBlockingQueue<Runnable>(); var effects = new CopyOnWriteArrayList<Object>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(directory.resolve("drain.db"), Clock.systemUTC())) {
            var monitor = new ExecutionMonitor(); var done = terminal(monitor);
            var deployment = deployment(engine, store, monitor, queued::add, effects::add);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, deployment.ingress().offerDurably(TestIdentities.TENANT_A,
                    IngressTarget.node("source"), Map.of("operation", "drain"), "source", "drain-before-dispatch"));
            var draining = deployment.drain(Duration.ofSeconds(5), 100);
            queued.poll(5, TimeUnit.SECONDS).run(); assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(draining.toCompletableFuture().get());
            assertEquals(List.of(Map.of("operation", "drain")), effects);
            assertTrue(store.pendingDeploymentEntries(scope(), Optional.empty(), 10).toCompletableFuture().get().items().isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void expiredRetainedInputSettlesVisiblyBeforeItsBytesAreRetired() throws Exception {
        Path database = directory.resolve("expired.db"); UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        Instant accepted = Instant.now().minusSeconds(10);
        var original = new DeploymentEntryIntent(scope(), process, traversal, "source",
                new ApplicationCorrelationContext("expired", Optional.empty(), Optional.empty(), "0", Optional.empty()), TestIdentities.TENANT_A,
                OpaquePayload.of("{\"operation\":\"expired\"}".getBytes(StandardCharsets.UTF_8), "application/json"),
                accepted, accepted.plusSeconds(1), CorrelatedTraversal.Cause.SOURCE_EVENT, null, "", null);
        try (var store = new SqliteExecutionStore(database, Clock.fixed(accepted, ZoneOffset.UTC))) {
            store.acceptDeploymentEntry(original, CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get();
        }
        var effects = new CopyOnWriteArrayList<Object>(); var settled = new CountDownLatch(1);
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var observed = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, values) -> {
                        Object result; try { result = method.invoke(store, values); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                        if (method.getName().equals("apply") && values[0] instanceof ExecutionBatch batch
                                && batch.transitions().stream().anyMatch(value -> value instanceof ExecutionTransition.ProcessTransitioned status
                                    && status.next() == ProcessInstanceStatus.FAILED))
                            return ((CompletionStage<?>) result).thenApply(value -> { settled.countDown(); return value; });
                        return result;
                    });
            var deployment = deployment(engine, observed, new ExecutionMonitor(), task -> Thread.ofVirtual().start(task), effects::add);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(settled.await(5, TimeUnit.SECONDS)); assertTrue(effects.isEmpty());
            assertTrue(store.deploymentEntry(original.key()).toCompletableFuture().get().isEmpty());
            var relation = store.correlatedTraversal(original.key().tenantId(), process, traversal).toCompletableFuture().get().orElseThrow();
            assertEquals(TraversalStatus.FAILED, relation.status()); assertEquals(ExecutionTerminationReason.UNREACHABLE, relation.terminationReason());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void returnedReceiptSurvivesRealProcessDeathBeforeRunnerDispatch() throws Exception { recoverAfterKill("receipt"); }
    @Test void originalSourceStartSurvivesRealProcessDeathBeforeSourceCompletion() throws Exception { recoverAfterKill("source-start"); }
    @Test void originalSourceCompletionSurvivesRealProcessDeathBeforeOutgoingDispatch() throws Exception { recoverAfterKill("source-complete"); }
    @Test void reservedSourceFanOutSurvivesRealProcessDeathBeforeFirstRealDelivery() throws Exception { recoverAfterKill("source-edge"); }

    @Test void restartingAtTheConsumedStepLimitCannotGrantAnotherExecutionBudget() throws Exception {
        Path database = directory.resolve("steps.db"), effects = directory.resolve("steps.effects");
        killAtBoundary(database, "source-edge-at-limit", effects); assertFalse(Files.exists(effects));
        var observed = new CopyOnWriteArrayList<Object>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)))) {
            var row = store.pendingDeploymentEntries(scope(), Optional.empty(), 10).toCompletableFuture().get().items().getFirst();
            var key = new ExecutionKey(row.scope().tenantId(), row.processInstanceId());
            var original = store.deploymentEntry(key).toCompletableFuture().get().orElseThrow();
            assertEquals(2, original.checkpoint().runtimeState().traversalSteps());
            var monitor = new ExecutionMonitor(); var done = terminal(monitor);
            var deployment = deployment(engine, store, monitor, task -> Thread.ofVirtual().start(task), observed::add, GRAPH, twoStepLimit());
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, observed.size());
            var traversal = store.load(key).toCompletableFuture().get().state().traversals().get(row.traversalId());
            assertEquals(TraversalStatus.FAILED, traversal.status());
            assertEquals(2, traversal.invocations().size(), "the third visit must be refused before it is created");
            assertTrue(monitor.eventsAfter(0).stream().anyMatch(event -> event.type() == ExecutionEventType.EXECUTION_FAILED
                    && event.detail().contains("TRAVERSAL_STEPS")), () -> monitor.eventsAfter(0).toString());
            assertEquals(original.checkpoint().invocationId(), traversal.invocations().values().stream()
                    .filter(visit -> visit.nodeId().equals("source")).findFirst().orElseThrow().invocationId());
            assertTrue(store.deploymentEntry(key).toCompletableFuture().get().isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    static GraphExecutionLimits twoStepLimit() {
        return GraphExecutionLimits.fromEnvironment(Map.of(GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE, "2"));
    }

    @Test void partialFanOutResumesOnlyTheMissingSiblingAfterAnOriginalChildCompleted() throws Exception { partialFanOut(false); }
    @Test void partialFanOutDoesNotCompleteOrRepeatAnAmbiguousOriginalRealEffect() throws Exception { partialFanOut(true); }

    private void partialFanOut(boolean ambiguous) throws Exception {
        String boundary = ambiguous ? "partial-ambiguous" : "partial-completed";
        Path database = directory.resolve(boundary + ".db"), effects = directory.resolve(boundary + ".effects");
        killAtBoundary(database, boundary, effects);
        assertEquals(1, Files.readAllLines(effects).size(), "the original left child performed one effect");
        var observed = new CopyOnWriteArrayList<Object>(); var rightDone = new CountDownLatch(1);
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)))) {
            var original = store.pendingDeploymentEntries(scope(partialGraph()), Optional.empty(), 10).toCompletableFuture().get().items().getFirst();
            var key = new ExecutionKey(original.scope().tenantId(), original.processInstanceId());
            var before = store.load(key).toCompletableFuture().get().state().traversals().get(original.traversalId());
            var left = before.invocations().values().stream().filter(visit -> !visit.nodeId().equals("source")).findFirst().orElseThrow();
            String missingNode = left.nodeId().equals("left") ? "right" : "left";
            assertEquals(ambiguous ? NodeInvocationStatus.RUNNING : NodeInvocationStatus.COMPLETED, left.status());
            var inputOwner = store.deploymentEntry(key).toCompletableFuture().get().orElseThrow();
            assertEquals(3, inputOwner.checkpoint().runtimeState().traversalSteps(), "the original full source fan-out was already charged");
            var monitor = new ExecutionMonitor(); var terminal = terminal(monitor); var released = new CountDownLatch(1);
            var observedStore = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, values) -> {
                        Object value; try { value = method.invoke(store, values); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                        if (method.getName().equals("release")) return ((CompletionStage<?>) value).thenApply(done -> { released.countDown(); return done; });
                        return value;
                    });
            var deployment = deployment(engine, observedStore, monitor, task -> Thread.ofVirtual().start(task), payload -> {
                observed.add(payload); rightDone.countDown();
            }, partialGraph());
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(rightDone.await(5, TimeUnit.SECONDS));
            if (!ambiguous) assertTrue(terminal.await(5, TimeUnit.SECONDS));
            else {
                assertTrue(released.await(5, TimeUnit.SECONDS), "partial reconciliation released its original execution lease");
            }
            assertEquals(1, observed.size()); assertEquals(missingNode, ((Map<?, ?>) observed.getFirst()).get("node"));
            var after = store.load(key).toCompletableFuture().get().state().traversals().get(original.traversalId());
            assertEquals(ambiguous ? TraversalStatus.RUNNING : TraversalStatus.COMPLETED, after.status());
            var retainedLeft = after.invocations().get(left.invocationId()); assertNotNull(retainedLeft);
            assertEquals(left, retainedLeft, "reconciliation must not replace or retry the original real child");
            assertEquals(1, after.invocations().values().stream().filter(visit -> visit.nodeId().equals("left")).count());
            assertEquals(1, after.invocations().values().stream().filter(visit -> visit.nodeId().equals("right")).count());
            if (ambiguous) {
                var retained = store.deploymentEntry(key).toCompletableFuture().get().orElseThrow();
                assertEquals(3, retained.checkpoint().runtimeState().traversalSteps(), "resuming an already charged sibling must not reset or double-charge counters");
                assertEquals(inputOwner.acceptedAt(), retained.acceptedAt()); assertEquals(inputOwner.expiresAt(), retained.expiresAt());
                assertEquals(0, monitor.eventsAfter(0).stream().filter(event -> event.type().isTraversalTerminal()).count());
            } else assertTrue(store.deploymentEntry(key).toCompletableFuture().get().isEmpty());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    static String partialGraph() {
        return GRAPH.replace("<node id=\"worker\"><data key=\"k\">behavior</data><data key=\"b\">custody.worker</data></node>",
                        "<node id=\"left\"><data key=\"k\">behavior</data><data key=\"b\">custody.worker</data></node>"
                                + "<node id=\"right\"><data key=\"k\">behavior</data><data key=\"b\">custody.worker</data></node>")
                .replace("<edge id=\"source-worker\" source=\"source\" target=\"worker\"/>",
                        "<edge id=\"source-left\" source=\"source\" target=\"left\"/>"
                                + "<edge id=\"source-right\" source=\"source\" target=\"right\"/>")
                .replace("<edge id=\"worker-end\" source=\"worker\" target=\"end\"/>", "");
    }

    private void recoverAfterKill(String boundary) throws Exception {
        Path database = directory.resolve(boundary + ".db"), effects = directory.resolve(boundary + ".effects");
        killAtBoundary(database, boundary, effects);
        assertFalse(Files.exists(effects), "no real worker ran before the killed boundary");
        DeploymentEntryIntent original;
        // The new store clock passes the dead process's lease, without requiring any producer retry.
        try (var store = new SqliteExecutionStore(database, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)))) {
            var rows = store.pendingDeploymentEntries(scope(), Optional.empty(), 10).toCompletableFuture().get().items();
            assertEquals(1, rows.size()); var row = rows.getFirst();
            original = store.deploymentEntry(new ExecutionKey(row.scope().tenantId(), row.processInstanceId())).toCompletableFuture().get().orElseThrow();
            assertEquals("original-operation", original.context().applicationCorrelationId());
            assertEquals(Map.of("operation", "original-operation", "body", "original-input"),
                    ai.ravenroot.api.payload.PayloadJson.read(original.input().bytes(), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava());
            assertEquals(!boundary.equals("receipt"), original.checkpoint() != null);
            if (original.checkpoint() != null) assertEquals(boundary.equals("source-complete") || boundary.equals("source-edge"),
                    original.checkpoint().completedEventId() != null);
        }
        var observed = new CopyOnWriteArrayList<Object>();
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)))) {
            var monitor = new ExecutionMonitor(); var completed = terminal(monitor);
            var deployment = deployment(engine, store, monitor, task -> Thread.ofVirtual().start(task), observed::add);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(completed.await(10, TimeUnit.SECONDS), () -> monitor.eventsAfter(0).toString());
            assertEquals(List.of(Map.of("operation", "original-operation", "body", "original-input")), observed);
            var persisted = store.load(original.key()).toCompletableFuture().get();
            var traversal = persisted.state().traversals().get(original.traversalId());
            assertEquals(TraversalStatus.COMPLETED, traversal.status(), () -> monitor.eventsAfter(0).toString());
            var sources = traversal.invocations().values().stream().filter(visit -> visit.nodeId().equals("source")).toList();
            assertEquals(1, sources.size());
            if (original.checkpoint() != null) {
                assertEquals(original.checkpoint().invocationId(), sources.getFirst().invocationId());
                assertEquals(original.checkpoint().attemptId(), sources.getFirst().attempts().getFirst().attemptId());
            }
            assertTrue(store.deploymentEntry(original.key()).toCompletableFuture().get().isEmpty());
            assertEquals(1, store.correlatedTraversals(scope(), "original-operation", Optional.empty(), 10).toCompletableFuture().get().items().size());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    private static void killAtBoundary(Path database, String boundary, Path effects) throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), SourceCustodyCrashChild.class.getName(), database.toString(), boundary,
                effects.toString()).redirectErrorStream(true).start();
        try (var reader = child.inputReader(); var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = readers.submit(() -> {
                var output = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n'); if (line.equals("CUSTODY_READY")) return output.toString();
                }
                throw new AssertionError("child exited before its committed receipt/boundary: " + output);
            });
            ready.get(20, TimeUnit.SECONDS); child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    static BusinessScope scope() throws Exception { return scope(GRAPH); }
    static BusinessScope scope(String graph) throws Exception {
        return new BusinessScope(TestIdentities.TENANT_A.tenantId(), "source-custody", HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(graph.getBytes(StandardCharsets.UTF_8))));
    }
    private static BusinessScope scope(DefaultGraphDeployment ignored) throws Exception { return scope(); }
    private static CountDownLatch terminal(ExecutionMonitor monitor) {
        var done = new CountDownLatch(1); monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) done.countDown(); }); return done;
    }
    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store, ExecutionMonitor monitor,
            Executor executor, java.util.function.Consumer<Object> effect) {
        return deployment(engine, store, monitor, executor, effect, GRAPH);
    }
    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store, ExecutionMonitor monitor,
            Executor executor, java.util.function.Consumer<Object> effect, String graph) {
        return deployment(engine, store, monitor, executor, effect, graph, GraphExecutionLimits.DEFAULTS);
    }
    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store, ExecutionMonitor monitor,
            Executor executor, java.util.function.Consumer<Object> effect, String graph, GraphExecutionLimits limits) {
        class Source implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() { return new NodeTypeDescriptor("custody.source", "Source", "Test", "Source", "actor", false,
                    List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE)); }
            @Override public NodeAction create(NodeConfiguration configuration) { throw new AssertionError("SOURCE listener cannot run as a worker"); }
            @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
                return new InboundSource() {
                    @Override public CompletionStage<Void> start(InboundSourceContext active) { return CompletableFuture.completedFuture(null); }
                    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                };
            }
        }
        var pack = new NodePackage() {
            @Override public String id() { return "test.custody"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(new Source()); }
        };
        var registry = NodePackages.register(new BehaviorRegistry(), pack).register("custody.worker", message -> {
            effect.accept(graph.equals(GRAPH) ? message.payload() : Map.of("node", message.nodeId(), "input", message.payload()));
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        return new DefaultGraphDeployment(DeploymentId.of("source-custody"), engine, registry, monitor, ExecutionIdentitySource.randomUuids(),
                graph.getBytes(StandardCharsets.UTF_8), 8, store, Duration.ofDays(1), "custody-worker", Duration.ofSeconds(1),
                RequestReplyLimits.defaults(8), null, limits, null, null, null,
                "source-custody", GraphRunner.DEFAULT_SHUTDOWN_BOUND, DefaultGraphDeployment.DEFAULT_STORE_CALL_BOUND, executor);
    }
}

/** Forked fault injector: the main thread observes the returned committed receipt before announcing readiness. */
class SourceCustodyCrashChild {
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]); String boundary = args[1]; Path effects = Path.of(args[2]);
        var reached = new CountDownLatch(1); var parked = new CountDownLatch(1);
        var nodes = new ConcurrentHashMap<UUID, String>();
        var firstRealNode = new java.util.concurrent.atomic.AtomicReference<String>();
        var store = new SqliteExecutionStore(database, Clock.systemUTC());
        var intercepted = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (proxy, method, values) -> {
                    Object result;
                    try { result = method.invoke(store, values); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                    if (method.getName().equals("apply") && values[0] instanceof ExecutionBatch batch) {
                        batch.transitions().forEach(transition -> {
                            if (transition instanceof ExecutionTransition.InvocationAdded added) nodes.put(added.invocation().invocationId(), added.invocation().nodeId());
                        });
                        boolean match = boundary.equals("partial-completed") ? batch.events().stream().anyMatch(event ->
                                event.eventType().equals("NODE_COMPLETED") && Objects.equals(firstRealNode.get(), nodes.get(event.invocationId())))
                                : batch.events().stream().anyMatch(event -> event.eventType().equals(switch (boundary) {
                            case "source-start" -> "NODE_STARTED"; case "source-complete" -> "NODE_COMPLETED"; case "source-edge", "source-edge-at-limit" -> "EDGE_TRAVERSED"; default -> "NEVER";
                        }));
                        if (match) return ((CompletionStage<?>) result).thenApply(committed -> {
                            reached.countDown(); try { parked.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return committed;
                        });
                    }
                    return result;
                });
        var engine = new SameThreadExecutionEngine();
        var monitor = new ExecutionMonitor();
        monitor.subscribe(event -> {
            if (boundary.startsWith("partial-") && event.type() == ExecutionEventType.EDGE_TRAVERSED && "source".equals(event.nodeId())) {
                String target = event.edgeId().equals("source-left") ? "left" : "right";
                if (!firstRealNode.compareAndSet(null, target)) {
                    try { parked.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        });
        Executor executor = boundary.equals("receipt") ? task -> reached.countDown() : task -> Thread.ofVirtual().start(task);
        var deployment = SourceCustodyRecoveryTest.deployment(engine, intercepted, monitor, executor, payload -> {
            try {
                Files.writeString(effects, payload + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (boundary.equals("partial-ambiguous") && Objects.equals(firstRealNode.get(), ((Map<?, ?>) payload).get("node"))) {
                    reached.countDown(); parked.await();
                }
            } catch (java.io.IOException failed) { throw new RuntimeException(failed); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
        }, boundary.startsWith("partial-") ? SourceCustodyRecoveryTest.partialGraph() : SourceCustodyRecoveryTest.GRAPH,
                boundary.endsWith("at-limit") ? SourceCustodyRecoveryTest.twoStepLimit() : GraphExecutionLimits.DEFAULTS);
        deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var receipt = deployment.ingress().offerDurably(TestIdentities.TENANT_A, IngressTarget.node("source"),
                Map.of("operation", "original-operation", "body", "original-input"), "source", "one-original-event");
        if (!(receipt instanceof IngressReceipt.DurablyCommitted)) throw new AssertionError(receipt);
        if (!reached.await(10, TimeUnit.SECONDS)) throw new AssertionError("boundary not reached");
        System.out.println("CUSTODY_READY"); System.out.flush(); parked.await();
    }
}
