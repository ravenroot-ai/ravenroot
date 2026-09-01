package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supervision state machine both adapters share (CORE-04).
 *
 * <p>Asserted here rather than only through the adapters because this is where the semantics live:
 * a conformance failure on one engine would otherwise leave open whether the rule or the actor
 * plumbing was wrong.</p>
 */
class NodeLifecycleTest {
    private static final NodeRef NODE = new NodeRef("node-under-test");

    @Test
    void encodesTheTransitionTable() {
        assertTrue(NodeLifecycleState.RUNNING.canTransitionTo(NodeLifecycleState.DRAINING));
        assertTrue(NodeLifecycleState.RUNNING.canTransitionTo(NodeLifecycleState.CANCELLING));
        assertFalse(NodeLifecycleState.RUNNING.canTransitionTo(NodeLifecycleState.TERMINATED));
        assertTrue(NodeLifecycleState.DRAINING.canTransitionTo(NodeLifecycleState.CANCELLING));
        assertTrue(NodeLifecycleState.DRAINING.canTransitionTo(NodeLifecycleState.TERMINATED));
        assertFalse(NodeLifecycleState.DRAINING.canTransitionTo(NodeLifecycleState.RUNNING),
                "a drain is not reversible: callers already refused would have to be un-refused");
        assertFalse(NodeLifecycleState.CANCELLING.canTransitionTo(NodeLifecycleState.DRAINING));
        for (NodeLifecycleState next : NodeLifecycleState.values()) {
            assertFalse(NodeLifecycleState.TERMINATED.canTransitionTo(next), "termination is absorbing");
        }
    }

    @Test
    void admitsWorkOnlyWhileRunning() {
        var lifecycle = new NodeLifecycle(NODE);
        var enqueued = new AtomicInteger();

        assertTrue(lifecycle.accept(new CompletableFuture<>(), enqueued::incrementAndGet));
        assertEquals(1, lifecycle.acceptedMessages());

        assertTrue(lifecycle.beginDrain());
        assertFalse(lifecycle.accept(new CompletableFuture<>(), enqueued::incrementAndGet));
        assertEquals(1, enqueued.get(), "a refused message must never be handed to the runtime");
        assertEquals(NodeLifecycleState.DRAINING, lifecycle.refusal().state());
    }

    @Test
    void unregistersAMessageWhoseEnqueueFailed() {
        var lifecycle = new NodeLifecycle(NODE);

        assertThrows(IllegalStateException.class, () -> lifecycle.accept(new CompletableFuture<>(), () -> {
            throw new IllegalStateException("runtime refused the enqueue");
        }));

        // Otherwise the node could never terminate: it would be waiting to settle a message that was
        // never delivered to anything.
        assertEquals(0, lifecycle.acceptedMessages());
    }

    @Test
    void settlesEachAcceptedMessageExactlyOnce() {
        var lifecycle = new NodeLifecycle(NODE);
        var reply = new CompletableFuture<NodeResult>();
        lifecycle.accept(reply, () -> {
        });

        assertTrue(lifecycle.settle(reply, NodeResult.continueWith("first"), null));
        assertFalse(lifecycle.settle(reply, NodeResult.continueWith("second"), null));
        assertFalse(lifecycle.settle(reply, null, new IllegalStateException("late failure")));

        assertEquals("first", reply.join().payload());
        assertEquals(0, lifecycle.acceptedMessages());
    }

    @Test
    void cancellationAbandonsOutstandingWorkAndFiresTheSignalOnce() throws Exception {
        var lifecycle = new NodeLifecycle(NODE);
        var first = new CompletableFuture<NodeResult>();
        var second = new CompletableFuture<NodeResult>();
        lifecycle.accept(first, () -> {
        });
        lifecycle.accept(second, () -> {
        });
        var fired = new AtomicInteger();
        lifecycle.cancellation().onCancel(fired::incrementAndGet);
        assertFalse(lifecycle.cancellation().cancelled());

        assertTrue(lifecycle.beginCancel());
        assertFalse(lifecycle.beginCancel(), "cancelling twice must not fire anything twice");

        assertTrue(lifecycle.cancellation().cancelled());
        assertEquals(1, fired.get());
        assertEquals(0, lifecycle.acceptedMessages());
        for (CompletableFuture<NodeResult> reply : List.of(first, second)) {
            var failure = assertThrows(ExecutionException.class, () -> reply.get(1, TimeUnit.SECONDS));
            assertInstanceOf(NodeCancelledException.class, failure.getCause());
        }

        var late = new AtomicInteger();
        lifecycle.cancellation().onCancel(late::incrementAndGet);
        assertEquals(1, late.get(), "a listener registered after the fact still has to learn about it");
    }

    @Test
    void doesNotLetAFailingListenerBreakTheCancellation() {
        var lifecycle = new NodeLifecycle(NODE);
        var survivor = new AtomicInteger();
        lifecycle.cancellation().onCancel(() -> {
            throw new IllegalStateException("a node's callback is not allowed to veto its cancellation");
        });
        lifecycle.cancellation().onCancel(survivor::incrementAndGet);

        assertTrue(lifecycle.beginCancel());

        assertEquals(1, survivor.get());
        assertTrue(lifecycle.terminate());
    }

    @Test
    void derivesTheTerminationReasonFromTheDecisionRatherThanTheLastCommand() {
        var drainedThenCancelled = new NodeLifecycle(NODE);
        assertTrue(drainedThenCancelled.beginDrain());
        assertTrue(drainedThenCancelled.beginCancel());
        assertTrue(drainedThenCancelled.terminate());
        // A stop command that happens to be the one that completes the node must not report a clean
        // drain when a cancellation has already abandoned work.
        assertEquals(NodeTerminationReason.CANCELLED, drainedThenCancelled.terminationReason());

        var drained = new NodeLifecycle(NODE);
        assertTrue(drained.beginDrain());
        assertTrue(drained.terminate());
        assertEquals(NodeTerminationReason.STOPPED, drained.terminationReason());
        assertFalse(drained.terminate());
        assertFalse(drained.beginCancel(), "a terminated node cannot be cancelled back into motion");
    }

    @Test
    void refusesToTerminateWithoutDrainingOrCancellingFirst() {
        var lifecycle = new NodeLifecycle(NODE);

        assertThrows(IllegalStateException.class, lifecycle::terminate);
        assertEquals(NodeLifecycleState.RUNNING, lifecycle.state());
    }

    @Test
    void reportsAFailingOnStopToWhoeverAskedForTheTermination() {
        var lifecycle = new NodeLifecycle(NODE);
        lifecycle.beginDrain();

        assertTrue(lifecycle.terminate(new IllegalStateException("cleanup failed")));

        assertEquals(NodeLifecycleState.TERMINATED, lifecycle.state());
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> lifecycle.terminated().toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void keepsStatusInternallyConsistent() {
        var lifecycle = new NodeLifecycle(NODE);
        var running = lifecycle.status();
        assertEquals(NodeLifecycleState.RUNNING, running.state());
        assertEquals(null, running.reason());
        assertTrue(running.accepting());

        lifecycle.beginDrain();
        lifecycle.terminate();

        var terminal = lifecycle.status();
        assertEquals(NodeTerminationReason.STOPPED, terminal.reason());
        assertFalse(terminal.accepting());
        assertThrows(IllegalArgumentException.class,
                () -> new NodeStatus(NODE, NodeLifecycleState.TERMINATED, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NodeStatus(NODE, NodeLifecycleState.RUNNING, NodeTerminationReason.STOPPED, 0));
    }

    @Test
    void neverAdmitsAMessageAfterTheTransitionThatRefusesIt() throws Exception {
        int rounds = 500;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                var lifecycle = new NodeLifecycle(NODE);
                var enqueuedAfterTransition = new ArrayList<CompletableFuture<NodeResult>>();
                var barrier = new CyclicBarrier(2);
                var done = new CountDownLatch(2);

                var reply = new CompletableFuture<NodeResult>();
                pool.execute(() -> {
                    try {
                        barrier.await();
                        // The enqueue records the state the runtime would have seen; a message handed
                        // over after the node stopped accepting is a message nobody will ever settle.
                        lifecycle.accept(reply, () -> {
                            if (!lifecycle.state().accepting()) {
                                enqueuedAfterTransition.add(reply);
                            }
                        });
                    } catch (Exception ignored) {
                        // Reported through the assertions below.
                    } finally {
                        done.countDown();
                    }
                });
                pool.execute(() -> {
                    try {
                        barrier.await();
                        lifecycle.beginDrain();
                    } catch (Exception ignored) {
                        // Reported through the assertions below.
                    } finally {
                        done.countDown();
                    }
                });

                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertEquals(List.of(), enqueuedAfterTransition,
                        "admission and enqueue must be one operation, not a check followed by a hand-off");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The same rule as above, pinned deterministically instead of sampled.
     *
     * <p>A race test finds this defect only when the scheduler happens to deschedule a sender inside
     * a window a few instructions wide. Measured against an {@code accept} whose admission test and
     * hand-off were deliberately split, the adapters' 3-sender conformance race reported it in 3 runs
     * out of 8. Here the window is held open by a latch instead of hoped for, so the same defect is
     * reported in 8 runs out of 8, and the assertion states the rule rather than a symptom of it: a
     * transition must not be able to complete while a message it would have refused is already on its
     * way to the runtime.</p>
     */
    @Test
    void holdsEveryTransitionOffUntilAnAdmittedMessageHasReachedTheRuntime() throws Exception {
        var lifecycle = new NodeLifecycle(NODE);
        var enqueueStarted = new CountDownLatch(1);
        var releaseEnqueue = new CountDownLatch(1);
        var stateSeenByTheRuntime = new AtomicReference<NodeLifecycleState>();
        var reply = new CompletableFuture<NodeResult>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admitted = pool.submit((Callable<Boolean>) () ->
                    lifecycle.accept(reply, () -> {
                        enqueueStarted.countDown();
                        awaitQuietly(releaseEnqueue);
                        stateSeenByTheRuntime.set(lifecycle.state());
                    }));
            assertTrue(enqueueStarted.await(5, TimeUnit.SECONDS));

            Future<Boolean> drained = pool.submit((Callable<Boolean>) lifecycle::beginDrain);

            assertThrows(TimeoutException.class, () -> drained.get(500, TimeUnit.MILLISECONDS),
                    "a drain must not be able to overtake a message that is already being enqueued");

            releaseEnqueue.countDown();
            assertTrue(admitted.get(5, TimeUnit.SECONDS));
            assertTrue(drained.get(5, TimeUnit.SECONDS));
            // The runtime is what would have dropped the message. It must never be handed one while
            // the node has already stopped accepting.
            assertEquals(NodeLifecycleState.RUNNING, stateSeenByTheRuntime.get());
            assertEquals(1, lifecycle.acceptedMessages(),
                    "the admitted message is still owed an answer and the drain still owes it");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Where the line between "this message failed" and "this JVM failed" is drawn.
     *
     * <p>Both adapters catch {@code Error}, which is what stops an {@code AssertionError} from killing
     * an actor and stranding the caller's reply. Applied without a line, that same catch would also
     * swallow an {@code OutOfMemoryError} and report it as one failed message, leaving the process
     * running on a heap it has already lost and taking the runtime's fatal-error policy away from the
     * operator who configured it. The line is the JVM's own type, not a curated list.</p>
     */
    @Test
    void separatesAFailureANodeCanResumeFromOneTheVirtualMachineDeclaresUnrecoverable() {
        List<Throwable> resumable = List.of(
                new AssertionError("an assertion inside a node"),
                new LinkageError("a library compiled against a different version"),
                new IllegalStateException("an ordinary failure"),
                new NoSuchMethodError("a missing method"));
        resumable.forEach(error -> assertSame(error, NodeLifecycle.resumableOrRethrow(error),
                error.getClass().getSimpleName() + " is a node failure, not a broken virtual machine"));

        var heap = new OutOfMemoryError("simulated");
        assertSame(heap, assertThrows(OutOfMemoryError.class, () -> NodeLifecycle.resumableOrRethrow(heap)));
        // A stack overflow is often a plain user-code bug, and it is still not exempted: drawing the
        // line anywhere other than VirtualMachineError would mean deciding case by case which
        // failures an operator is allowed to have a policy for.
        assertThrows(StackOverflowError.class,
                () -> NodeLifecycle.resumableOrRethrow(new StackOverflowError("simulated")));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
