package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the durable record says about a traversal that terminated <em>on</em> its payload.
 *
 * <p>A node whose payload boundary rejects a value fails the whole traversal with a
 * {@link PayloadException}, and the process that ran it keeps the typed rejection and re-throws it
 * to a caller reading the result. Nothing durable used to carry that fact: the write recorded
 * {@link ResultPayloadState#NONE}, whose own documentation reserves it for "there was nothing to
 * keep" and forbids reporting that something was kept and then dropped. The consequence was a
 * disagreement between the cache and the authority — the same identifier answering with a typed
 * refusal while the process-local entry was warm, and with a bare {@code 200 Found}, terminal status,
 * no payload, after a restart, from a second instance, or once the entry aged out, indistinguishable
 * from a run that legitimately produced nothing.</p>
 *
 * <p>Each case here is proved from both ends: the state actually written to the store, and what a
 * cold reader — an application instance that never ran the traversal, which is what a restart and a
 * sibling instance both look like — observes.</p>
 */
class RefusedPayloadDurableResultTest {

    private static final String ONE_EFFECT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="one-effect" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="effect">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">refuse-payload</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="effect"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="effect" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aPayloadRefusedForABudgetIsRecordedAsWithheldAndReadsBackAsRedacted() throws Exception {
        // 4 MiB against a 1 MiB budget: a size an operator can raise, which is the whole content of
        // the WITHHELD/UNCONVERTIBLE distinction.
        assertRefusalIsDurablyLegible(PayloadException.tooLarge(4 * 1024 * 1024, 1024 * 1024),
                ResultPayloadState.WITHHELD);
    }

    @Test
    void aPayloadRefusedAsMalformedIsRecordedAsUnconvertibleAndReadsBackAsRedacted() throws Exception {
        // No configuration change admits a malformed document, so this is the other side of the same
        // rule and must not be reported as a limit somebody could raise.
        assertRefusalIsDurablyLegible(PayloadException.malformed(), ResultPayloadState.UNCONVERTIBLE);
    }

    private static void assertRefusalIsDurablyLegible(PayloadException rejection,
                                                      ResultPayloadState expected) throws Exception {
        UUID traversalId = UUID.randomUUID();
        var monitor = new ExecutionMonitor();
        var registry = new BehaviorRegistry().register("refuse-payload",
                message -> CompletableFuture.failedFuture(rejection));
        var terminal = new CountDownLatch(1);

        try (var store = new InMemoryExecutionStore()) {
            try (var engine = new SameThreadExecutionEngine();
                 var application = new DefaultRavenrootApplication(engine, monitor, registry,
                         new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                         ExecutionIdentitySource.randomUuids(), store);
                 AutoCloseable subscription = monitor.subscribe(event -> {
                     if (traversalId.equals(event.traversalId())
                             && event.type() == ExecutionEventType.EXECUTION_FAILED) {
                         terminal.countDown();
                     }
                 })) {

                application.startGraphMl(TestIdentities.TENANT_A, traversalId,
                        new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)), "payload");
                assertTrue(terminal.await(10, TimeUnit.SECONDS), "the traversal must reach a terminal state");

                // Unchanged, and deliberately asserted beside the durable state: the process that ran
                // the traversal still hands the caller the typed rejection. The defect was never that
                // this answer is wrong; it was that nothing durable agreed with it.
                var thrown = assertThrows(PayloadException.class,
                        () -> application.executionResult(TestIdentities.TENANT_A.tenantId(), traversalId));
                assertEquals(rejection.reason(), thrown.reason());
            }

            var recorded = awaitDurableResult(store, traversalId);
            assertEquals(expected, recorded.payload().state(),
                    "a payload that existed and was refused must not be recorded as an execution "
                            + "that produced nothing");
            assertEquals(ProcessInstanceStatus.FAILED, recorded.status());

            // A cold reader: no cache entry, no tombstone, nothing but the store. This is a restart
            // and a sibling instance alike.
            try (var engine = new SameThreadExecutionEngine();
                 var another = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                         new BehaviorRegistry(), new InMemoryArtifactRegistry(),
                         new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store)) {
                var lookup = another.executionResult(TestIdentities.TENANT_A.tenantId(), traversalId);
                var redacted = assertInstanceOf(ExecutionLookup.Redacted.class, lookup,
                        "a refused payload read cold must say the output is not returnable, not "
                                + "report a run that produced nothing as a plain Found");
                assertEquals(expected, redacted.payloadState());
                assertEquals(ProcessInstanceStatus.FAILED, redacted.status());
            }
        }
    }

    /**
     * The durable write happens on the completion action, after the terminal event this test waits
     * on is published, so the record can arrive a moment later. A bounded wait rather than a retried
     * assertion: the write really is asynchronous with respect to that event, and a real regression
     * still fails on the assertion that follows rather than on a silent timeout.
     */
    private static ai.ravenroot.api.persistence.DurableExecutionResult awaitDurableResult(
            InMemoryExecutionStore store, UUID traversalId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var recorded = store.loadExecutionResult(TestIdentities.TENANT_A.tenantId(), traversalId)
                    .toCompletableFuture().join();
            if (recorded.isPresent()) {
                return recorded.get();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("no durable execution result was recorded for the refused payload");
    }
}
