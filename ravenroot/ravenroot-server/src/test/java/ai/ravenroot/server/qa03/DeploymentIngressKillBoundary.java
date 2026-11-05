package ai.ravenroot.server.qa03;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The program {@link DeploymentIngressKillTest} forks and then kills with {@code SIGKILL} — the
 * deployment-hosted-traversal cell of the QA-03 crash/replay matrix.
 *
 * <h2>The boundary</h2>
 * <p>The exact SOURCE input and business relationship commit before inbox deduplication and the
 * first process-state write. The test decorator parks that first write after the real custody and
 * inbox transactions. SIGKILL therefore leaves retained original input and causal identities, with
 * no execution or downstream effect. A reopened runtime must recover that same entry without
 * producer redelivery, and a later duplicate must neither add an inbox row nor repeat its effect.</p>
 *
 * <h2>Process death, not machine death; no seed</h2>
 * <p>As with every other cell: a real {@code SIGKILL} via {@code Process#destroyForcibly()}, which
 * leaves the OS page cache intact, so this cannot distinguish {@code synchronous=FULL} from a reduced
 * mode. The kill point is announced (the decorator prints {@link #AT_BOUNDARY} only once the real
 * engine's dispatch through the real deployment has reached that exact write), not sampled.
 */
public final class DeploymentIngressKillBoundary {

    static final String TENANT = "acme";
    static final String SOURCE_ID = "qa03-source";
    static final String IDEMPOTENT_KEY = "qa03-event-1";
    static final String DESTINATION = "qa03-deployment/source/c291cmNl/" + SOURCE_ID;
    static final UUID INSTANCE_ID = UUID.nameUUIDFromBytes((TENANT + '\0' + DESTINATION + '\0' + IDEMPOTENT_KEY)
            .getBytes(StandardCharsets.UTF_8));
    static final UUID TRAVERSAL_ID = UUID.nameUUIDFromBytes((INSTANCE_ID + "\0traversal").getBytes(StandardCharsets.UTF_8));
    static final String WORKER_ID = "qa03-deployment-ingress-kill-worker";
    static final Duration LEASE_TTL = Duration.ofSeconds(30);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";

    /** Independent START; source -> effect -> end. The kill precedes every dispatch. */
    static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="source"><data key="node-kind">BEHAVIOR</data><data key="behavior">qa03.source</data></node>
                <node id="effect"><data key="node-kind">BEHAVIOR</data><data key="behavior">qa03.effect</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="source-effect" source="source" target="effect"><data key="edge-outcome">continue</data></edge>
                <edge id="effect-end" source="effect" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    static final SecurityContext SECURITY = new SecurityContext("qa03-deployment-ingress-kill", TENANT,
            "qa03-kill-worker", PrincipalType.USER, "urn:ravenroot:qa03");

    private DeploymentIngressKillBoundary() {
    }

    static BehaviorRegistry registry(ai.ravenroot.core.runtime.NodeHandler effect) {
        class Source implements ai.ravenroot.api.node.NodeBehavior, ai.ravenroot.api.node.InboundSourceCapable {
            @Override public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() {
                return new ai.ravenroot.api.catalog.NodeTypeDescriptor("qa03.source", "Source", "Test", "", "actor", false,
                        java.util.List.of(), java.util.Set.of());
            }
            @Override public ai.ravenroot.api.node.NodeAction create(ai.ravenroot.api.node.NodeConfiguration configuration) {
                throw new AssertionError("SOURCE ordinary action must not execute");
            }
            @Override public ai.ravenroot.api.deployment.InboundSource createSource(
                    ai.ravenroot.api.node.NodeConfiguration configuration, ai.ravenroot.api.deployment.InboundSourceContext context) {
                return new ai.ravenroot.api.deployment.InboundSource() {
                    @Override public java.util.concurrent.CompletionStage<Void> start(ai.ravenroot.api.deployment.InboundSourceContext active) {
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                    @Override public java.util.concurrent.CompletionStage<Void> stop() {
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                };
            }
        }
        return ai.ravenroot.core.runtime.NodePackages.register(BehaviorRegistry.standard().register("qa03.effect", effect),
                new ai.ravenroot.api.node.NodePackage() {
                    @Override public String id() { return "test.qa03.source"; }
                    @Override public String version() { return "1"; }
                    @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
                    @Override public java.util.List<ai.ravenroot.api.node.NodeBehavior> behaviors() { return java.util.List.of(new Source()); }
                });
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);

        var realStore = new SqliteExecutionStore(databaseFile, Clock.systemUTC());
        var decoratedStore = ParkOnFirstApplyExecutionStore.wrap(realStore, () -> {
            System.out.println(AT_BOUNDARY);
            System.out.flush();
        });

        try (var engine = new PekkoExecutionEngine("qa03-deployment-ingress-kill")) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("qa03-deployment"), engine,
                    registry(message -> { throw new AssertionError("No effect may precede the kill boundary"); }), new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    GRAPH.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                    decoratedStore, DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, WORKER_ID, LEASE_TTL);
            deployment.start(SECURITY).toCompletableFuture().get(20, TimeUnit.SECONDS);

            // Blocks forever inside openTraversalRecorder's first store write, once recordInboxDelivery
            // has already committed for real -- see ParkOnFirstApplyExecutionStore.
            deployment.ingress().offerDurably(SECURITY, IngressTarget.node("source"), "payload", SOURCE_ID,
                    IDEMPOTENT_KEY);
        }
        System.out.println(COMPLETED);
        System.out.flush();
    }
}
