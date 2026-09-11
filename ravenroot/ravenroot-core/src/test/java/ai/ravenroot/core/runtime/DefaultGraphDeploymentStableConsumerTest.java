package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, retired.offerDurably(IDENTITY,
                    IngressTarget.start(), "mail", "mailbox/42", "uid-7"));
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

    private static SecurityContext identity(String tenant) {
        return new SecurityContext("request", tenant, "source", PrincipalType.WORKLOAD, "issuer");
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, SqliteExecutionStore store,
                                                       SourceBehavior behavior, String graph) {
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        return new DefaultGraphDeployment(DeploymentId.of(UUID.randomUUID().toString()), engine,
                NodePackages.register(new BehaviorRegistry(), nodePackage), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), graph.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
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
