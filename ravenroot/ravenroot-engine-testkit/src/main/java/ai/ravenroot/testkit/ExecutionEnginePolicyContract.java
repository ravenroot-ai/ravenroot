package ai.ravenroot.testkit;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.NodeTerminationReason;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.manifest.ExecutionManifestResolver;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared behavioral proof that an adapter applies every non-lifecycle engine policy component. */
public abstract class ExecutionEnginePolicyContract {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "engine-policy-test", "tenant-a", "tester", PrincipalType.WORKLOAD, "urn:ravenroot:test");
    private static final Duration LIFECYCLE_BOUND = Duration.ofSeconds(2);
    private static final long CLEANUP_BOUND_SECONDS = 20;

    protected abstract ExecutionEngine createEngine(String systemName, ExecutionEnginePolicy policy);

    /** Waits until the adapter runtime has actually terminated after an asynchronous close path. */
    protected abstract void awaitEngineTermination(ExecutionEngine engine) throws Exception;

    /** Resolves the actual adapter identity and policy fingerprint through production manifest code. */
    protected final String manifestEngineDigest(ExecutionEngine engine) {
        GraphContentId content = GraphContentId.of("engine-policy".getBytes(StandardCharsets.UTF_8));
        return ExecutionManifestResolver.from(engine, java.util.Set.of(),
                        BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                        UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null)
                .manifestFor(new ExecutionKey("tenant-a", UUID.randomUUID()), content,
                        GraphDefinitionIdentity.forSubmission(content), ExecutionPolicy.STANDARD, Instant.EPOCH)
                .runtime().engineDigest();
    }

    @Test
    final void configuredStashCapacityAcceptsExactlyThatManyWaitingCommandsInFifoOrder() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var firstResult = new CompletableFuture<NodeResult>();
        var invocations = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try (var engine = createEngine("policy-stash-capacity-" + UUID.randomUUID(), policy(2, 8))) {
            try {
                var ref = engine.spawn("ordered", (message, context) -> {
                    int invocation = invocations.incrementAndGet();
                    order.add(message.payload().toString());
                    if (invocation == 1) {
                        firstStarted.countDown();
                        return firstResult;
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
                CompletionStage<NodeResult> first = engine.send(ref, message("first"));
                assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
                CompletionStage<NodeResult> second = engine.send(ref, message("second"));
                CompletionStage<NodeResult> third = engine.send(ref, message("third"));
                firstResult.complete(NodeResult.continueWith("first"));
                assertEquals("first", first.toCompletableFuture().get(2, TimeUnit.SECONDS).payload());
                assertEquals("second", second.toCompletableFuture().get(2, TimeUnit.SECONDS).payload());
                assertEquals("third", third.toCompletableFuture().get(2, TimeUnit.SECONDS).payload());
                assertEquals(List.of("first", "second", "third"), order);
            } finally {
                firstResult.complete(NodeResult.continueWith("released"));
            }
        }
    }

    @Test
    final void overflowingConfiguredStashSettlesEveryAcceptedReply() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var firstResult = new CompletableFuture<NodeResult>();
        try (var engine = createEngine("policy-stash-overflow-" + UUID.randomUUID(), policy(2, 8))) {
            try {
                var ref = engine.spawn("overflow", (message, context) -> {
                    firstStarted.countDown();
                    return firstResult;
                });
                List<CompletionStage<NodeResult>> replies = new ArrayList<>();
                replies.add(engine.send(ref, message("first")));
                assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
                replies.add(engine.send(ref, message("second")));
                replies.add(engine.send(ref, message("third")));
                replies.add(engine.send(ref, message("overflow")));
                CompletableFuture.allOf(replies.stream()
                                .map(reply -> reply.handle((value, error) -> null).toCompletableFuture())
                                .toArray(CompletableFuture[]::new))
                        .get(2, TimeUnit.SECONDS);
                assertTrue(replies.stream().allMatch(reply -> reply.toCompletableFuture().isDone()));
                NodeStatus terminal = awaitTerminalStatus(engine, ref);
                assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
                assertEquals(NodeTerminationReason.CANCELLED, terminal.reason());
            } finally {
                firstResult.complete(NodeResult.continueWith("released"));
            }
        }
    }

    @Test
    final void configuredTerminalHistoryRetainsOnlyTheNewestEntries() throws Exception {
        try (var engine = createEngine("policy-history-" + UUID.randomUUID(), policy(8, 2))) {
            var refs = new ArrayList<ai.ravenroot.api.execution.NodeRef>();
            for (int index = 0; index < 3; index++) {
                var ref = engine.spawn("history-" + index,
                        (message, context) -> CompletableFuture.completedFuture(
                                NodeResult.continueWith(message.payload())));
                refs.add(ref);
                engine.stop(ref).toCompletableFuture().get(2, TimeUnit.SECONDS);
            }

            assertFalse(engine.status(refs.get(0)).isPresent());
            assertEquals(NodeLifecycleState.TERMINATED, engine.status(refs.get(1)).orElseThrow().state());
            assertEquals(NodeLifecycleState.TERMINATED, engine.status(refs.get(2)).orElseThrow().state());
        }
    }

    @Test
    final void domainStopWaitEscalatesBlockedWorkAtTheConfiguredBound() throws Exception {
        var result = new CompletableFuture<NodeResult>();
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        try (var engine = createEngine("policy-domain-stop-" + UUID.randomUUID(), shortPolicy())) {
            try {
                var domain = engine.openDomain("bounded-stop");
                var ref = domain.spawn("blocked", (message, context) -> {
                    context.cancellation().onCancel(cancelled::countDown);
                    started.countDown();
                    return result;
                });
                engine.send(ref, message("blocked"));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                CompletionStage<Void> closing = domain.close();
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
                closing.toCompletableFuture().get(2, TimeUnit.SECONDS);
            } finally {
                result.complete(NodeResult.continueWith("released"));
            }
        }
    }

    @Test
    final void domainCancelWaitDoesNotHoldTheGuardianStepPastTheConfiguredBound() throws Exception {
        var result = new CompletableFuture<NodeResult>();
        var started = new CountDownLatch(1);
        var stopEntered = new CountDownLatch(1);
        var releaseStop = new CountDownLatch(1);
        try (var engine = createEngine("policy-domain-cancel-" + UUID.randomUUID(), shortPolicy())) {
            try {
                var domain = engine.openDomain("bounded-cancel");
                var ref = domain.spawn("blocked", blockingNode(result, started, stopEntered, releaseStop));
                engine.send(ref, message("blocked"));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                CompletionStage<Void> closing = domain.close();
                assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, releaseStop.getCount());
                closing.toCompletableFuture().get(2, TimeUnit.SECONDS);
            } finally {
                releaseStop.countDown();
                result.complete(NodeResult.continueWith("released"));
            }
        }
    }

    @Test
    final void engineDrainAndCancelWaitsAdvanceThroughTheConfiguredBound() throws Exception {
        var result = new CompletableFuture<NodeResult>();
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var stopEntered = new CountDownLatch(1);
        var releaseStop = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        var engine = createEngine("policy-engine-close-" + UUID.randomUUID(), shortPolicy());
        try (engine) {
            Thread closing = null;
            try {
                var ref = engine.spawn("blocked", new RavenNode() {
                    @Override
                    public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                        context.cancellation().onCancel(cancelled::countDown);
                        started.countDown();
                        return result;
                    }

                    @Override
                    public void onStop(NodeContext context) {
                        stopEntered.countDown();
                        await(releaseStop);
                    }
                });
                engine.send(ref, message("blocked"));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                closing = Thread.ofPlatform().name("engine-policy-close").start(() -> {
                    try {
                        engine.close();
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                });
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
                assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, releaseStop.getCount());
                awaitState(engine, EngineState.CLOSED);
            } finally {
                releaseStop.countDown();
                result.complete(NodeResult.continueWith("released"));
                if (closing != null) {
                    closing.join(2_000);
                    if (closing.isAlive()) closing.interrupt();
                    closing.join(TimeUnit.SECONDS.toMillis(CLEANUP_BOUND_SECONDS));
                    assertFalse(closing.isAlive(), "engine close thread did not settle");
                }
            }
        } finally {
            awaitEngineTermination(engine);
        }
        Throwable failure = closeFailure.get();
        if (failure != null) {
            assertTrue(failure instanceof IllegalStateException, failure.toString());
        }
    }

    @Test
    final void closeRestoresTheCallingThreadsInterruptStatus() throws Exception {
        var result = new CompletableFuture<NodeResult>();
        var started = new CountDownLatch(1);
        var engine = createEngine("policy-interrupt-" + UUID.randomUUID(), shortPolicy());
        boolean closeAttempted = false;
        try (engine) {
            try {
                var ref = engine.spawn("interrupt", (message, context) -> {
                    started.countDown();
                    return result;
                });
                engine.send(ref, message("blocked"));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                Thread.currentThread().interrupt();
                closeAttempted = true;
                try {
                    engine.close();
                } catch (IllegalStateException allowedTerminationTimeout) {
                    // A short policy may expire while the actor runtime is still terminating.
                }
            } finally {
                boolean interruptRestored = Thread.currentThread().isInterrupted();
                Thread.interrupted();
                result.complete(NodeResult.continueWith("released"));
                if (closeAttempted) {
                    assertTrue(interruptRestored, "engine close did not restore the interrupt flag");
                }
            }
        } finally {
            awaitEngineTermination(engine);
        }
    }

    private static ExecutionEnginePolicy policy(int stashCapacity, int historyCapacity) {
        return new ExecutionEnginePolicy(stashCapacity, LIFECYCLE_BOUND, historyCapacity);
    }

    private static ExecutionEnginePolicy shortPolicy() {
        return new ExecutionEnginePolicy(8, Duration.ofMillis(75), 8);
    }

    private static RavenNode blockingNode(CompletableFuture<NodeResult> result, CountDownLatch started,
                                          CountDownLatch stopEntered, CountDownLatch releaseStop) {
        return new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                started.countDown();
                return result;
            }

            @Override
            public void onStop(NodeContext context) {
                stopEntered.countDown();
                await(releaseStop);
            }
        };
    }

    private static void awaitState(ExecutionEngine engine, EngineState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (engine.state() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, engine.state());
    }

    private static NodeStatus awaitTerminalStatus(ExecutionEngine engine, NodeRef ref)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        NodeStatus status = engine.status(ref).orElse(null);
        while ((status == null || !status.state().terminal()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
            status = engine.status(ref).orElse(null);
        }
        assertTrue(status != null && status.state().terminal(), "node did not reach terminal status");
        return status;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(CLEANUP_BOUND_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting test release", interruption);
        }
    }

    protected static NodeMessage message(String payload) {
        return new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "policy-node", payload, Map.of());
    }
}
