package ai.ravenroot.testkit;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphMlParseException;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mandatory conformance suite for {@link DefaultGraphDeployment} against every adapter's
 * {@link ExecutionEngine} (ADR 0021 D2).
 *
 * <h2>What this suite proves, and what it cannot</h2>
 * <p>S4 measured, by mutation, that forcing an adapter to the engine-neutral
 * {@code TrackedExecutionDomain} fallback leaves {@code ExecutionEngineContract}'s domain assertions
 * green while the real adapter's own structural test reds: a conforming engine with <em>no</em>
 * segregation is indistinguishable from one with real isolation, using the contract alone. The same is
 * true here, and for the same reason -- {@link DefaultGraphDeployment} is built entirely on
 * {@link ai.ravenroot.api.execution.ExecutionEngine#openDomain(String)} and
 * {@link ai.ravenroot.api.execution.ExecutionDomain}, so every test below would pass identically
 * against the bookkeeping fallback.</p>
 *
 * <p><b>The governing question for every test in this class, and for anyone adding one:</b> "would this
 * pass against {@code TrackedExecutionDomain}?" If yes, it belongs here, and it must never be cited as
 * evidence of segregation, isolation or real resource release -- only of the lifecycle contract. Two
 * requirements from the same design fail that question and are deliberately <em>absent</em> here:
 * "restart without duplicated subscriptions" and "zero residual resources" both pass trivially against
 * bookkeeping and need a count of real actors/threads the contract cannot express, so they live in each
 * adapter's own test module instead (see {@code PekkoGraphDeploymentResidualityTest}). "Playground
 * unaffected" is a core-level regression, not a conformance question, and is covered by
 * {@code DefaultRavenrootApplicationDeploymentTest#thePlaygroundStaysOnTheSharedEngineRegardlessOfTheCap}
 * rather than repeated here.</p>
 *
 * <h2>No timing-ratio tests</h2>
 * <p>S4 shipped one and a mutation making {@code close()} strictly serial survived it, because nodes
 * that stop instantly make eight serial closes as fast as one. Nothing here compares elapsed
 * time; every assertion is either a direct contract observation or barrier-synchronized.</p>
 */
public abstract class GraphDeploymentContract {
    protected static final SecurityContext TCK_IDENTITY = new SecurityContext("deployment-tck-request",
            "deployment-tck-tenant", "deployment-tck-subject", PrincipalType.WORKLOAD,
            "urn:ravenroot:deployment-tck");

    private static final String VALID_GRAPH = """
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

    /**
     * Deterministic, environment-independent startup failure: {@code GraphManager.readGraphMl}
     * already refuses a DOCTYPE unconditionally (SEC-08), well before this suite's own graph-semantic
     * concerns are reached. That is what makes it suitable here -- the failure is guaranteed on every
     * adapter and has nothing to do with the domain seam under test.
     */
    private static final String UNPARSEABLE_GRAPH = """
            <?xml version="1.0"?>
            <!DOCTYPE graphml [<!ENTITY payload "expanded">]>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <graph id="unsafe" edgedefault="directed">
                <node id="start"><data key="value">&payload;</data></node>
              </graph>
            </graphml>
            """;

    private ExecutionEngine engine;

    protected abstract ExecutionEngine createEngine(String systemName);

    protected final ExecutionEngine engine() {
        if (engine == null) {
            engine = createEngine("ravenroot-deployment-tck-" + UUID.randomUUID());
        }
        return engine;
    }

    @AfterEach
    final void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    private DefaultGraphDeployment deployment(DeploymentId id, String graphMl) {
        return new DefaultGraphDeployment(id, engine(), BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                graphMl.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }

    @Test
    final void noActivityBeforeStart() {
        var deployment = deployment(DeploymentId.of("cold"), VALID_GRAPH);

        assertEquals(DeploymentState.COLD, deployment.status().state());
        assertEquals(IngressDisposition.REJECTED_NOT_READY,
                deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "payload"));
        var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("payload"),
                        Instant.now().plusSeconds(5)));
        assertEquals(RequestReplyRefusal.NOT_READY, refusal.reason());
    }

    /**
     * A fake reporting {@code READY} the instant it is asked passes if only the enum is checked. This
     * asserts something true only <em>at</em> readiness: ingress is genuinely admitted and the
     * traversal it starts actually completes with the payload untouched.
     */
    @Test
    final void readinessIsRealNotJustTheEnumValue() throws Exception {
        var deployment = deployment(DeploymentId.of("readiness"), VALID_GRAPH);

        var status = deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(DeploymentState.READY, status.state());

        assertEquals(IngressDisposition.ACCEPTED,
                deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "payload"));

        var accepted = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("request-reply"),
                        Instant.now().plusSeconds(5)));
        var exchange = accepted.exchange();
        var outcome = exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state());
        assertEquals("request-reply", outcome.payload());
        assertEquals(exchange.processInstanceId(), outcome.processInstanceId());
        assertEquals(exchange.traversalId(), outcome.traversalId());
    }

    /**
     * The pre-dispatch payload projection is engine-neutral too.
     *
     * <p>Both halves are asserted together because they are the same property seen from two sides: a
     * projection that succeeds sees runtime-issued identity and its payload is what the traversal
     * carries, and a projection that fails admits nothing at all. Neither half involves an actor, a
     * mailbox or an adapter-specific handle, so Pekko and Akka must produce identical results — which
     * is exactly why this lives in the shared contract rather than being written twice.</p>
     */
    @Test
    final void projectedRequestReplyBuildsThePayloadFromRuntimeIssuedMetadataOrAdmitsNothing()
            throws Exception {
        var deployment = deployment(DeploymentId.of("projection"), VALID_GRAPH);
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        var seen = new AtomicReference<RequestReplyContext>();
        var deadline = Instant.now().plusSeconds(5);
        var accepted = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                deployment.requestReply().requestProjected(IngressTarget.start(), context -> {
                    seen.set(context);
                    return PayloadValue.of(context.traversalId().toString());
                }, deadline));
        var exchange = accepted.exchange();
        var outcome = exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state());
        assertEquals(exchange.traversalId().toString(), outcome.payload());
        assertEquals(exchange.correlationId(), seen.get().correlationId());
        assertEquals(exchange.processInstanceId(), seen.get().processInstanceId());
        assertEquals(exchange.traversalId(), seen.get().traversalId());
        assertEquals(deadline, seen.get().deadline());
        assertEquals("projection", seen.get().binding().deploymentId());
        assertEquals(TCK_IDENTITY.tenantId(), seen.get().binding().tenantId());

        var ran = new AtomicInteger();
        var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                deployment.requestReply().requestProjected(IngressTarget.start(), context -> {
                    ran.incrementAndGet();
                    throw PayloadException.tooLarge(64, 16);
                }, Instant.now().plusSeconds(5)));
        assertEquals(RequestReplyRefusal.PAYLOAD_REJECTED, refusal.reason());
        assertEquals(1, ran.get(), "the projection runs once, on the admitting thread");

        // Capacity survives the refusal: the next ordinary request is still admitted and completes.
        var after = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("after"),
                        Instant.now().plusSeconds(5)));
        assertEquals("after", after.exchange().completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS).payload());

        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Startup rollback is atomic: a start that fails part-way must not leave the deployment reachable
     * through the contract surface as anything other than {@code FAILED} with a cause, and must not
     * leave ingress admitting work it never really readied.
     */
    @Test
    final void startupRollbackIsAtomicAndLeavesNoResidueOnTheContractSurface() throws Exception {
        var deployment = deployment(DeploymentId.of("rollback"), UNPARSEABLE_GRAPH);

        var start = deployment.start(TCK_IDENTITY).toCompletableFuture();
        var failure = assertThrows(ExecutionException.class, () -> start.get(10, TimeUnit.SECONDS));
        assertInstanceOf(GraphMlParseException.class, rootCause(failure));

        var status = deployment.status();
        assertEquals(DeploymentState.FAILED, status.state());
        assertTrue(status.cause().isPresent(), "a FAILED status must carry a sanitized cause");

        // No partial residue via the contract surface: ingress must not be admitting on a deployment
        // that never actually reached readiness.
        assertEquals(IngressDisposition.REJECTED_NOT_READY,
                deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "payload"));

        // A repeat start against the same unparseable document fails the same well-defined way rather
        // than hanging, corrupting state, or silently reporting something other than FAILED -- the
        // rollback path is itself idempotent under retry.
        var second = deployment.start(TCK_IDENTITY).toCompletableFuture();
        var secondFailure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
        assertInstanceOf(GraphMlParseException.class, rootCause(secondFailure));
        assertEquals(DeploymentState.FAILED, deployment.status().state());
    }

    /**
     * Concurrent callers of {@code start} on a COLD deployment must all observe the very same
     * readiness, not a race to start twice. Idempotency is the other half: a caller against an already
     * {@code READY} deployment gets the current status immediately.
     */
    @Test
    final void concurrentStartsShareOneReadinessAndFurtherStartsAreIdempotent() throws Exception {
        var deployment = deployment(DeploymentId.of("concurrent-start"), VALID_GRAPH);
        int callers = 12;
        var barrier = new java.util.concurrent.CyclicBarrier(callers);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            var futures = new java.util.ArrayList<Future<DeploymentState>>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS).state();
                }));
            }
            for (var future : futures) {
                assertEquals(DeploymentState.READY, future.get(10, TimeUnit.SECONDS));
            }

            // Idempotent: a start against an already-READY deployment returns immediately.
            var again = deployment.start(TCK_IDENTITY).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(DeploymentState.READY, again.state());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Symmetric to the start case: concurrent {@code stop} callers share one stop, and a stop against
     * an already-{@code STOPPED} deployment is idempotent.
     */
    @Test
    final void concurrentStopsShareOneShutdownAndFurtherStopsAreIdempotent() throws Exception {
        var deployment = deployment(DeploymentId.of("concurrent-stop"), VALID_GRAPH);
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        int callers = 12;
        var barrier = new java.util.concurrent.CyclicBarrier(callers);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            var futures = new java.util.ArrayList<Future<DeploymentState>>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS).state();
                }));
            }
            for (var future : futures) {
                assertEquals(DeploymentState.STOPPED, future.get(10, TimeUnit.SECONDS));
            }

            var again = deployment.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(DeploymentState.STOPPED, again.state());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Late delivery after stop: the disposition is the contract, not whatever exception an adapter
     * might otherwise be tempted to throw. {@link IngressDisposition#REJECTED_ADMISSION_CLOSED} is
     * terminal for this deployment -- stop closes admission and does not reopen it.
     */
    @Test
    final void lateDeliveryAfterStopIsRejectedAsAdmissionClosed() throws Exception {
        var deployment = deployment(DeploymentId.of("late-delivery"), VALID_GRAPH);
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED,
                deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "late"));
        var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("late"),
                        Instant.now().plusSeconds(5)));
        assertEquals(RequestReplyRefusal.ADMISSION_CLOSED, refusal.reason());
    }

    /** {@code restart} composes a completed stop with a start: the readiness it reaches is real too. */
    @Test
    final void restartReachesRealReadinessAgain() throws Exception {
        var deployment = deployment(DeploymentId.of("restart"), VALID_GRAPH);
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "before-restart");

        var status = deployment.restart(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(DeploymentState.READY, status.state());
        assertEquals(IngressDisposition.ACCEPTED,
                deployment.ingress().offer(TCK_IDENTITY, IngressTarget.start(), "after-restart"));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
