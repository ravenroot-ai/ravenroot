package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentInitialStartTest {
    @TempDir Path directory;
    private static final SecurityContext IDENTITY = TestIdentities.TENANT_A;
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="initial" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="effect"><data key="kind">behavior</data><data key="behavior">effect</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="effect"/><edge source="effect" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void distinctRequestsAndConcurrentClientsCannotReplaceAnAcceptedStart() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("concurrent.db"), Clock.systemUTC())) {
            var effects = new CopyOnWriteArrayList<Object>();
            var monitor = new ExecutionMonitor();
            var completed = terminalLatch(monitor);
            var deployment = deployment("one", engine, store, effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                var gate = new CountDownLatch(1);
                var results = new ArrayList<Future<IngressReceipt>>();
                for (int i = 0; i < 24; i++) {
                    int request = i;
                    results.add(clients.submit(() -> {
                        gate.await();
                        return deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(),
                                "input-" + request, "client-" + request, "request-" + request);
                    }));
                }
                gate.countDown();
                for (var result : results) assertNotNull(result.get(10, TimeUnit.SECONDS));
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, effects.size());
            var original = store.initialStart(IDENTITY.tenantId(), "one").toCompletableFuture().join().orElseThrow();
            assertEquals(effects.getFirst(), ai.ravenroot.api.payload.PayloadJson.read(original.input().bytes(),
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava());
            assertInstanceOf(IngressReceipt.Duplicate.class, deployment.ingress().offerDurably(IDENTITY,
                    IngressTarget.start(), "replacement", "other-client", "new-request"));
            assertEquals(1, effects.size());
            assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "another-entry-path"));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void restartAndIncarnationChangesPreserveAdmissionButExplicitNewDeploymentIsIndependent() throws Exception {
        Path database = directory.resolve("restart.db");
        UUID originalProcess;
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var monitor = new ExecutionMonitor();
            var completed = terminalLatch(monitor);
            var deployment = deployment("retained", engine, store, new CopyOnWriteArrayList<>(), monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, deployment.ingress().offerDurably(IDENTITY,
                    IngressTarget.start(), "original", "client", "first"));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            originalProcess = store.initialStart(IDENTITY.tenantId(), "retained").toCompletableFuture().join()
                    .orElseThrow().processInstanceId();
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        // A new store handle, runner and incarnation, seven years beyond transport inbox retention.
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(365 * 7L)))) {
            var effects = new CopyOnWriteArrayList<Object>();
            var monitor = new ExecutionMonitor();
            var deployment = deployment("retained", engine, store, effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1L, store.purgeExpiredProcessInstances(IDENTITY.tenantId()).toCompletableFuture().join());
            assertInstanceOf(IngressReceipt.Duplicate.class, deployment.ingress().offerDurably(IDENTITY,
                    IngressTarget.start(), "replacement", "client-2", "second"));
            assertTrue(effects.isEmpty());
            assertEquals(originalProcess, store.initialStart(IDENTITY.tenantId(), "retained")
                    .toCompletableFuture().join().orElseThrow().processInstanceId());
            var completed = terminalLatch(monitor);
            var fresh = deployment("explicit-new", engine, store, effects, monitor);
            fresh.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, fresh.ingress().offerDurably(IDENTITY,
                    IngressTarget.start(), "fresh", "client", "first"));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("fresh"), effects);
            fresh.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void crashAfterAdmissionBeforeExecutionReconcilesOriginalInputAndIdentity() throws Exception {
        Path database = directory.resolve("accepted.db");
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var definition = deploymentVersion();
            store.acceptInitialStart(new InitialStartRecord("crash", definition, IDENTITY,
                    OpaquePayload.of("\"accepted-input\"".getBytes(StandardCharsets.UTF_8), "application/json"),
                    Instant.now())).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        try (var engine = new SameThreadExecutionEngine(); var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            var effects = new CopyOnWriteArrayList<Object>();
            var monitor = new ExecutionMonitor();
            var completed = terminalLatch(monitor);
            var deployment = deployment("crash", engine, store, effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(completed.await(5, TimeUnit.SECONDS), "activation must recover accepted initial work without a client retry");
            assertInstanceOf(IngressReceipt.Duplicate.class, deployment.ingress().offerDurably(IDENTITY,
                    IngressTarget.start(), "retry-must-not-replace-input", "new-client", "new-request"));
            assertEquals(List.of("accepted-input"), effects);
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void applicationAndEmbedReconcileSameDeploymentStartAndRefuseStaleOrForeignBindings() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("application.db"), Clock.systemUTC())) {
            var effects = new CopyOnWriteArrayList<Object>();
            var monitor = new ExecutionMonitor();
            var completed = terminalLatch(monitor);
            var registry = new BehaviorRegistry().register("effect", message -> {
                effects.add(message.payload());
                return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            });
            try (var application = new DefaultRavenrootApplication(engine, monitor, registry,
                    new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                    new ai.ravenroot.core.programming.DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(),
                    store, 8, UnknownBehaviorPolicy.passThrough())) {
                application.registerLocalDeployment(IDENTITY, "document", new java.io.ByteArrayInputStream(
                        GRAPH.getBytes(StandardCharsets.UTF_8)));
                application.startLocalDeployment(IDENTITY, "document").toCompletableFuture().get(5, TimeUnit.SECONDS);
                var binding = application.localDeploymentView(IDENTITY.tenantId(), "document").orElseThrow().source();
                assertTrue(effects.isEmpty());
                var initial = application.startLocalDeploymentExecution(IDENTITY, "document", binding.incarnationId(),
                        binding.graphVersion(), "request-one", ai.ravenroot.api.payload.PayloadValue.of("initial-input"));
                assertEquals(EmbedDeploymentStart.Outcome.ACCEPTED, initial.outcome());
                assertNotNull(initial.processInstanceId());
                assertNotNull(initial.traversalId());
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                var repeated = application.startEmbedDeploymentExecution(IDENTITY, "document", binding.incarnationId(),
                        binding.graphVersion(), "different-client");
                assertEquals(EmbedDeploymentStart.Outcome.DUPLICATE, repeated.outcome());
                assertEquals(initial.processInstanceId(), repeated.processInstanceId());
                assertEquals(initial.traversalId(), repeated.traversalId());
                assertEquals(List.of("initial-input"), effects);
                assertEquals(EmbedDeploymentStart.Outcome.REFUSED, application.startEmbedDeploymentExecution(
                        IDENTITY, "document", "stale", binding.graphVersion(), "stale-request").outcome());
                var foreign = new SecurityContext("foreign", "another-tenant", IDENTITY.subject(),
                        IDENTITY.principalType(), IDENTITY.issuer());
                var denied = application.startEmbedDeploymentExecution(foreign, "document", binding.incarnationId(),
                        binding.graphVersion(), "foreign-request");
                assertEquals(EmbedDeploymentStart.Outcome.REFUSED, denied.outcome());
                assertNull(denied.traversalId());
            }
        }
    }

    @Test void volatileRestartRetainsSingleStartForSameLogicalObject() throws Exception {
        try (var engine = new SameThreadExecutionEngine()) {
            var effects = new CopyOnWriteArrayList<Object>();
            var deployment = deployment("volatile", engine, null, effects, new ExecutionMonitor());
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(IngressDisposition.ACCEPTED, deployment.ingress().offer(IDENTITY, IngressTarget.start(), "once"));
            deployment.restart(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "again"));
            assertEquals(List.of("once"), effects);
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void recoveryRetriesAfterAnOrphanedUnstartedExecutionLeaseExpires() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var timers = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        try (timers; var engine = new SameThreadExecutionEngine((delay, task) -> {
                 var scheduled = timers.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
                 return () -> scheduled.cancel(false);
             });
             var store = new SqliteExecutionStore(directory.resolve("orphaned-start.db"), clock)) {
            var initial = store.acceptInitialStart(new InitialStartRecord("orphan", deploymentVersion(), IDENTITY,
                    OpaquePayload.of("\"original-input\"".getBytes(StandardCharsets.UTF_8), "application/json"), now.get()))
                    .toCompletableFuture().get();
            var key = new ExecutionKey(IDENTITY.tenantId(), initial.processInstanceId());
            var traversal = new Traversal(initial.traversalId(), "start", TraversalStatus.RUNNING, Map.of());
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(initial.processInstanceId(),
                            ProcessInstanceStatus.RUNNING, Map.of(initial.traversalId(), traversal)),
                            new GraphVersionPin(initial.graphVersion()))).build()).toCompletableFuture().get();
            store.claim(key, "orphaned-worker", Duration.ofMinutes(1)).toCompletableFuture().get();
            var refusedClaim = new CountDownLatch(1);
            ExecutionStore observed = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(
                    ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class}, (proxy, method, arguments) -> {
                        try {
                            Object value = method.invoke(store, arguments);
                            if (method.getName().equals("claim") && value instanceof CompletionStage<?> claim)
                                claim.whenComplete((lease, failure) -> { if (failure != null) refusedClaim.countDown(); });
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            var effects = new CopyOnWriteArrayList<Object>(); var monitor = new ExecutionMonitor();
            var finished = terminalLatch(monitor);
            var registry = new BehaviorRegistry().register("effect", message -> {
                effects.add(message.payload()); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            });
            var deployment = new DefaultGraphDeployment(DeploymentId.of("orphan"), engine, registry, monitor,
                    ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, observed,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "recovery-worker", Duration.ofMillis(300),
                    RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY), clock);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(refusedClaim.await(5, TimeUnit.SECONDS), "the original lease must actually refuse the first recovery");
            assertTrue(effects.isEmpty());
            now.set(now.get().plusSeconds(61));
            assertTrue(finished.await(5, TimeUnit.SECONDS), "a refused first claim must re-arm recovery");
            assertEquals(List.of("original-input"), effects);
            assertEquals(initial.processInstanceId(), monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow().processInstanceId());
            assertEquals(initial.traversalId(), monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow().traversalId());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static String deploymentVersion() throws Exception {
        try (var engine = new SameThreadExecutionEngine()) {
            return deployment("hash", engine, null, new CopyOnWriteArrayList<>(), new ExecutionMonitor()).graphVersion();
        }
    }

    private static CountDownLatch terminalLatch(ExecutionMonitor monitor) {
        var latch = new CountDownLatch(1);
        monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) latch.countDown(); });
        return latch;
    }

    private static DefaultGraphDeployment deployment(String id, SameThreadExecutionEngine engine, ExecutionStore store,
            List<Object> effects, ExecutionMonitor monitor) {
        var registry = new BehaviorRegistry().register("effect", message -> {
            effects.add(message.payload());
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        return new DefaultGraphDeployment(DeploymentId.of(id), engine, registry, monitor,
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "initial-test", Duration.ofSeconds(30));
    }
}
