package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The engine that freezes the dispatch chain exactly at one worker instance's handoff.
 *
 * <p>{@code GraphRunner.run()} releases a completing node's worker instance in {@code .handle(...)},
 * strictly before {@code .thenCompose(dispatchSuccessors)} reaches the successor's own
 * {@code workers.acquire()} -- and {@code WorkerInstanceRegistry.acquire()} does not register the new
 * instance in {@code live} (what {@code liveCount()} reads) until AFTER its call into the spawner has
 * returned. Blocking {@link #spawn} for one logical name therefore holds the runner open in exactly
 * the window between "the previous node's instance is gone" and "the next one exists": the real,
 * healthy node-to-node transient that stuck-traversal detection must not mistake for a failure.</p>
 *
 * <p>Delegates everything else to a real engine so the graph actually runs once released.</p>
 *
 * <h2>Call it from a thread you can afford to block</h2>
 * <p>{@link #spawn} runs synchronously on whichever thread reaches it, and which thread that is is
 * not guaranteed to be a pool thread. {@code JoinTestEngine.send()} submits to a fixed pool and
 * returns a stage that is not yet complete only if the pool has not already finished the node by the
 * time the caller attaches a continuation to it -- with an idle pool and trivial node work that race
 * is won by the pool often enough to matter, and every dependent stage then runs inline, recursively,
 * on the thread that called {@code execute()}. A caller that invokes {@code execute()} directly and
 * then tries to synchronize with this gate from the same thread can therefore deadlock against
 * itself: found by exactly that deadlock while writing this fixture, not reasoned out in advance.
 * Invoke {@code execute()} from a thread other than the one that will call {@link #awaitReached} and
 * {@link #releaseSpawn}.</p>
 */
final class SpawnGatingEngine implements ExecutionEngine {

    private final ExecutionEngine delegate;
    private final String gatedLogicalNamePrefix;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    SpawnGatingEngine(ExecutionEngine delegate, String gatedLogicalNamePrefix) {
        this.delegate = delegate;
        this.gatedLogicalNamePrefix = gatedLogicalNamePrefix;
    }

    /** Blocks until the gated {@link #spawn} call has been entered, so a test never races it. */
    boolean awaitReached(long millis) throws InterruptedException {
        return reached.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Lets the gated {@link #spawn} call return, so the traversal can finish normally. */
    void releaseSpawn() {
        release.countDown();
    }

    @Override
    public NodeRef spawn(String logicalName, RavenNode node) {
        if (logicalName.startsWith(gatedLogicalNamePrefix)) {
            reached.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("SpawnGatingEngine: releaseSpawn() was never called");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        return delegate.spawn(logicalName, node);
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public Set<EngineCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public Scheduler scheduler() {
        return delegate.scheduler();
    }

    @Override
    public EngineState state() {
        return delegate.state();
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        return delegate.send(target, message);
    }

    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        return delegate.status(target);
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
        return delegate.stop(target);
    }

    @Override
    public CompletionStage<Void> cancel(NodeRef target) {
        return delegate.cancel(target);
    }

    @Override
    public CompletionStage<Void> drain() {
        return delegate.drain();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
