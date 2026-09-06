package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one result-payload decision shared by process-local and durable retention. */
class DefaultRavenrootApplicationResultPayloadAdmissionTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="result-admission" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="produce">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">produce</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="produce"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="produce" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void noStoreStillAppliesTheConfiguredResultCapBeforeTheResultBecomesReadable() throws Exception {
        UUID traversalId = UUID.randomUUID();
        var monitor = new ExecutionMonitor();
        var rejected = new CountDownLatch(1);
        var rejectionCount = new AtomicInteger();
        try (var subscription = monitor.subscribeResultPayloadAdmissions(state -> {
                 rejectionCount.incrementAndGet();
                 rejected.countDown();
             });
             var engine = new SameThreadExecutionEngine();
             var application = application(engine, monitor, null, limitsWithPayloadBytes(24),
                     "token=12345678")) {
            application.startGraphMl(TestIdentities.TENANT_A, traversalId, graph(), "input");

            assertTrue(rejected.await(10, TimeUnit.SECONDS));
            var redacted = awaitRedacted(application, traversalId);
            assertEquals(ResultPayloadState.WITHHELD, redacted.payloadState());
            assertEquals(1, rejectionCount.get(), "one canonical decision emits one aggregate observation");
        }
    }

    @Test
    void noStoreReportsAnUnsupportedRootAsAContentFreeTypedRefusal() throws Exception {
        UUID traversalId = UUID.randomUUID();
        var monitor = new ExecutionMonitor();
        try (var engine = new SameThreadExecutionEngine();
             var application = application(engine, monitor, null, GraphExecutionLimits.DEFAULTS, new Object())) {
            application.startGraphMl(TestIdentities.TENANT_A, traversalId, graph(), "input");

            var redacted = awaitRedacted(application, traversalId);
            assertEquals(ResultPayloadState.UNCONVERTIBLE, redacted.payloadState());
        }
    }

    /**
     * Observes the local result while the durable write is deliberately unable to finish. A final
     * state assertion cannot distinguish admit-before-retain from retain-then-replace; this instant
     * can. If the old raw-payload shortcut is restored, this lookup exposes the oversized string and
     * the assertion fails before the durable path can repair it.
     */
    @Test
    void aBlockedDurableWriteCannotExposeRawPayloadFromTheLocalRegistry() throws Exception {
        UUID traversalId = UUID.randomUUID();
        var enteredResultWrite = new CountDownLatch(1);
        var releaseResultWrite = new CountDownLatch(1);
        var recorded = new AtomicReference<DurableExecutionResult>();
        try (var delegate = new InMemoryExecutionStore()) {
            ExecutionStore blocking = blockingResultStore(
                    delegate, 64, enteredResultWrite, releaseResultWrite, recorded);
            var monitor = new ExecutionMonitor();
            try (var engine = new SameThreadExecutionEngine();
                 var application = application(engine, monitor, blocking, GraphExecutionLimits.DEFAULTS,
                         "x".repeat(256))) {
                Thread submission = Thread.startVirtualThread(() -> application.startGraphMl(
                        TestIdentities.TENANT_A, traversalId, graph(), "input"));

                assertTrue(enteredResultWrite.await(10, TimeUnit.SECONDS),
                        "the completion callback must reach the deliberately blocked result write");
                var redacted = assertInstanceOf(ExecutionLookup.Redacted.class,
                        application.executionResult(TestIdentities.TENANT_A.tenantId(), traversalId));
                assertEquals(ResultPayloadState.WITHHELD, redacted.payloadState(),
                        "local visibility must already contain the admitted state while persistence is blocked");
                assertEquals(ResultPayloadState.WITHHELD, recorded.get().payload().state());
                assertNull(recorded.get().payload().retained());

                releaseResultWrite.countDown();
                submission.join(Duration.ofSeconds(10));
                assertTrue(!submission.isAlive());
            } finally {
                releaseResultWrite.countDown();
            }
        }
    }

    private static DefaultRavenrootApplication application(
            SameThreadExecutionEngine engine, ExecutionMonitor monitor, ExecutionStore store,
            GraphExecutionLimits limits, Object output) {
        var behaviors = new BehaviorRegistry().register("produce", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(output)));
        return new DefaultRavenrootApplication(engine, monitor, behaviors,
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store, 0, UnknownBehaviorPolicy.passThrough(), limits);
    }

    private static GraphExecutionLimits limitsWithPayloadBytes(int bytes) {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        PayloadLimits payload = defaults.payload();
        return new GraphExecutionLimits(defaults.graphMl(),
                new PayloadLimits(bytes, payload.maxDepth(), payload.maxCollectionSize(),
                        payload.maxValueCount(), payload.maxTextLength(), payload.maxKeyLength()),
                defaults.maxFanOut(), defaults.maxResidentActors(), defaults.maxLiveActorsPerTraversal(),
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                defaults.maxTraversalSteps(), defaults.maxAmplifiedDeliveries(),
                defaults.maxCumulativePayloadBytes(), defaults.maxRecoveryDeliveriesPerAttempt());
    }

    private static ByteArrayInputStream graph() {
        return new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    private static ExecutionLookup.Redacted awaitRedacted(
            DefaultRavenrootApplication application, UUID traversalId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var lookup = application.executionResult(TestIdentities.TENANT_A.tenantId(), traversalId);
            if (lookup instanceof ExecutionLookup.Redacted redacted) return redacted;
            Thread.sleep(10);
        }
        throw new AssertionError("execution did not publish its admitted terminal payload");
    }

    private static ExecutionStore blockingResultStore(
            ExecutionStore delegate, int resultCap, CountDownLatch entered, CountDownLatch release,
            AtomicReference<DurableExecutionResult> recorded) {
        return (ExecutionStore) Proxy.newProxyInstance(
                ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (ignored, method, arguments) -> {
                    if (method.getName().equals("maxExecutionResultPayloadBytes")) return resultCap;
                    if (method.getName().equals("recordExecutionResult")) {
                        recorded.set((DurableExecutionResult) arguments[0]);
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release the durable result write");
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }
}
