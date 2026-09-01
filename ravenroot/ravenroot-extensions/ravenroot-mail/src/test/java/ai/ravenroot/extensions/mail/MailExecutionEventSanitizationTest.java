package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.extensions.mail.imap.ImapProfile;
import ai.ravenroot.extensions.mail.imap.MailImapQueryNodeBehavior;
import ai.ravenroot.extensions.mail.imap.DeterministicStartTlsImapFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailExecutionEventSanitizationTest {
    private static final String SENTINEL = "credential-secret-sentinel";

    @Test void hostileAuthenticationReplyCannotReachCoreExecutionEvents() throws Exception {
        synchronized (MailExecutionEventSanitizationTest.class) {
            try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate();
                 var smtp = DeterministicSmtpFixture.rejectingAuthentication();
                 var graph = GraphManager.from(new GraphDefinition(
                         List.of(GraphNode.start("start"), GraphNode.behavior("mail", "mail.send"), GraphNode.error("error"), GraphNode.end("end")),
                         List.of(GraphEdge.to("start", "mail"), GraphEdge.to("mail", "end"))));
                 var engine = new DirectEngine()) {
                var action = MailTestSupport.authenticatedAction(ref -> Optional.of(new SecretValue(SENTINEL.toCharArray())),
                        "127.0.0.1", smtp.port(), "STARTTLS");
                var monitor = new ExecutionMonitor();
                try (var runner = new GraphRunner(graph, engine, new BehaviorRegistry().register("mail.send", action::handle), monitor)) {
                    SecurityContext security = new SecurityContext("request", "tenant-a", "session", PrincipalType.USER, "alice");
                    Throwable failure = assertThrows(Throwable.class, () -> runner.execute(security,
                            Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"))
                            .toCompletableFuture().join());
                    assertSecretAbsent(failure, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                    var events = monitor.eventsAfter(0);
                    assertTrue(smtp.authenticationAttempts() > 0, "the hostile reply path must actually execute");
                    assertTrue(events.stream().anyMatch(event -> event.type().name().endsWith("FAILED")));
                    assertFalse(events.stream().anyMatch(event -> event.detail().contains(SENTINEL)));
                }
            }
        }
    }

    /**
     * {@code logs} used to be a plain {@link ArrayList}, mutated by a {@link Handler} attached to
     * the root logger and read back by the final {@code assertFalse(logs.stream()...)} below without a
     * snapshot -- the same unsynchronized-collection-under-root-attachment shape measured as a flake on
     * the IMAP-side sibling {@code ImapProfileRejectionDiagnosticsTest} (one {@code
     * ConcurrentModificationException} in thirteen runs), caused there by background threads -- Jakarta
     * Mail's own {@code MailLogger} among them -- still writing after their originating fixture's
     * nominal close. This test opens exactly such a fixture ({@link DeterministicStartTlsImapFixture},
     * in the same {@code try} block, immediately before the final assertion) inside the same scope, so
     * it is exposed to the identical race.
     *
     * <p>Unlike the IMAP-side test, this test cannot narrow capture to one named logger: its whole point
     * is that a hostile exception's message must never surface through <em>any</em> logger reachable
     * from {@link ai.ravenroot.core.runtime.GraphRunner}'s execution path, and which logger that might
     * be is exactly what is under test, not known in advance. The fixture therefore uses a synchronized
     * structure, not a named logger. {@link java.util.concurrent.CopyOnWriteArrayList}
     * makes {@code publish} (invoked concurrently, from whatever thread each logger call happens to run
     * on) and the final read mutually safe -- its iterator never throws {@code
     * ConcurrentModificationException} and observes a stable snapshot taken when iteration starts --
     * while leaving the capture exactly as broad as before: still the root logger, still every record
     * from anywhere in the fork.
     *
     * <p>"Mutually safe" covers the collection, not the read-after-write sequence: a background thread
     * can still append a record after the snapshot iterator for {@code logs.stream()} below has already
     * started, so that one late record is not guaranteed to be seen. That residual is harmless here only
     * because the property under test is negative -- {@code assertFalse(... .anyMatch(...))} -- so a
     * record arriving too late to be scanned can only make this assertion pass when it should have
     * failed, never the reverse; a missed leak would still be caught by some other invocation of this
     * same check, not silently accepted as a false failure. A future <em>positive</em> assertion added
     * against this {@code logs} list (asserting something *is* present, e.g. "a FAILED event was logged")
     * would not have that one-directional safety and would be flaky for exactly this reason -- it would
     * need its own synchronization with the fixture's close, not just a thread-safe collection.
     */
    @Test void imapProfileCredentialAndTransportFailuresNeverLeakThroughEventsLogsOrThrowableGraph() throws Exception {
        String sentinel = "imap-hostile-secret-sentinel";
        RuntimeException hostile = new RuntimeException(sentinel, new IllegalStateException(sentinel)); hostile.addSuppressed(new IllegalArgumentException(sentinel));
        List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>(); Handler handler = new Handler() { @Override public void publish(LogRecord record) { logs.add(record.getMessage() + " " + record.getThrown()); } @Override public void flush() { } @Override public void close() { } };
        Logger root = Logger.getLogger(""); root.addHandler(handler);
        try {
            assertImapFailureSanitized(new MailImapQueryNodeBehavior((tenant, name) -> { throw hostile; }, ref -> Optional.empty()).create(imapConfiguration()), sentinel);
            assertImapFailureSanitized(new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(imapProfile(tenant, name)), ref -> { throw hostile; }).create(imapConfiguration()), sentinel);
            AssertionError hostileError = new AssertionError(sentinel); hostileError.initCause(new IllegalStateException(sentinel)); hostileError.addSuppressed(new IllegalArgumentException(sentinel));
            assertImapFailureSanitized(new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(imapProfile(tenant, name)), ref -> { throw hostileError; }).create(imapConfiguration()), sentinel);
            try (var hostileServer = new DeterministicStartTlsImapFixture(true, true, sentinel)) {
                assertImapFailureSanitized(hostileServer.action(ref -> Optional.of(new SecretValue(sentinel.toCharArray()))), sentinel);
            }
            assertFalse(logs.stream().anyMatch(value -> value.contains(sentinel)));
        } finally { root.removeHandler(handler); }
    }

    private static void assertImapFailureSanitized(NodeAction action, String sentinel) throws Exception {
        try (var graph = GraphManager.from(new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.behavior("imap", MailImapQueryNodeBehavior.BEHAVIOR), GraphNode.error("error"), GraphNode.end("end")), List.of(GraphEdge.to("start", "imap"), GraphEdge.to("imap", "end")))); var engine = new DirectEngine()) {
            var monitor = new ExecutionMonitor();
            try (var runner = new GraphRunner(graph, engine, new BehaviorRegistry().register(MailImapQueryNodeBehavior.BEHAVIOR, action::handle), monitor)) {
                SecurityContext security = new SecurityContext("request", "tenant-a", "session", PrincipalType.USER, "alice");
                Throwable failure = assertThrows(Throwable.class, () -> runner.execute(security, Map.of("version", "mail.imap.query.v1")).toCompletableFuture().join());
                assertSecretAbsent(failure, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), sentinel);
                assertTrue(monitor.eventsAfter(0).stream().anyMatch(event -> event.type().name().endsWith("FAILED")));
                assertFalse(monitor.eventsAfter(0).stream().anyMatch(event -> event.detail().contains(sentinel)));
            }
        }
    }
    private static NodeConfiguration imapConfiguration() { return new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, Map.of("profile", "reader", "limit", "1")); }
    private static ImapProfile imapProfile(String tenant, String id) { return new ImapProfile(tenant, id, "127.0.0.1", 1, "IMAPS", "reader", "credential", Set.of("INBOX"), 100, 100, 1, 1, 10); }

    private static void assertSecretAbsent(Throwable failure, Set<Throwable> visited) {
        assertSecretAbsent(failure, visited, SENTINEL);
    }
    private static void assertSecretAbsent(Throwable failure, Set<Throwable> visited, String sentinel) {
        if (failure == null || !visited.add(failure)) return;
        assertFalse(String.valueOf(failure.getMessage()).contains(sentinel));
        assertFalse(failure.toString().contains(sentinel));
        assertSecretAbsent(failure.getCause(), visited, sentinel);
        for (Throwable suppressed : failure.getSuppressed()) assertSecretAbsent(suppressed, visited, sentinel);
        if (failure instanceof jakarta.mail.MessagingException messaging) assertSecretAbsent(messaging.getNextException(), visited, sentinel);
    }

    private static final class DirectEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        @Override public String id() { return "mail-test-direct"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public EngineState state() { return EngineState.RUNNING; }
        @Override public NodeRef spawn(String logicalName, RavenNode node) { NodeRef ref = new NodeRef(logicalName + "-" + UUID.randomUUID()); nodes.put(ref, node); return ref; }
        @Override public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            return node == null ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown node")) : node.onMessage(message, context(target));
        }
        @Override public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target) ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0)) : Optional.empty();
        }
        @Override public CompletionStage<Void> stop(NodeRef target) { nodes.remove(target); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public CompletionStage<Void> drain() { nodes.clear(); return CompletableFuture.completedFuture(null); }
        @Override public void close() { nodes.clear(); }
        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override public NodeRef self() { return ref; }
                @Override public Scheduler scheduler() { return DirectEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() {
                    return new CancellationSignal() { @Override public boolean cancelled() { return false; } @Override public void onCancel(Runnable listener) { } };
                }
            };
        }
    }
}
