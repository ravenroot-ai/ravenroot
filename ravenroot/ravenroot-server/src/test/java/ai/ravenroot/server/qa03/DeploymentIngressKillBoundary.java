package ai.ravenroot.server.qa03;

import ai.ravenroot.api.application.ExecutionIdentityKind;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The program {@link DeploymentIngressKillTest} forks and then kills with {@code SIGKILL} — the
 * deployment-hosted-traversal cell of the QA-03 crash/replay matrix.
 *
 * <h2>The boundary, and why it is the one that matters</h2>
 * <p>{@code DefaultGraphDeployment.offerDurably} does, in order: (1) durably commit the inbound
 * event's dedup receipt via {@code ExecutionStore.recordInboxDelivery}; (2) create the process instance
 * and commit its {@code RUNNING} transition via {@code openTraversalRecorder}; (3) dispatch the
 * traversal. Its own Javadoc states the guarantee this ordering buys: "the durable commit happens
 * before the traversal is dispatched, never after — so a crash between the two leaves a durable record
 * with no traversal, recoverable by redelivery being recognised as Duplicate, rather than a traversal
 * with no durable record, which redelivery could not detect at all." Until this cell, that sentence was
 * argued, not shown.
 *
 * <p>This child kills itself at exactly step (2)'s first write, using
 * {@link ParkOnFirstApplyExecutionStore} — a test-only decorator over a real
 * {@link SqliteExecutionStore}, not a production seam (see that class's Javadoc for why one is not
 * needed: {@code CommitBoundary} is package-private by ADR 0010 section 12.4 and cannot be reached from
 * here). Step (1)'s commit reaches the real database file for real before the decorator ever runs;
 * only step (2) is intercepted.
 *
 * <h2>Process death, not machine death; no seed</h2>
 * <p>As with every other cell: a real {@code SIGKILL} via {@code Process#destroyForcibly()}, which
 * leaves the OS page cache intact, so this cannot distinguish {@code synchronous=FULL} from a reduced
 * mode. The kill point is announced (the decorator prints {@link #AT_BOUNDARY} only once the real
 * engine's dispatch through the real deployment has reached that exact write), not sampled.
 */
public final class DeploymentIngressKillBoundary {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String TENANT = "acme";
    static final UUID INSTANCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TRAVERSAL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final String SOURCE_ID = "qa03-source";
    static final String IDEMPOTENT_KEY = "qa03-event-1";
    static final String DESTINATION = DeploymentId.of("qa03-deployment").value() + "/" + SOURCE_ID;
    static final String WORKER_ID = "qa03-deployment-ingress-kill-worker";
    static final Duration LEASE_TTL = Duration.ofSeconds(30);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";

    /** start -> end. No behavior node is reached: the kill lands before the traversal ever dispatches. */
    static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    private static final SecurityContext SECURITY = new SecurityContext("qa03-deployment-ingress-kill", TENANT,
            "qa03-kill-worker", PrincipalType.USER, "urn:ravenroot:qa03");

    /** Fixed identities so the parent can assert against them without parsing anything the child printed. */
    private static final ExecutionIdentitySource FIXED_IDENTITIES = kind -> switch (kind) {
        case PROCESS_INSTANCE -> INSTANCE_ID;
        case TRAVERSAL -> TRAVERSAL_ID;
        default -> UUID.randomUUID(); // never reached: the kill lands before any node dispatches
    };

    private DeploymentIngressKillBoundary() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);

        var realStore = new SqliteExecutionStore(databaseFile, Clock.fixed(NOW, ZoneOffset.UTC));
        var decoratedStore = new ParkOnFirstApplyExecutionStore(realStore, () -> {
            System.out.println(AT_BOUNDARY);
            System.out.flush();
        });

        try (var engine = new PekkoExecutionEngine("qa03-deployment-ingress-kill")) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("qa03-deployment"), engine,
                    BehaviorRegistry.standard(), new ExecutionMonitor(), FIXED_IDENTITIES,
                    GRAPH.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                    decoratedStore, DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, WORKER_ID, LEASE_TTL);
            deployment.start(SECURITY).toCompletableFuture().get(20, TimeUnit.SECONDS);

            // Blocks forever inside openTraversalRecorder's first store write, once recordInboxDelivery
            // has already committed for real -- see ParkOnFirstApplyExecutionStore.
            deployment.ingress().offerDurably(SECURITY, IngressTarget.start(), "payload", SOURCE_ID,
                    IDEMPOTENT_KEY);
        }
        System.out.println(COMPLETED);
        System.out.flush();
    }
}
