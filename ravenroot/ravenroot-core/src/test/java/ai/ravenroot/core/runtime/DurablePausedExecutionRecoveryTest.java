package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.DurableExecutionPause;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionPauseStatus;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hold survives the process that took it, and a restart neither forgets it nor acts on it.
 *
 * <h2>What this file establishes that the live pause tests cannot</h2>
 * <p>{@code PausedExecutionObservabilityTest} is about what an observer of a <em>running</em> process
 * is shown. Every claim here is about what is true once that process is gone: the hold is still
 * reported, the node it withheld still has not run, a recovery sweep still does not run it, and only
 * an explicit resume does — continuing from the boundary the hold committed rather than repeating
 * the node that had already finished.</p>
 *
 * <h2>How a restart is constructed rather than simulated</h2>
 * <p>The two stores outlive the applications. An application is built over them, holds a traversal,
 * and is closed — which releases every runtime resource it had: its actors, its runner, its lease.
 * A second application is then built over the same two stores, with a fresh monitor, a fresh
 * behaviour registry and a fresh engine, sharing nothing with the first but the durable state. That
 * is exactly what a restart leaves behind, and it is the only channel between the halves of every
 * test here.</p>
 *
 * <h2>The hold is taken where the boundary is</h2>
 * <p>Pausing before a traversal starts holds it at the start node, which has no predecessor and is
 * therefore not a boundary a continuation can be written for. The pause is issued from inside the
 * first node's own behaviour instead, so the hold lands on the hop <em>after</em> it: one completed
 * predecessor, one branch, nothing else in flight. That is the boundary the runtime writes down, and
 * asserting the durable row exists is what proves the test reached it.</p>
 */
final class DurablePausedExecutionRecoveryTest {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration HELD_BOUND = Duration.ofSeconds(1);
    private static final String TENANT = "tenant-a";

    /** Two behaviour nodes, so a hold can sit between them with a completed predecessor behind it. */
    private static final String TWO_EFFECTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="two-effects" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="first">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">first</data>
                </node>
                <node id="second">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">second</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="first"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="first" target="second"><data key="edge-outcome">continue</data></edge>
                <edge id="e3" source="second" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * Two mutually exclusive routes converging on a declared fan-in.
     *
     * <p>Exclusive on purpose: only one route is ever taken, so no node ever dispatches two
     * successors and the traversal never fans out. That is what lets these tests isolate the fan-in
     * and iteration clauses from the fan-out clause, which any ordinary diamond would also trip.
     * {@code joinPolicy=any} makes the single arrival satisfy the merge rather than wait forever for
     * a branch the routing decision already excluded.</p>
     */
    private static final String EXCLUSIVE_MERGE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="node-joinPolicy" for="node" attr.name="joinPolicy" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="exclusive-merge" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="first">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">first</data>
                </node>
                <node id="taken">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">second</data>
                </node>
                <node id="untaken">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">third</data>
                </node>
                <node id="merge">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">merge</data>
                  <data key="node-joinPolicy">any</data>
                </node>
                <node id="after">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">after</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="first"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="first" target="taken"><data key="edge-outcome">left</data></edge>
                <edge id="e3" source="first" target="untaken"><data key="edge-outcome">right</data></edge>
                <edge id="e4" source="taken" target="merge"><data key="edge-outcome">continue</data></edge>
                <edge id="e5" source="untaken" target="merge"><data key="edge-outcome">continue</data></edge>
                <edge id="e6" source="merge" target="after"><data key="edge-outcome">continue</data></edge>
                <edge id="e7" source="after" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /** One node, then two branches from it: the boundary a single-hop continuation cannot carry. */
    private static final String FAN_OUT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="fan-out" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="first">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">first</data>
                </node>
                <node id="second">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">second</data>
                </node>
                <node id="third">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">third</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="first"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="first" target="second"><data key="edge-outcome">continue</data></edge>
                <edge id="e3" source="first" target="third"><data key="edge-outcome">continue</data></edge>
                <edge id="e4" source="second" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * <b>Criterion 1.</b> A traversal held before the process stopped is still reported as held after
     * it restarts, and the durable record says what it is holding.
     *
     * <p>The second application shares nothing with the first except the two stores. Its answer
     * therefore cannot have come from any bookkeeping the first one kept, which is the whole claim:
     * before this, the hold lived only in the process running the traversal and a restart forgot
     * it.</p>
     */
    @Test
    void aTraversalHeldBeforeShutdownIsStillReportedAsPausedAfterRestart() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        DurableExecutionPause durable = stores.heldPause(held.traversalId()).orElseThrow(
                () -> new AssertionError("the hold must have been written down at the boundary"));
        assertEquals(ExecutionPauseStatus.HELD, durable.status());
        assertEquals("second", durable.request().nodeId(),
                "the hold names the node it withheld, which is the one that never ran");

        StoredProcessInstance stored = stores.load(held.key());
        assertEquals(TraversalStatus.WAITING,
                stored.state().traversals().get(held.traversalId()).status(),
                "a held traversal is durably waiting, which is what stops any process from adding "
                        + "an invocation to it");
        assertEquals(ProcessInstanceStatus.WAITING, stored.state().status());

        try (Restarted restarted = stores.restart()) {
            assertTrue(restarted.application().executionPaused(TENANT, held.traversalId()),
                    "a restarted process must report the hold it inherited");
            assertFalse(restarted.application().executionPaused("other-tenant", held.traversalId()),
                    "and must report it to nobody else");
            assertEquals(List.of("first"), held.effects(),
                    "the node after the boundary must not have run before the restart");
        }
    }

    /**
     * <b>Criterion 2.</b> A recovery sweep over the restarted process finds nothing to do, and the
     * node after the boundary stays unrun.
     *
     * <p>This is the assertion the design turns on and it is deliberately not an assertion about a
     * dispatcher being absent. The sweep is given a dispatcher that would accept anything it was
     * handed and records what it was handed; it is handed nothing, because a hold writes no claimable
     * work of any kind. An implementation that relied on there being no dispatcher would pass a test
     * with no dispatcher and fail this one.</p>
     */
    @Test
    void noNodeAfterTheBoundaryRunsWhenRecoverySweepsTheRestartedProcess() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        var dispatched = new ConcurrentLinkedQueue<String>();
        var recovery = new ExecutionRecoveryService(stores.executions(), List.of(TENANT), "recovery-worker",
                50, Duration.ofMinutes(1), RepeatabilityDeclarations.NONE_DECLARED,
                new RecordingRecoveryDispatcher(dispatched));

        List<RecoveryOutcome> outcomes = recovery.sweepOnce();
        assertTrue(outcomes.isEmpty(),
                "a held traversal offers a sweep nothing to claim, so it decides nothing: " + outcomes);
        assertTrue(dispatched.isEmpty(),
                "and a dispatcher that would have taken anything was handed nothing: " + dispatched);

        assertEquals(ExecutionPauseStatus.HELD,
                stores.heldPause(held.traversalId()).orElseThrow().status(),
                "the sweep must leave the hold exactly as it found it");
        assertEquals(List.of("first"), held.effects());
    }

    /**
     * <b>Criterion 3.</b> An authorized resume in the restarted process continues from the committed
     * boundary: the node that had completed is not run again, and the node that was withheld runs
     * for the first time.
     *
     * <p>The effect log is the measurement, and it spans both processes: {@code first} appears once
     * in total, written before the restart, and {@code second} appears once, written after it. A
     * continuation that re-entered at the completed node instead would show {@code first} twice, and
     * a continuation that lost the boundary would show neither.</p>
     */
    @Test
    void resumeAfterRestartContinuesFromTheBoundaryWithoutRepeatingACompletedEffect() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        try (Restarted restarted = stores.restart()) {
            assertTrue(restarted.application().resumeTraversal(TENANT, held.traversalId()),
                    "an inherited hold must be releasable");
            assertTrue(restarted.awaitTerminal(BOUND),
                    "and the continued traversal must reach its terminal event");

            assertEquals(List.of("second"), restarted.effects(),
                    "the restarted process runs the node the hold withheld, and only that node");
            assertEquals(List.of("first"), held.effects(),
                    "the node that had already completed is not run again by the continuation");

            assertEquals(ExecutionPauseStatus.RESUMED,
                    stores.pause(held.key(), stores.anyPauseId(held.key())).status());
            assertTrue(stores.heldPause(held.traversalId()).isEmpty(),
                    "a resumed hold is no longer the traversal's live hold");
            assertFalse(restarted.application().executionPaused(TENANT, held.traversalId()));
            assertEquals(TraversalStatus.COMPLETED,
                    stores.load(held.key()).state().traversals().get(held.traversalId()).status());
        }
    }

    /**
     * The hold path's verification hook, driven through the operator's own resume rather than through
     * the service.
     *
     * <p>The contrast with the test above is the whole assertion: identical hold, identical restart,
     * identical resume call, and the only difference is a restarted process that records manifests
     * and has none for work accepted before it did. The resume must refuse with the typed failure
     * rather than continue the traversal against dependencies nothing recorded, and the hold must
     * still be held afterwards — a refused resume settles nothing.</p>
     */
    @Test
    void resumeRefusesAnInheritedHoldWhoseManifestThisRuntimeDoesNotHave() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        try (var manifests = new ai.ravenroot.core.persistence.InMemoryExecutionManifestStore(
                java.time.Clock.systemUTC());
             Restarted restarted = stores.restartRecordingManifests(manifests)) {

            var refused = assertThrows(
                    ai.ravenroot.api.persistence.ExecutionManifestStoreException.class,
                    () -> restarted.application().resumeTraversal(TENANT, held.traversalId()));
            assertInstanceOf(ai.ravenroot.api.persistence.ExecutionManifestStoreFailure.NotFound.class,
                    refused.failure(),
                    "an execution accepted before this deployment recorded manifests has none, and "
                            + "resuming it would mean resolving today's dependencies for yesterday's "
                            + "acceptance");

            assertEquals(List.of(), restarted.effects(),
                    "the node the hold withheld did not run");
            assertEquals(ExecutionPauseStatus.HELD,
                    stores.pause(held.key(), stores.anyPauseId(held.key())).status(),
                    "and the hold is still held: a refused resume settles nothing");
            assertTrue(stores.heldPause(held.traversalId()).isPresent());
        }
    }

    /**
     * <b>Criterion 4, cancel.</b> Cancelling a recovered hold settles it once, ends the traversal, and
     * runs nothing.
     *
     * <p>The second cancellation is the deterministic half: a settled hold is not a hold, so the
     * repeat finds nothing and says so rather than settling anything a second time.</p>
     */
    @Test
    void cancellingARecoveredHoldSettlesItOnceAndRunsNothing() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        try (Restarted restarted = stores.restart()) {
            assertTrue(restarted.application().cancelTraversal(TENANT, held.traversalId()),
                    "a hold nobody is keeping is still cancellable");
            assertFalse(restarted.application().cancelTraversal(TENANT, held.traversalId()),
                    "and cancelling it again decides nothing further");

            DurableExecutionPause settled = stores.pause(held.key(), stores.anyPauseId(held.key()));
            assertEquals(ExecutionPauseStatus.CANCELLED, settled.status());
            assertFalse(settled.actor().isBlank(), "a settled hold records who settled it");
            assertEquals(TraversalStatus.FAILED,
                    stores.load(held.key()).state().traversals().get(held.traversalId()).status());
            assertEquals(ProcessInstanceStatus.FAILED, stores.load(held.key()).state().status());
            assertFalse(restarted.application().executionPaused(TENANT, held.traversalId()));
            assertTrue(restarted.effects().isEmpty(),
                    "cancelling a hold runs nothing: " + restarted.effects());
            assertEquals(List.of("first"), held.effects());
        }
    }

    /**
     * <b>Criterion 4, stop.</b> Stopping a process is not a decision about the traversals it was
     * holding.
     *
     * <p>This is the guard that separates {@code SHUTDOWN} from {@code ENDED} in the runner's gate
     * release, and it is asserted on the actor of the stored row as well as on its status: a hold
     * settled by a shutdown would name the runtime as having given the traversal up, which is a
     * decision nobody took.</p>
     */
    @Test
    void stoppingTheProcessLeavesTheHoldHeldAndNamesNobodyAsHavingSettledIt() throws Exception {
        var stores = new DurableStores();
        Held held = holdAndStop(stores);

        DurableExecutionPause after = stores.heldPause(held.traversalId()).orElseThrow(
                () -> new AssertionError("a shutdown must not settle a hold"));
        assertEquals(ExecutionPauseStatus.HELD, after.status());
        assertEquals("", after.actor(),
                "a process stopping is nobody's decision, so nobody may be recorded as having made one");
    }

    /**
     * A boundary whose continuation cannot be written down keeps the process-local hold #130 shipped,
     * rather than being refused a hold or given a record that would be wrong on resume.
     *
     * <p>A fan-out is that boundary, and it is the one whose failure mode is worst: two hops are
     * withheld and a continuation can carry one, so a hold written here would resume half a traversal
     * after a restart and silently discard the other branch. The traversal still holds — the two
     * branch nodes not having run is what proves it — and simply holds the way it did before this
     * change.</p>
     *
     * <p>The mark is set before either branch is dispatched, so this is not a race the test has to
     * win: the first branch to reach the gate already reads a traversal that has fanned out.</p>
     */
    @Test
    void aFannedOutBoundaryHoldsWithoutBeingWrittenDown() throws Exception {
        var stores = new DurableStores();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects, FAN_OUT)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "fan-out-hold");
            running.pauseFromFirstNode(traversalId);
            submitter.start();

            assertTrue(running.awaitPaused(BOUND), "the hold must be announced at the fan-out");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and the traversal must be holding");
            assertEquals(List.of("first"), running.effects(),
                    "neither branch may run behind the hold: " + running.effects());
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "a continuation carries one hop, so a fan-out is not a boundary this runtime "
                            + "writes down");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId),
                    "the process-local hold is still releasable");
            assertTrue(running.awaitTerminal(BOUND));
            submitter.join(BOUND.toMillis());
            assertTrue(running.effects().containsAll(List.of("first", "second", "third")),
                    "and both branches run once released: " + running.effects());
        }
    }

    /**
     * Releasing a hold in the process that took it settles the durable row in the same act, so the
     * two can never disagree about whether the traversal is held.
     *
     * <p>Without the settlement the traversal would be released in this process while every other
     * reader, and every restart, still saw it as held — and its next node would be refused by the
     * aggregate, which does not admit an invocation into a waiting traversal. The completion below
     * is therefore not decoration: it is what proves the traversal was put back into a state that
     * can run.</p>
     */
    @Test
    void releasingAHoldInTheProcessThatTookItSettlesTheDurableRowToo() throws Exception {
        var stores = new DurableStores();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "live-hold");
            running.pauseFromFirstNode(traversalId);
            submitter.start();
            assertTrue(running.awaitPaused(BOUND), "the hold must be announced");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and must be holding the second node");
            assertTrue(stores.heldPause(traversalId).isPresent(),
                    "a live hold at a writable boundary is written down as well as kept");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId));
            assertTrue(running.awaitTerminal(BOUND), "the released traversal must complete");
            submitter.join(BOUND.toMillis());

            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "releasing the hold settles the durable row in the same act");
            assertEquals(List.of("first", "second"), effects,
                    "and the released hop runs with the payload it was carrying, losing nothing");
        }
    }

    /**
     * A resume whose durable settlement cannot commit does not release the gate, and says so.
     *
     * <h2>The interleaving, constructed rather than raced</h2>
     * <p>Another writer touches the instance while the traversal is held — which is what a second
     * process deciding about the same hold looks like from here — so the settlement this process
     * attempts is refused. The gate must then stay closed. Releasing it anyway would hand the parked
     * hop a thread while the traversal is still durably {@code WAITING}, where the aggregate refuses
     * to add its invocation, so the hop would fail the traversal instead of continuing it — and the
     * caller would have been told the resume succeeded.</p>
     *
     * <p>The assertion that the withheld node still has not run is what makes this a guard rather
     * than a report: a released gate would have run it.</p>
     */
    @Test
    void aResumeWhoseSettlementCannotCommitKeepsTheGateClosedAndReportsFailure() throws Exception {
        var stores = new DurableStores();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "unsettlable-resume");
            running.pauseFromFirstNode(traversalId);
            submitter.start();
            assertTrue(running.awaitPaused(BOUND));
            assertFalse(running.awaitTerminal(HELD_BOUND));
            ExecutionKey key = stores.keyOf(traversalId);
            assertTrue(stores.heldPause(traversalId).isPresent(), "the hold must be written down");

            // Another writer settles the hold first. This process's settlement can no longer commit.
            stores.settleElsewhere(key, stores.anyPauseId(key));

            assertFalse(running.application().resumeTraversal(TENANT, traversalId),
                    "a resume that cannot settle the hold must report failure rather than success");
            assertFalse(running.awaitTerminal(HELD_BOUND),
                    "and must not have released the gate");
            assertEquals(List.of("first"), running.effects(),
                    "the withheld node must still not have run: " + running.effects());
            assertTrue(running.application().executionPaused(TENANT, traversalId),
                    "the traversal must still be reported as held: a refused resume that dropped the "
                            + "hold would leave a hop parked on a gate nothing can ever release, and "
                            + "an operator told there was nothing there to release");
        }
    }

    /**
     * A store that does not keep holds takes the hold and writes nothing, which is exactly the
     * behaviour that shipped before holds were durable.
     *
     * <p>The capability is withheld from a store that otherwise has it, so everything else about the
     * run is identical to the tests above and the only difference is the declaration. The traversal
     * still holds — the withheld node not running is what proves it — and still releases.</p>
     */
    @Test
    void aStoreThatDoesNotKeepHoldsTakesTheHoldWithoutWritingItDown() throws Exception {
        var stores = new DurableStores(Set.of(StoreCapability.EXECUTION_PAUSES));
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "no-capability-hold");
            running.pauseFromFirstNode(traversalId);
            submitter.start();

            assertTrue(running.awaitPaused(BOUND), "the hold must be announced");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and the traversal must be holding");
            assertEquals(List.of("first"), running.effects(),
                    "the withheld node must not have run: " + running.effects());
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "a store that does not declare that it keeps holds must not be asked to write one");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId),
                    "and the process-local hold must still be releasable");
            assertTrue(running.awaitTerminal(BOUND));
            submitter.join(BOUND.toMillis());
            assertEquals(List.of("first", "second"), running.effects());
        }
    }

    /** An unrepresentable node result fails before either durable or process-local routing. */
    @Test
    void aPayloadTheTypeModelCannotRepresentIsNotWrittenDown() throws Exception {
        var stores = new DurableStores();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            running.emitOpaquePayloadFromFirstNode();
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "opaque-payload-hold");
            running.pauseFromFirstNode(traversalId);
            submitter.start();

            assertTrue(running.awaitTerminal(BOUND),
                    "the graph payload boundary must fail before routing the opaque value");
            assertEquals(List.of("first"), running.effects(),
                    "the withheld node must not have run: " + running.effects());
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "an opaque result must never become durable continuation data");
            assertFalse(running.application().resumeTraversal(TENANT, traversalId),
                    "a failed traversal must not retain a process-local hold");
            submitter.join(BOUND.toMillis());
            assertEquals(List.of("first"), running.effects());
        }
    }

    /**
     * A hold landing on the traversal's own first node writes nothing, because there is no completed
     * invocation for a continuation to sit behind.
     *
     * <p>Reaching that boundary means holding a traversal that has been admitted and has not
     * dispatched its start node, which is a real window and not a contrived one: the submission is
     * suspended at its first durable write, exactly as {@code PausedExecutionObservabilityTest}
     * enters the same window, and the hold is taken there.</p>
     */
    @Test
    void aHoldAtTheTraversalsFirstNodeIsNotWrittenDown() throws Exception {
        var stores = new DurableStores();
        var gate = stores.suspendFirstWrite();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "first-node-hold");
            submitter.start();

            assertTrue(gate.awaitFirstWrite(BOUND), "the submission must reach the startup window");
            assertTrue(running.application().pauseTraversal(traversalId),
                    "a traversal admitted and not yet started must accept a hold");
            gate.releaseFirstWrite();

            assertTrue(running.awaitPaused(BOUND), "the hold must be announced once the traversal starts");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and the traversal must be holding");
            assertTrue(effects.isEmpty(), "no node may run behind the hold: " + effects);
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "the start node has no predecessor invocation to anchor a continuation to");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId));
            assertTrue(running.awaitTerminal(BOUND));
            submitter.join(BOUND.toMillis());
            assertEquals(List.of("first", "second"), running.effects());
        }
    }

    /**
     * A hop entering a fan-in writes nothing: continuing one arrival of a correlation the join store
     * owns would present that arrival to the join twice.
     *
     * <p>The routing is exclusive, so this traversal never fans out and the fan-out clause is not
     * what refuses the boundary. Two clauses do refuse it together — a hop that has passed a firing
     * also carries that firing's lap — and the assertion here is over the behaviour rather than over
     * which of them acted; the lap clause is isolated by the test below it and the fan-in clause by
     * {@code PauseBoundaryAdmissionTest}.</p>
     */
    @Test
    void aHoldEnteringAFanInIsNotWrittenDown() throws Exception {
        var stores = new DurableStores();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        UUID traversalId = UUID.randomUUID();
        try (Restarted running = stores.open(effects, EXCLUSIVE_MERGE)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "fan-in-hold");
            running.pauseFromNode("second");
            running.pauseFromFirstNode(traversalId);
            submitter.start();

            assertTrue(running.awaitPaused(BOUND), "the hold must be announced");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and the traversal must be holding");
            assertEquals(List.of("first", "second"), running.effects(),
                    "the hold must sit on the hop entering the merge: " + running.effects());
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "an arrival at a fan-in is not a boundary a one-hop continuation can carry");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId));
            assertTrue(running.awaitTerminal(BOUND));
            submitter.join(BOUND.toMillis());
            assertTrue(running.effects().containsAll(List.of("merge", "after")),
                    "and the released traversal passes the merge: " + running.effects());
        }
    }

    /**
     * A hold taken after a fan-in has fired writes nothing, because the withheld dispatch carries
     * that firing's lap and a lap lives only in the runner.
     *
     * <p>This is the isolated iteration case: the withheld node is an ordinary one with a single
     * completed predecessor on a traversal that never fanned out, so the lap is the only thing
     * refusing the boundary.</p>
     */
    @Test
    void aHoldInsideAnIterationIsNotWrittenDown() throws Exception {
        var stores = new DurableStores();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        UUID traversalId = UUID.randomUUID();
        try (Restarted running = stores.open(effects, EXCLUSIVE_MERGE)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "lap-hold");
            running.pauseFromNode("merge");
            running.pauseFromFirstNode(traversalId);
            submitter.start();

            assertTrue(running.awaitPaused(BOUND), "the hold must be announced");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and the traversal must be holding");
            assertEquals(List.of("first", "second", "merge"), running.effects(),
                    "the hold must sit on the hop after the merge fired: " + running.effects());
            assertTrue(stores.heldPause(traversalId).isEmpty(),
                    "a dispatch carrying a lap is not a boundary this runtime writes down");

            assertTrue(running.application().resumeTraversal(TENANT, traversalId));
            assertTrue(running.awaitTerminal(BOUND));
            submitter.join(BOUND.toMillis());
            assertTrue(running.effects().contains("after"),
                    "and the released hop runs: " + running.effects());
        }
    }

    /**
     * A cancellation whose hold settlement the store refuses still ends the traversal.
     *
     * <h2>The interaction this pins, which neither half suggests on its own</h2>
     * <p>A refused settlement on a traversal that is ending must not refuse the release — the parked
     * hop and the teardown waiting on it would be stranded. But the flag that suppresses a held
     * traversal's terminal write is the same flag the settlement would have cleared, so letting the
     * release proceed while keeping the flag set means the teardown writes <em>nothing</em>: the
     * stored traversal stays {@code WAITING} behind a hold that stays {@code HELD}, while the caller
     * is told the execution was cancelled. After a restart an authorized resume would then run work
     * the operator had been told was stopped.</p>
     *
     * <p>The refusal is narrow by construction — the fixture fails the settlement batch and nothing
     * else — so this test cannot pass by the traversal being unable to write anything at all.</p>
     */
    @Test
    void aCancellationWhoseSettlementIsRefusedStillEndsTheTraversal() throws Exception {
        var stores = new DurableStores();
        var recording = stores.recordWrites();
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "refused-settlement");
            running.pauseFromFirstNode(traversalId);
            submitter.start();
            assertTrue(running.awaitPaused(BOUND));
            assertFalse(running.awaitTerminal(HELD_BOUND));
            ExecutionKey key = stores.keyOf(traversalId);
            assertTrue(stores.heldPause(traversalId).isPresent(), "the hold must be written down");

            recording.failNextHoldSettlement();
            assertTrue(running.application().cancelTraversal(TENANT, traversalId),
                    "a cancellation must not be refused because the hold could not be settled");
            assertTrue(running.awaitTerminal(BOUND),
                    "and the traversal must reach its terminal event rather than stay parked");
            submitter.join(BOUND.toMillis());

            assertEquals(TraversalStatus.FAILED,
                    stores.load(key).state().traversals().get(traversalId).status(),
                    "the traversal's end must be written even though the hold could not be settled: "
                            + "leaving it WAITING would let a resume after a restart run work the "
                            + "caller was told had been cancelled");
            assertEquals(List.of("first"), running.effects(),
                    "and nothing after the boundary may run: " + running.effects());
            assertFalse(running.application().executionPaused(TENANT, traversalId),
                    "a terminal traversal is never reported as held, whatever the stale row says");

            // The stale row is the residue this trade accepts, and it is settleable rather than
            // permanent: a later cancellation clears it without asking the aggregate for a second
            // terminal transition.
            assertEquals(ExecutionPauseStatus.HELD,
                    stores.pause(key, stores.anyPauseId(key)).status(),
                    "the refused settlement leaves the row as it was");
            assertTrue(running.application().cancelTraversal(TENANT, traversalId),
                    "and a later cancellation settles it");
            assertEquals(ExecutionPauseStatus.CANCELLED,
                    stores.pause(key, stores.anyPauseId(key)).status());
        }
    }

    // ------------------------------------------------------------------ fixture

    /** Runs one traversal up to the boundary, holds it there, and stops the process. */
    private Held holdAndStop(DurableStores stores) throws Exception {
        UUID traversalId = UUID.randomUUID();
        var effects = Collections.synchronizedList(new ArrayList<String>());
        ExecutionKey key;
        try (Restarted running = stores.open(effects)) {
            var submitter = new Thread(() -> running.application().startGraphMl(TestIdentities.TENANT_A,
                    traversalId, running.graph(), "payload"), "holding-submitter");
            running.pauseFromFirstNode(traversalId);
            submitter.start();
            assertTrue(running.awaitPaused(BOUND), "the traversal must reach the boundary and hold");
            assertFalse(running.awaitTerminal(HELD_BOUND), "and must still be holding");
            key = stores.keyOf(traversalId);
            assertTrue(stores.heldPause(traversalId).isPresent(),
                    "the boundary the pause landed on must be one the runtime can write down; "
                            + "without that this test measures nothing");
            // The submitter is parked on the gate. Closing the application releases it.
            submitter.interrupt();
        }
        return new Held(key, traversalId, List.copyOf(effects));
    }

    private record Held(ExecutionKey key, UUID traversalId, List<String> effects) {
    }

    /** The only thing two "processes" in this file share. */
    private static final class DurableStores {
        private final InMemoryExecutionStore executions = new InMemoryExecutionStore();
        private final InMemoryGraphDefinitionStore definitions =
                new InMemoryGraphDefinitionStore(java.time.Clock.systemUTC());
        private final Set<StoreCapability> withheld;
        private SuspendFirstWriteExecutionStore firstWriteGate;

        private DurableStores() {
            this(Set.of());
        }

        /**
         * @param withheld capabilities the application is not told about, while the assertions still
         *                 read the underlying store directly — so "the runtime did not write a hold"
         *                 and "the store could not have kept one" stay separable
         */
        private DurableStores(Set<StoreCapability> withheld) {
            this.withheld = Set.copyOf(withheld);
        }

        private InMemoryExecutionStore executions() {
            return executions;
        }

        /** Suspends the next application's first durable write, so a test can stand in the startup window. */
        private SuspendFirstWriteExecutionStore suspendFirstWrite() {
            firstWriteGate = new SuspendFirstWriteExecutionStore(executions);
            return firstWriteGate;
        }

        private ThreadRecordingExecutionStore recorder;

        /** Wraps the store so a test can refuse one specific write; returns the handle that does it. */
        private ThreadRecordingExecutionStore recordWrites() {
            recorder = new ThreadRecordingExecutionStore(executions, withheld);
            return recorder;
        }

        private ExecutionStore forApplication() {
            if (recorder != null) {
                return recorder;
            }
            ExecutionStore base = firstWriteGate != null ? firstWriteGate : executions;
            return withheld.isEmpty() ? base : new ThreadRecordingExecutionStore(base, withheld);
        }

        private Restarted open(List<String> effects) {
            return open(effects, TWO_EFFECTS);
        }

        private Restarted open(List<String> effects, String graphMl) {
            return new Restarted(forApplication(), definitions, effects, graphMl);
        }

        private Restarted restart() {
            return new Restarted(forApplication(), definitions,
                    Collections.synchronizedList(new ArrayList<>()), TWO_EFFECTS);
        }

        /** A restart that records manifests, and has none for work accepted before it did. */
        private Restarted restartRecordingManifests(
                ai.ravenroot.api.persistence.ExecutionManifestStore manifests) {
            return new Restarted(forApplication(), definitions,
                    Collections.synchronizedList(new ArrayList<>()), TWO_EFFECTS, manifests);
        }

        private StoredProcessInstance load(ExecutionKey key) {
            return executions.load(key).toCompletableFuture().join();
        }

        private Optional<DurableExecutionPause> heldPause(UUID traversalId) {
            return executions.findHeldExecutionPause(TENANT, traversalId).toCompletableFuture().join();
        }

        /** Settles a hold from outside the process that took it, as a second decider would. */
        private void settleElsewhere(ExecutionKey key, UUID pauseId) {
            StoredProcessInstance current = load(key);
            executions.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                    .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(current.revision()))
                    .applyExecutionPause(new ai.ravenroot.api.persistence.ExecutionPauseTransition.Cancelled(
                            pauseId, "issuer|USER|other-operator"))
                    .build()).toCompletableFuture().join();
        }

        private DurableExecutionPause pause(ExecutionKey key, UUID pauseId) {
            return executions.loadExecutionPause(key, pauseId).toCompletableFuture().join().orElseThrow();
        }

        private UUID anyPauseId(ExecutionKey key) {
            List<DurableExecutionPause> all = executions.executionPauses(key).toCompletableFuture().join();
            if (all.size() != 1) {
                throw new AssertionError("expected exactly one hold on " + key + ", found " + all.size());
            }
            return all.getFirst().request().pauseId();
        }

        /** Resolves the process instance that carries this traversal, from the durable inventory. */
        private ExecutionKey keyOf(UUID traversalId) {
            return executions.listProcessInstances(TENANT,
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.outstanding(100))
                    .toCompletableFuture().join().items().stream()
                    .map(ai.ravenroot.api.persistence.ProcessInventoryEntry::key)
                    .filter(candidate -> executions.load(candidate).toCompletableFuture().join()
                            .state().traversals().containsKey(traversalId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no stored instance carries " + traversalId));
        }
    }

    /** One "process": its own engine, monitor, registry and application over the shared stores. */
    private static final class Restarted implements AutoCloseable {
        private final SameThreadExecutionEngine engine = new SameThreadExecutionEngine();
        private final ExecutionMonitor monitor = new ExecutionMonitor();
        private final List<String> effects;
        private final DefaultRavenrootApplication application;
        private final AutoCloseable subscription;
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private volatile UUID pauseWhenFirstRuns;
        /**
         * The application the behaviour pauses through, read at node time rather than captured.
         *
         * <p>The registry is built before the application exists, because the application takes the
         * registry, so the behaviour cannot close over the field directly.</p>
         */
        private final java.util.concurrent.atomic.AtomicReference<DefaultRavenrootApplication> self =
                new java.util.concurrent.atomic.AtomicReference<>();

        private final String graphMl;
        /** The node whose behaviour issues the hold, so the gate lands on the hop after it. */
        private volatile String pauseAfterNode = "first";
        private volatile boolean opaquePayload;

        private Restarted(ExecutionStore executions, InMemoryGraphDefinitionStore definitions,
                          List<String> effects, String graphMl) {
            this(executions, definitions, effects, graphMl, null);
        }

        private Restarted(ExecutionStore executions, InMemoryGraphDefinitionStore definitions,
                          List<String> effects, String graphMl,
                          ai.ravenroot.api.persistence.ExecutionManifestStore manifests) {
            this.effects = effects;
            this.graphMl = graphMl;
            var registry = new BehaviorRegistry()
                    // "first" additionally selects the exclusive route in EXCLUSIVE_MERGE, so only
                    // one of the two branches is ever dispatched and the traversal never fans out.
                    .register("first", node("first", "left"))
                    .register("second", node("second", null))
                    .register("third", node("third", null))
                    .register("merge", node("merge", null))
                    .register("after", node("after", null));
            this.application = new DefaultRavenrootApplication(engine, monitor, registry,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), executions, 8,
                    UnknownBehaviorPolicy.passThrough(), definitions, null, null,
                    GraphExecutionLimits.DEFAULTS, null, manifests);
            this.self.set(application);
            this.subscription = monitor.subscribe(event -> {
                if (event.type() == ExecutionEventType.EXECUTION_PAUSED) {
                    paused.countDown();
                }
                if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                        || event.type() == ExecutionEventType.EXECUTION_FAILED) {
                    terminal.countDown();
                }
            });
        }

        private DefaultRavenrootApplication application() {
            return application;
        }

        private ByteArrayInputStream graph() {
            return new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8));
        }

        private List<String> effects() {
            return List.copyOf(effects);
        }

        /**
         * One behaviour: record that it ran, hold the traversal if this is the node the test holds
         * from, and hand its payload on.
         *
         * <p>The hold is issued from <em>inside</em> a node rather than before the traversal starts,
         * which is what puts the gate on the hop after that node: one completed predecessor behind
         * it and nothing else in flight. Which node issues it decides which boundary the hold lands
         * on, and that is the whole variable these tests turn.</p>
         */
        private NodeHandler node(String id, String outcome) {
            return message -> {
                effects.add(id);
                UUID holding = pauseWhenFirstRuns;
                if (holding != null && id.equals(pauseAfterNode)) {
                    self.get().pauseTraversal(holding);
                }
                Object payload = opaquePayload && "first".equals(id)
                        // Not representable by the payload type model, which is what an in-process
                        // behaviour handing on an arbitrary Java value produces.
                        ? new Object() : message.payload();
                return CompletableFuture.completedFuture(
                        new NodeResult(outcome, payload, java.util.Map.of()));
            };
        }

        private void pauseFromFirstNode(UUID traversalId) {
            pauseWhenFirstRuns = traversalId;
        }

        /** Holds from inside {@code nodeId} instead of from the traversal's first behaviour node. */
        private void pauseFromNode(String nodeId) {
            pauseAfterNode = nodeId;
        }

        private void emitOpaquePayloadFromFirstNode() {
            opaquePayload = true;
        }

        private boolean awaitPaused(Duration bound) throws InterruptedException {
            return paused.await(bound.toMillis(), TimeUnit.MILLISECONDS);
        }

        private boolean awaitTerminal(Duration bound) throws InterruptedException {
            return terminal.await(bound.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            subscription.close();
            application.close();
            engine.close();
        }
    }

    /** A dispatcher that would accept anything, so "nothing was dispatched" is not "nothing could be". */
    private record RecordingRecoveryDispatcher(ConcurrentLinkedQueue<String> dispatched)
            implements ai.ravenroot.core.recovery.RecoveryDispatcher {
        @Override
        public boolean canDispatch(ai.ravenroot.api.persistence.PendingWork work) {
            return true;
        }

        @Override
        public void dispatch(ai.ravenroot.api.persistence.PendingWork work, String effectKey) {
            dispatched.add(work.getClass().getSimpleName() + ":" + effectKey);
        }
    }
}
