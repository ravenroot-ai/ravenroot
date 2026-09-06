package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LocalDeploymentStatus;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.persistence.HumanTaskAttentionPage;
import ai.ravenroot.api.persistence.HumanTaskAttentionQuery;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration contract from public local-deployment status to durable Human Task attention. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LocalDeploymentHumanTaskAttentionContextTest {
    private static final String TENANT = "tenant-a";
    private static final String PUBLIC_DEPLOYMENT_ID = "human-task-e2e";
    private static final String NODE_ID = "review";
    private static final SecurityContext DEPLOYER = new SecurityContext(
            "deploy-request", TENANT, "deployer", PrincipalType.USER, "urn:ravenroot:test");
    private static final RequestContext RESPONDER = new RequestContext(
            "attention-request", "responder", PrincipalType.USER, "urn:ravenroot:test", TENANT,
            Set.of(Role.APPROVER), Set.of());
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="title" for="node" attr.name="title" attr.type="string"/>
              <key id="confirmation" for="node" attr.name="confirmationPresentationVersion" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="attention-context" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="source"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
                <node id="review"><data key="kind">behavior</data><data key="behavior">human-task</data><data key="title">Review</data><data key="confirmation">1</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="source"><data key="outcome">continue</data></edge>
                <edge source="source" target="review"><data key="outcome">continue</data></edge>
                <edge source="review" target="end"><data key="outcome">resolved</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path directory;

    @Test
    void publicDeploymentStatusScopesAttentionAcrossTenantAndStoreRestart() throws Exception {
        Path database = directory.resolve("attention.db");
        String graphVersion;

        try (var store = new SqliteExecutionStore(database, Clock.systemUTC(), HumanTaskPolicy.DEFAULTS)) {
            var tasks = new HumanTaskService(store, Clock.systemUTC(), HumanTaskPolicy.DEFAULTS);
            var source = new CapturingSourceBehavior();
            var application = application(store, tasks, source);
            try {
                LocalDeploymentStatus registered = application.registerLocalDeployment(DEPLOYER,
                        PUBLIC_DEPLOYMENT_ID, graph(GRAPH));
                graphVersion = registered.graphVersion().orElseThrow();
                application.startLocalDeployment(DEPLOYER, PUBLIC_DEPLOYMENT_ID)
                        .toCompletableFuture().get(30, TimeUnit.SECONDS).orElseThrow();
                InboundSourceContext context = source.awaitContext();
                context.ingress().offerDurably(context.identity(), IngressTarget.start(), "payload",
                        "source", "attention-context-1");

                HumanTaskAttentionPage attention = awaitAttention(tasks, RESPONDER, graphVersion);
                assertEquals(1, attention.items().size());
                assertEquals(java.util.Optional.of(PUBLIC_DEPLOYMENT_ID),
                        attention.items().getFirst().deploymentId(),
                        "the id projected by deployment status must be the durable attention context");
                assertEquals(NODE_ID, attention.items().getFirst().nodeId());

                RequestContext sibling = new RequestContext("sibling-request", "responder",
                        PrincipalType.USER, "urn:ravenroot:test", "tenant-b",
                        Set.of(Role.APPROVER), Set.of());
                assertTrue(tasks.attention(sibling, HumanTaskAttentionQuery.forDeployment(
                        graphVersion, PUBLIC_DEPLOYMENT_ID, 10)).items().isEmpty(),
                        "a public deployment name remains tenant-partitioned");
            } finally {
                application.close();
            }
        }

        // No local deployment is registered in this process. The durable status-projected context
        // alone must still locate the task after SQLite is reopened.
        try (var reopened = new SqliteExecutionStore(database, Clock.systemUTC(), HumanTaskPolicy.DEFAULTS)) {
            var recoveredTasks = new HumanTaskService(reopened, Clock.systemUTC(), HumanTaskPolicy.DEFAULTS);
            HumanTaskAttentionPage recovered = awaitAttention(recoveredTasks, RESPONDER, graphVersion);
            assertEquals(1, recovered.items().size());
            assertEquals(java.util.Optional.of(PUBLIC_DEPLOYMENT_ID),
                    recovered.items().getFirst().deploymentId());
        }
    }

    private static DefaultRavenrootApplication application(SqliteExecutionStore store,
                                                            HumanTaskService tasks,
                                                            CapturingSourceBehavior source) {
        var environment = BehaviorEnvironment.safeDefaults();
        var registry = BehaviorRegistry.standard(environment,
                ai.ravenroot.api.publication.PublicationPolicyResolver.none(),
                ai.ravenroot.api.publication.PublicationAuditSink.noop(), tasks, HumanTaskPolicy.DEFAULTS);
        registry = NodePackages.register(registry, source.nodePackage());
        return new DefaultRavenrootApplication(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                registry, new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store, 2, UnknownBehaviorPolicy.passThrough(),
                null, null, tasks, GraphExecutionLimits.DEFAULTS);
    }

    private static HumanTaskAttentionPage awaitAttention(HumanTaskService tasks, RequestContext requester,
                                                         String graphVersion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HumanTaskAttentionPage latest = null;
        while (System.nanoTime() < deadline) {
            latest = tasks.attention(requester, HumanTaskAttentionQuery.forDeployment(
                    graphVersion, PUBLIC_DEPLOYMENT_ID, 10));
            if (!latest.items().isEmpty()) return latest;
            Thread.sleep(10);
        }
        throw new AssertionError("deployment attention did not become visible; latest=" + latest);
    }

    private static ByteArrayInputStream graph(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CapturingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();

        private NodePackage nodePackage() {
            return new NodePackage() {
                @Override public String id() { return "test.attention-context"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                @Override public List<NodeBehavior> behaviors() { return List.of(CapturingSourceBehavior.this); }
            };
        }

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source",
                    "actor", false, List.of(), Set.of(), NodeRuntimeNature.SOURCE,
                    Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override public InboundSource createSource(NodeConfiguration configuration,
                                                    InboundSourceContext context) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        private InboundSourceContext awaitContext() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                if (!contexts.isEmpty()) return contexts.getFirst();
                Thread.sleep(10);
            }
            throw new AssertionError("source did not start");
        }
    }
}
