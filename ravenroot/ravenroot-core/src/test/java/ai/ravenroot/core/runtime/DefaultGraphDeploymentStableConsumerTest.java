package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.SourceCheckpointStore;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DefaultGraphDeploymentStableConsumerTest {
    @TempDir Path directory;
    private static final SecurityContext IDENTITY = identity("tenant");
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="stable" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="source"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="source"/><edge source="source" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void newDeploymentAndStoreResumeCursorAndInboxWhileRetiredContextCannotWrite() throws Exception {
        Path database = directory.resolve("consumer.db");
        JournalCursor recorded;
        DurableConsumerIngress retired;
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC()); var engine = new SameThreadExecutionEngine()) {
            var behavior = new SourceBehavior();
            var deployment = deployment(engine, store, behavior, GRAPH);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThrows(UnsupportedOperationException.class, () -> deployment.ingress().openDurableConsumer("reader"));
            retired = behavior.context.ingress().openDurableConsumer("reader");
            var initial = retired.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join();
            assertEquals(0, initial.deliveredThrough());
            assertEquals(DurableIngressStartState.ABSENT,
                    retired.startState(IDENTITY, "mailbox/42", "uid-7").toCompletableFuture().join());
            String namespace = sourcePart("stable-consumer") + "/" + sourcePart(deployment.graphVersion()) + "/"
                    + sourcePart("test.source") + "/" + sourcePart("source") + "/reader";
            String destination = "source-v1:" + sourcePart(namespace) + "." + sourcePart("mailbox/42");
            UUID inboxOnlyId = UUID.nameUUIDFromBytes((IDENTITY.tenantId() + '\0' + destination + '\0'
                    + "uid-inbox-only").getBytes(StandardCharsets.UTF_8));
            assertTrue(store.recordInboxDelivery(IDENTITY.tenantId(), destination, inboxOnlyId,
                    Duration.ofDays(7)).toCompletableFuture().join());
            assertEquals(DurableIngressStartState.INBOX_ONLY,
                    retired.startState(IDENTITY, "mailbox/42", "uid-inbox-only").toCompletableFuture().join());
            assertInstanceOf(IngressReceipt.Duplicate.class, retired.offerDurably(IDENTITY,
                    IngressTarget.start(), "recovered mail", "mailbox/42", "uid-inbox-only"));
            awaitStarted(retired, "uid-inbox-only");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, retired.offerDurably(IDENTITY,
                    IngressTarget.start(), "mail", "mailbox/42", "uid-7"));
            awaitStarted(retired, "uid-7");
            recorded = retired.advanceSourceCheckpoint(initial, 8).toCompletableFuture().join();
            assertFalse(recorded.destination().contains("/"), "stable keys cannot collide with legacy deployment/source keys");
            assertThrows(CompletionException.class, () -> behavior.context.ingress()
                    .advanceSourceCheckpoint(recorded, 9).toCompletableFuture().join(),
                    "legacy source ingress must not reach an exclusively owned stable cursor");
            assertThrows(IllegalStateException.class, () -> behavior.context.ingress().openDurableConsumer("other"));
            assertThrows(CompletionException.class, () -> retired.sourceCheckpoint(identity("foreign"), "mailbox/42")
                    .toCompletableFuture().join());
            assertInstanceOf(IngressReceipt.Refused.class, retired.offerDurably(identity("foreign"),
                    IngressTarget.start(), "mail", "mailbox/42", "foreign"));
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); // source.stop intentionally does nothing
            assertThrows(CompletionException.class, () -> retired.advanceSourceCheckpoint(recorded, 9).toCompletableFuture().join());
            assertThrows(IllegalStateException.class, () -> behavior.context.ingress().openDurableConsumer("reader"));
        }
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC()); var engine = new SameThreadExecutionEngine()) {
            var behavior = new SourceBehavior();
            var deployment = deployment(engine, store, behavior, GRAPH);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (var resumed = behavior.context.ingress().openDurableConsumer("reader")) {
                assertEquals(recorded, resumed.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join());
                assertInstanceOf(IngressReceipt.Duplicate.class, resumed.offerDurably(IDENTITY,
                        IngressTarget.start(), "mail", "mailbox/42", "uid-7"));
                assertInstanceOf(IngressReceipt.Refused.class, retired.offerDurably(IDENTITY,
                        IngressTarget.start(), "late", "mailbox/42", "uid-8"));
                assertThrows(CompletionException.class, () -> resumed.advanceSourceCheckpoint(
                        new JournalCursor("foreign", recorded.destination(), 8), 9).toCompletableFuture().join());
                assertThrows(CompletionException.class, () -> resumed.advanceSourceCheckpoint(
                        new JournalCursor(IDENTITY.tenantId(), recorded.destination() + "/legacy", 0), 9).toCompletableFuture().join());
            }
            try (var other = behavior.context.ingress().openDurableConsumer("different")) {
                assertEquals(0, other.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join().deliveredThrough());
                assertInstanceOf(IngressReceipt.DurablyCommitted.class, other.offerDurably(IDENTITY,
                        IngressTarget.start(), "mail", "mailbox/42", "uid-7"));
            }
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void secondDeploymentCannotOwnSameConsumerAndDifferentTrustedSourceCannotInheritIt() throws Exception {
        Path database = directory.resolve("concurrent.db");
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC());
             var peerStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var engine = new SameThreadExecutionEngine(); var peerEngine = new SameThreadExecutionEngine()) {
            var behavior = new SourceBehavior();
            var peerBehavior = new SourceBehavior();
            var first = deployment(engine, store, behavior, GRAPH);
            var peer = deployment(peerEngine, peerStore, peerBehavior, GRAPH);
            first.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            peer.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var owner = behavior.context.ingress().openDurableConsumer("reader");
            var initial = owner.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join();
            owner.advanceSourceCheckpoint(initial, 8).toCompletableFuture().join();
            assertThrows(IllegalStateException.class, () -> peerBehavior.context.ingress().openDurableConsumer("reader"));
            first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (var successor = peerBehavior.context.ingress().openDurableConsumer("reader")) {
                assertEquals(8, successor.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join().deliveredThrough());
            }
            peer.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var differentNode = deployment(peerEngine, peerStore, peerBehavior,
                    GRAPH.replace("id=\"source\"", "id=\"another\"").replace("target=\"source\"", "target=\"another\"")
                            .replace("source=\"source\"", "source=\"another\""));
            differentNode.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (var consumer = peerBehavior.context.ingress().openDurableConsumer("reader")) {
                assertEquals(0, consumer.sourceCheckpoint(IDENTITY, "mailbox/42").toCompletableFuture().join().deliveredThrough());
            }
            differentNode.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void blockedOwnershipOpenCannotDelayAdmissionOrDeactivationAndLateOwnerIsReleased() throws Exception {
        Path database = directory.resolve("blocked-open.db");
        try (var realStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var engine = new SameThreadExecutionEngine()) {
            var ownership = new BlockingOwnershipStore(realStore, true, true, false);
            var behavior = new SourceBehavior();
            var deployment = deployment(engine, ownership.store(), behavior, GRAPH, Duration.ofMillis(500));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<DurableConsumerIngress> opening = CompletableFuture.supplyAsync(() ->
                    behavior.context.ingress().openDurableConsumer("reader"));
            assertTrue(ownership.openEntered.await(10, TimeUnit.SECONDS));

            long started = System.nanoTime();
            assertInstanceOf(IngressReceipt.DurablyCommitted.class,
                    behavior.context.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                            "mailbox/42", "open-blocked"));
            deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000,
                    "ownership open must not hold the source lifecycle lock");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> opening.get(2, TimeUnit.SECONDS),
                    "the open caller receives its bounded unresolved result");

            ownership.releaseOpen.countDown();
            assertTrue(ownership.closeEntered.await(10, TimeUnit.SECONDS),
                    "an owner acquired after retirement must be closed instead of published");
            ownership.releaseClose.countDown();
        }
    }

    @Test void blockedOwnershipCloseCannotDelayAdmissionOrDeactivation() throws Exception {
        Path database = directory.resolve("blocked-close.db");
        try (var realStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var engine = new SameThreadExecutionEngine()) {
            var ownership = new BlockingOwnershipStore(realStore, false, true, false);
            var behavior = new SourceBehavior();
            var deployment = deployment(engine, ownership.store(), behavior, GRAPH, Duration.ofMillis(500));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var consumer = behavior.context.ingress().openDurableConsumer("reader");

            var stopping = deployment.stop().toCompletableFuture();
            assertTrue(ownership.closeEntered.await(10, TimeUnit.SECONDS));
            assertInstanceOf(IngressReceipt.Refused.class,
                    consumer.offerDurably(IDENTITY, IngressTarget.start(), "payload",
                            "mailbox/42", "close-blocked"),
                    "revocation must be visible before ownership cleanup settles");
            stopping.get(2, TimeUnit.SECONDS);

            ownership.releaseClose.countDown();
        }
    }

    @Test void queuedDeactivationCannotBeOvertakenAndAdmissionsRemainBounded() throws Exception {
        Path database = directory.resolve("queued-deactivation.db");
        try (var realStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var engine = new SameThreadExecutionEngine()) {
            var ownership = new BlockingOwnershipStore(realStore, false, false, true);
            var behavior = new SourceBehavior();
            var deployment = deployment(engine, ownership.store(), behavior, GRAPH, Duration.ofMillis(400));
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var consumer = behavior.context.ingress().openDurableConsumer("reader");

            CompletableFuture<IngressReceipt> first = CompletableFuture.supplyAsync(() ->
                    consumer.offerDurably(IDENTITY, IngressTarget.start(), "first",
                            "mailbox/42", "first"));
            assertTrue(ownership.inboxEntered.await(10, TimeUnit.SECONDS));
            var stopped = deployment.stop().toCompletableFuture();
            long secondStarted = System.nanoTime();
            IngressReceipt second = consumer.offerDurably(IDENTITY, IngressTarget.start(), "second",
                    "mailbox/42", "second");

            assertFalse(second.acknowledgeable(), "a reader queued behind deactivation cannot barge");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - secondStarted) < 2_000,
                    "lifecycle lock acquisition must spend from the wrapper's deadline");
            assertInstanceOf(IngressReceipt.Ambiguous.class, first.get(2, TimeUnit.SECONDS));
            stopped.get(2, TimeUnit.SECONDS);
            ownership.releaseInbox.complete(true);
        }
    }

    private static SecurityContext identity(String tenant) {
        return new SecurityContext("request", tenant, "source", PrincipalType.WORKLOAD, "issuer");
    }

    private static String sourcePart(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitStarted(DurableConsumerIngress ingress, String key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ingress.startState(IDENTITY, "mailbox/42", key).toCompletableFuture().join()
                != DurableIngressStartState.STARTED && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(DurableIngressStartState.STARTED,
                ingress.startState(IDENTITY, "mailbox/42", key).toCompletableFuture().join(),
                "only a persisted invocation establishes scheduler checkpoint custody");
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
                                                       SourceBehavior behavior, String graph) {
        return deployment(engine, store, behavior, graph, DefaultGraphDeployment.DEFAULT_STORE_CALL_BOUND);
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
                                                       SourceBehavior behavior, String graph, Duration bound) {
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        return new DefaultGraphDeployment(DeploymentId.of("stable-consumer"), engine,
                NodePackages.register(new BehaviorRegistry(), nodePackage), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), graph.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "stable-consumer-test", Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                null, GraphExecutionLimits.DEFAULTS, null, null, null, null,
                GraphRunner.DEFAULT_SHUTDOWN_BOUND, bound);
    }

    private static final class BlockingOwnershipStore {
        private final ExecutionStore store;
        private final ExecutionStore delegate;
        private final boolean blockOpen;
        private final boolean blockClose;
        private final boolean blockInbox;
        private final CountDownLatch openEntered = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final CountDownLatch inboxEntered = new CountDownLatch(1);
        private final CompletableFuture<Boolean> releaseInbox = new CompletableFuture<>();

        private BlockingOwnershipStore(ExecutionStore delegate, boolean blockOpen, boolean blockClose,
                                       boolean blockInbox) {
            this.delegate = delegate;
            this.blockOpen = blockOpen;
            this.blockClose = blockClose;
            this.blockInbox = blockInbox;
            this.store = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[] { ExecutionStore.class }, this::invoke);
        }

        private ExecutionStore store() {
            return store;
        }

        private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            try {
                if (method.getName().equals("openSourceCheckpointStore")) {
                    openEntered.countDown();
                    if (blockOpen) releaseOpen.await();
                    SourceCheckpointStore opened = delegate.openSourceCheckpointStore(
                            (String) arguments[0], (String) arguments[1]);
                    return wrap(opened);
                }
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }

        private SourceCheckpointStore wrap(SourceCheckpointStore opened) {
            return new SourceCheckpointStore() {
                @Override public CompletionStage<JournalCursor> checkpoint(String sourceId) {
                    return opened.checkpoint(sourceId);
                }
                @Override public CompletionStage<JournalCursor> advance(JournalCursor expected, long position) {
                    return opened.advance(expected, position);
                }
                @Override public CompletionStage<Boolean> recordInbox(String sourceId, UUID eventId,
                                                                       Duration retention) {
                    if (!blockInbox) return opened.recordInbox(sourceId, eventId, retention);
                    inboxEntered.countDown();
                    return releaseInbox;
                }
                @Override public CompletionStage<Boolean> containsInbox(String sourceId, UUID eventId) {
                    return opened.containsInbox(sourceId, eventId);
                }
                @Override public void close() {
                    closeEntered.countDown();
                    try {
                        if (blockClose) releaseClose.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        opened.close();
                    }
                }
            };
        }
    }

    private static final class SourceBehavior implements NodeBehavior, InboundSourceCapable {
        private InboundSourceContext context;
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Source", "Test", "Source", "actor", false,
                    List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }
        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext ignored) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    context = started;
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
            };
        }
    }
}
