package ai.ravenroot.core.pause;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeDirective;
import ai.ravenroot.api.persistence.DurableExecutionPause;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;
import ai.ravenroot.api.persistence.ExecutionPauseTransition;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/**
 * Reads, continues and gives up traversals that are durably held, in a process that need not be the
 * one that held them.
 *
 * <h2>Why this is not driven by a recovery sweep</h2>
 * <p>A hold produces no claimable work: no {@link ai.ravenroot.api.persistence.PendingWork}, no
 * {@code SCHEDULED} attempt, no timer. That is deliberate and it is the whole of "recovery leaves a
 * paused traversal paused" — there is nothing for a sweep to find, so nothing can be dispatched by
 * one, and no dispatcher's absence is load-bearing. A held traversal moves only when a principal
 * authorized to resume or cancel asks it to, through the same control surface that authorized and
 * audited the hold.</p>
 *
 * <h2>What a continuation reconstructs, and against what</h2>
 * <p>The graph is loaded from the immutable bytes the hold pinned, never from whatever is currently
 * deployed, so a traversal held across a redeploy continues against the program it was running. The
 * runtime — engine, behaviours, runner, lease — is built for this continuation and closed with it.
 * Nothing of the process that took the hold is reached for, because nothing of it survived.</p>
 */
public final class DurableExecutionPauseService {

    private static final java.util.concurrent.Executor CLEANUP_EXECUTOR = ForkJoinPool.commonPool();

    private final GraphDefinitionStore definitions;
    private final ExecutionStore executions;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identities;
    private final String workerId;
    private final Duration leaseTtl;

    /**
     * Composes the service against the stores and runtime a continuation has to rebuild from.
     *
     * @param definitions the pinned graph bytes a held traversal is continued against.
     * @param executions the store the hold and the aggregate live in.
     * @param engine the execution engine a continuation dispatches into.
     * @param behaviors the behaviour registry the rebuilt runner resolves nodes through.
     * @param monitor the live event stream the continuation publishes to.
     * @param identities the identity source the continuation mints invocation identities from.
     * @param workerId this process's identity, presented when claiming the instance.
     * @param leaseTtl how long the continuation's claim on the instance lasts between renewals.
     */
    public DurableExecutionPauseService(GraphDefinitionStore definitions, ExecutionStore executions,
                                        ExecutionEngine engine, BehaviorRegistry behaviors,
                                        ExecutionMonitor monitor, ExecutionIdentitySource identities,
                                        String workerId, Duration leaseTtl) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    }

    /**
     * Whether this deployment can answer durable hold questions at all.
     *
     * @return whether the composed store declares {@link StoreCapability#EXECUTION_PAUSES}.
     */
    public boolean available() {
        return executions.supports(StoreCapability.EXECUTION_PAUSES);
    }

    /**
     * Reads the hold currently on a traversal, whether or not this process took it.
     *
     * <p>This is what makes a traversal held before a restart still <em>report</em> as held after
     * one: the answer comes from the store rather than from any registry of this process, which by
     * definition has none for a traversal it never ran.</p>
     *
     * @param tenantId authenticated tenant boundary.
     * @param traversalId the traversal being asked about.
     * @return the current hold, or empty when the traversal is not held or holds are unsupported.
     */
    public Optional<DurableExecutionPause> held(String tenantId, UUID traversalId) {
        if (!available()) return Optional.empty();
        // Deliberately not wrapped in a catch. An unreadable store is not "this traversal is not
        // held": answering that would tell an operator during an outage that a hold they took is
        // gone, which is the one wrong answer this whole mechanism exists to stop the system giving.
        // Every caller either surfaces the failure or fails closed on it.
        Optional<DurableExecutionPause> found =
                executions.findHeldExecutionPause(tenantId, traversalId).toCompletableFuture().join();
        // A terminal traversal is never held, the same invariant ExecutionOutcome enforces on the
        // live side. A hold row can outlive its traversal by one narrow path -- a settlement the
        // store refused while the traversal was ending, which GraphRunner deliberately lets go of so
        // the end can still be written -- and reporting that row would tell an operator that
        // finished work is waiting for them.
        return found.filter(pause -> !terminalTraversal(pause));
    }

    /**
     * The stored hold on a traversal, terminal traversal included.
     *
     * <p>{@link #held} hides a hold whose traversal has ended, because reporting one would tell an
     * operator that finished work is waiting for them. Settling one is the opposite: a stale row is
     * exactly what a cancellation should be able to clear, and filtering it out of the settlement
     * path as well would make it permanent.</p>
     */
    private Optional<DurableExecutionPause> heldIncludingStale(String tenantId, UUID traversalId) {
        if (!available()) return Optional.empty();
        return executions.findHeldExecutionPause(tenantId, traversalId).toCompletableFuture().join();
    }

    private boolean terminalTraversal(DurableExecutionPause pause) {
        try {
            Traversal traversal = executions.load(pause.key()).toCompletableFuture().join()
                    .state().traversals().get(pause.request().traversalId());
            return traversal != null && traversal.status().terminal();
        } catch (RuntimeException unreadable) {
            // Unreadable is not terminal. Hiding a hold because the instance could not be read would
            // turn a transient fault into "there is nothing here", which is the one answer an
            // operator must not be given about work that is being held.
            return false;
        }
    }

    /**
     * Continues a held traversal from its committed boundary, under the authority of {@code actor}.
     *
     * <p>The settlement and the traversal's return to {@code RUNNING} commit as one batch before any
     * runtime is built. Splitting them would open the one window this design cannot survive: a hold
     * that is gone over a traversal that is still {@code WAITING}, which no process may ever add an
     * invocation to and no operator may ever release again.</p>
     *
     * @param tenantId authenticated tenant boundary.
     * @param traversalId the held traversal to continue.
     * @param actor audit-stable identity of the principal that authorized the resume.
     * @return a stage completing when the continued traversal settles, or empty when this traversal
     *         is not durably held here.
     */
    public Optional<CompletionStage<Void>> resume(String tenantId, UUID traversalId, String actor) {
        Optional<DurableExecutionPause> found = held(tenantId, traversalId);
        if (found.isEmpty()) return Optional.empty();
        DurableExecutionPause pause = found.get();
        ExecutionPauseRegistration request = pause.request();
        ExecutionPauseContinuation continuation;
        try {
            continuation = ExecutionPauseContinuation.decode(request.continuationVersion(),
                    request.continuation());
        } catch (RuntimeException undecodable) {
            // A hold this build cannot read stays held. Settling it would discard a traversal an
            // operator deliberately kept, on the strength of not understanding it.
            throw new IllegalStateException("this build cannot continue hold " + request.pauseId()
                    + ": " + undecodable.getMessage(), undecodable);
        }
        Prepared prepared = prepare(pause);
        GraphManager manager = prepared.manager();
        ExecutionRecorder recorder;
        try {
            recorder = ExecutionRecorder.open(executions, pause.key(), workerId, leaseTtl,
                    executions.load(pause.key()).toCompletableFuture().join().revision());
        } catch (RuntimeException unavailable) {
            manager.close();
            throw unavailable;
        }
        var runner = new GraphRunner(manager, prepared.snapshot(), engine, behaviors, monitor, identities,
                GraphRunner.DEFAULT_SHUTDOWN_BOUND);
        try {
            recorder.settleExecutionPause(
                    new ExecutionPauseTransition.Resumed(request.pauseId(), actor), traversalId,
                    TraversalStatus.RUNNING, ProcessInstanceStatus.RUNNING);
        } catch (RuntimeException notSettled) {
            runner.close();
            recorder.close();
            manager.close();
            throw notSettled;
        }
        CompletionStage<Void> result = runner.executeFromPause(request.requester(),
                pause.key().processInstanceId(), traversalId, request.nodeId(),
                request.graphVersionPin().reference(), recorder, request.afterInvocationId(),
                continuation.payloadValue(), continuation.attributeValues(), commandOf(request));
        // Pekko may complete on the node's own actor-dispatcher thread, and runner shutdown waits for
        // that node, so cleanup moves off the completion thread rather than waiting on itself.
        return Optional.of(result.whenCompleteAsync((ignored, failure) -> {
            runner.close();
            recorder.close();
            manager.close();
        }, CLEANUP_EXECUTOR));
    }

    /**
     * Gives up a held traversal without continuing it, under the authority of {@code actor}.
     *
     * <p>The hold and the traversal's end commit together, and the process instance follows only
     * when this was its last unfinished traversal. Failing an instance that still has live traversals
     * would end work nobody cancelled.</p>
     *
     * @param tenantId authenticated tenant boundary.
     * @param traversalId the held traversal to give up.
     * @param actor audit-stable identity of the principal that authorized the cancellation.
     * @return whether a hold was found and settled here.
     */
    public boolean cancel(String tenantId, UUID traversalId, String actor) {
        Optional<DurableExecutionPause> found = heldIncludingStale(tenantId, traversalId);
        if (found.isEmpty()) return false;
        DurableExecutionPause pause = found.get();
        ExecutionKey key = pause.key();
        ExecutionRecorder recorder = ExecutionRecorder.open(executions, key, workerId, leaseTtl,
                executions.load(key).toCompletableFuture().join().revision());
        try {
            ProcessInstance stored = recorder.storedState();
            boolean lastLiveTraversal = stored.traversals().values().stream()
                    .filter(traversal -> !traversal.traversalId().equals(traversalId))
                    .allMatch(traversal -> traversal.status().terminal());
            // The traversal transition is skipped when it has already ended, which is the stale-hold
            // case: the aggregate refuses a second terminal transition, so asking for one would make
            // the settlement that clears the stale row impossible.
            Traversal heldTraversal = stored.traversals().get(traversalId);
            boolean traversalLive = heldTraversal != null && !heldTraversal.status().terminal();
            recorder.settleExecutionPause(
                    new ExecutionPauseTransition.Cancelled(pause.request().pauseId(), actor), traversalId,
                    traversalLive ? TraversalStatus.FAILED : null,
                    traversalLive && lastLiveTraversal && !stored.status().terminal()
                            ? ProcessInstanceStatus.FAILED : null);
            return true;
        } finally {
            recorder.close();
        }
    }

    private static NodeCommand commandOf(ExecutionPauseRegistration request) {
        return new NodeCommand(NodeDirective.valueOf(request.commandDirective()), request.commandName());
    }

    private Prepared prepare(DurableExecutionPause pause) {
        StoredGraphDefinition stored = definitions.load(new GraphDefinitionKey(pause.key().tenantId(),
                        new GraphContentId(pause.request().graphVersionPin().reference())))
                .toCompletableFuture().join();
        GraphManager manager = GraphManager.readGraphMl(new ByteArrayInputStream(stored.canonical().bytes()));
        try {
            GraphVersionSnapshot snapshot = GraphVersionSnapshot.create(
                    new GraphVersionKey(stored.identity().graphId(), stored.identity().versionId()),
                    manager.definition());
            // Fails here rather than after a lease and a runner exist, so a hold naming a node the
            // pinned graph does not contain is refused before anything has been claimed.
            manager.definition().node(pause.request().nodeId());
            return new Prepared(manager, snapshot);
        } catch (RuntimeException failure) {
            manager.close();
            throw failure;
        }
    }

    private record Prepared(GraphManager manager, GraphVersionSnapshot snapshot) {
    }
}
