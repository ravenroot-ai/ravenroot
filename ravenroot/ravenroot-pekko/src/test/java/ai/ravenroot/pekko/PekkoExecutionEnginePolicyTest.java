package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.testkit.ExecutionEnginePolicyContract;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PekkoExecutionEnginePolicyTest extends ExecutionEnginePolicyContract {
    private static final Duration SHORT_BOUND = Duration.ofMillis(75);
    private static final long CLEANUP_BOUND_SECONDS = 20;

    @Override
    protected ExecutionEngine createEngine(String systemName, ExecutionEnginePolicy policy) {
        return new PekkoExecutionEngineProvider().create(systemName, policy);
    }

    @Override
    protected void awaitEngineTermination(ExecutionEngine engine) throws Exception {
        awaitTerminated((PekkoExecutionEngine) engine);
    }

    @Test
    void conversionPreservesOneNanosecondAndSaturatesBeforeOverflow() {
        assertEquals(1, PekkoExecutionEngine.timeoutNanos(Duration.ofNanos(1)));
        assertEquals(Long.MAX_VALUE, PekkoExecutionEngine.timeoutNanos(Duration.ofNanos(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, PekkoExecutionEngine.timeoutNanos(
                Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)));
    }

    @Test
    void providerAndConstructorsApplyPolicyWithoutChangingLegacyHistoryCompatibility() {
        var provider = new PekkoExecutionEngineProvider();
        var policy = new ExecutionEnginePolicy(31, Duration.ofSeconds(2), 19);
        try (var configured = provider.create("pekko-provider-policy-" + UUID.randomUUID(), policy);
             var legacyHistory = new PekkoExecutionEngine("pekko-legacy-history-" + UUID.randomUUID(), 2_048)) {
            assertEquals(policy.compatibilityFingerprint(), configured.compatibilityFingerprint());
            assertEquals("", legacyHistory.compatibilityFingerprint());
        }
    }

    @Test
    void abandonedSpawnHandoffCleansOnlyAnAlreadySuccessfulValue() {
        var cleaned = new ArrayList<String>();
        var refusal = new IllegalStateException("refused");
        var successful = CompletableFuture.completedFuture("created");
        var pending = new CompletableFuture<String>();
        var alreadyFailed = CompletableFuture.<String>failedFuture(new IllegalArgumentException("failed"));

        PekkoExecutionEngine.abandonSpawn(successful, refusal, cleaned::add);
        PekkoExecutionEngine.abandonSpawn(pending, refusal, cleaned::add);
        PekkoExecutionEngine.abandonSpawn(alreadyFailed, refusal, cleaned::add);

        assertEquals(List.of("created"), cleaned);
        assertTrue(pending.isCompletedExceptionally());
        assertTrue(alreadyFailed.isCompletedExceptionally());
    }

    @Test
    void fingerprintAndManifestPinExecutionFieldsButExcludeHistory() {
        var first = new ExecutionEnginePolicy(31, Duration.ofSeconds(2), 2);
        var historyOnly = new ExecutionEnginePolicy(31, Duration.ofSeconds(2), 9);
        var changedStash = new ExecutionEnginePolicy(32, Duration.ofSeconds(2), 2);
        var changedLifecycle = new ExecutionEnginePolicy(31, Duration.ofSeconds(2, 1), 2);
        try (var legacy = new PekkoExecutionEngine("pekko-legacy-digest-" + UUID.randomUUID());
             var a = new PekkoExecutionEngine("pekko-policy-a-" + UUID.randomUUID(), first);
             var history = new PekkoExecutionEngine("pekko-policy-history-" + UUID.randomUUID(), historyOnly);
             var stash = new PekkoExecutionEngine("pekko-policy-stash-" + UUID.randomUUID(), changedStash);
             var lifecycle = new PekkoExecutionEngine(
                     "pekko-policy-lifecycle-" + UUID.randomUUID(), changedLifecycle)) {
            assertEquals("8a3d10e1ca385cefe21a8b84f98a79dd57d19de98c056dfc0612bc034435900d",
                    manifestEngineDigest(legacy));
            assertEquals(a.compatibilityFingerprint(), history.compatibilityFingerprint());
            assertEquals(manifestEngineDigest(a), manifestEngineDigest(history));
            assertNotEquals(manifestEngineDigest(a), manifestEngineDigest(stash));
            assertNotEquals(manifestEngineDigest(a), manifestEngineDigest(lifecycle));
        }
    }

    @Test
    void timedOutDomainSpawnBeforeCreationDoesNotLeaveAnUnregisteredActor() throws Exception {
        var spawnGate = new BlockingGate(true, false);
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var spawnEngine = engine(spawnGate);
        try (spawnEngine) {
            ExecutionDomain domain = spawnEngine.openDomain("spawn-reply");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread spawning = Thread.ofPlatform().name("pekko-domain-spawn").start(() -> {
                try {
                    domain.spawn("blocked", countingNode(starts, stops, new CountDownLatch(0), new CountDownLatch(0)));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            try {
                assertTrue(spawnGate.entered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, spawnGate.release.getCount());
                spawning.join(2_000);
                assertFalse(spawning.isAlive());
                assertInstanceOf(IllegalStateException.class, failure.get());
                assertEquals(0, starts.get());
                spawnGate.release.countDown();
                assertTrue(spawnGate.decision.await(2, TimeUnit.SECONDS));
                assertEquals(0, starts.get());
                assertEquals(0, stops.get());
                assertTrue(domain.nodes().isEmpty());
            } finally {
                spawnGate.release.countDown();
                spawning.join(2_000);
                if (spawning.isAlive()) spawning.interrupt();
                spawning.join(TimeUnit.SECONDS.toMillis(CLEANUP_BOUND_SECONDS));
                assertFalse(spawning.isAlive(), "domain spawn thread did not settle");
            }
        } finally {
            awaitTerminated(spawnEngine);
        }
    }

    @Test
    void callerRefusalCleansAChildWhoseSpawnReplyWonAtTheTimeoutBoundary() throws Exception {
        var spawnGate = new LostReplyRaceGate();
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var spawnEngine = engine(spawnGate);
        try (spawnEngine) {
            ExecutionDomain domain = spawnEngine.openDomain("late-spawn-reply");
            var sibling = domain.spawn("sibling", (message, context) ->
                    CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
            spawnGate.arm();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread spawning = Thread.ofPlatform().name("pekko-late-domain-spawn").start(() -> {
                try {
                    domain.spawn("blocked",
                            countingNode(starts, stops, new CountDownLatch(0), new CountDownLatch(0)));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            try {
                assertTrue(spawnGate.completionEntered.await(2, TimeUnit.SECONDS));
                var terminated = watch(spawnEngine, spawnGate.created.get());
                assertTrue(spawnGate.refusalEntered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, spawnGate.allowCompletion.getCount());
                spawnGate.allowRefusal.countDown();
                spawning.join(2_000);
                assertFalse(spawning.isAlive());
                assertInstanceOf(IllegalStateException.class, failure.get());
                terminated.get(2, TimeUnit.SECONDS);
                assertEquals(starts.get(), stops.get());
                assertTrue(starts.get() == 0 || starts.get() == 1);
                assertSiblingRemainsUsable(spawnEngine, domain, sibling);
            } finally {
                spawnGate.allowRefusal.countDown();
                spawnGate.allowCompletion.countDown();
                spawnGate.decision.countDown();
                spawning.join(2_000);
                if (spawning.isAlive()) spawning.interrupt();
                spawning.join(TimeUnit.SECONDS.toMillis(CLEANUP_BOUND_SECONDS));
                assertFalse(spawning.isAlive(), "late domain spawn thread did not settle");
            }
        } finally {
            awaitTerminated(spawnEngine);
        }
    }

    @Test
    void guardianStopsAChildWhenCallerRefusalWinsAfterCreation() throws Exception {
        var spawnGate = new FailureWinsRaceGate();
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var spawnEngine = engine(spawnGate);
        try (spawnEngine) {
            ExecutionDomain domain = spawnEngine.openDomain("failed-spawn-reply");
            var sibling = domain.spawn("sibling", (message, context) ->
                    CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
            spawnGate.arm();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread spawning = Thread.ofPlatform().name("pekko-failed-domain-spawn").start(() -> {
                try {
                    domain.spawn("blocked",
                            countingNode(starts, stops, new CountDownLatch(0), new CountDownLatch(0)));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            try {
                assertTrue(spawnGate.completionEntered.await(2, TimeUnit.SECONDS));
                var terminated = watch(spawnEngine, spawnGate.created.get());
                spawning.join(2_000);
                assertFalse(spawning.isAlive());
                assertInstanceOf(IllegalStateException.class, failure.get());
                assertEquals(1L, spawnGate.allowCompletion.getCount());
                spawnGate.allowCompletion.countDown();
                assertTrue(spawnGate.decision.await(2, TimeUnit.SECONDS));
                terminated.get(2, TimeUnit.SECONDS);
                assertEquals(starts.get(), stops.get());
                assertTrue(starts.get() == 0 || starts.get() == 1);
                assertSiblingRemainsUsable(spawnEngine, domain, sibling);
            } finally {
                spawnGate.allowCompletion.countDown();
                spawnGate.decision.countDown();
                spawning.join(2_000);
                if (spawning.isAlive()) spawning.interrupt();
                spawning.join(TimeUnit.SECONDS.toMillis(CLEANUP_BOUND_SECONDS));
                assertFalse(spawning.isAlive(), "failed domain spawn thread did not settle");
            }
        } finally {
            awaitTerminated(spawnEngine);
        }
    }

    @Test
    void configuredBoundAppliesToFinalDomainStopReply() throws Exception {
        var stopGate = new BlockingGate(false, true);
        var stopEngine = engine(stopGate);
        try (stopEngine) {
            ExecutionDomain domain = stopEngine.openDomain("stop-reply");
            try {
                var closing = domain.close().toCompletableFuture();
                assertTrue(stopGate.entered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, stopGate.release.getCount());
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> closing.get(2, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
            } finally {
                stopGate.release.countDown();
            }
        } finally {
            awaitTerminated(stopEngine);
        }
    }

    @Test
    void actorSystemTerminationWaitUsesTheConfiguredBound() throws Exception {
        var stopEntered = new CountDownLatch(1);
        var releaseStop = new CountDownLatch(1);
        var ready = new CountDownLatch(1);
        var engine = engine(PekkoExecutionEngine.DomainReplyGate.NONE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closing = null;
        try {
            engine.actorSystem().systemActorOf(
                    Behaviors.<Object>setup(context -> {
                        ready.countDown();
                        return Behaviors.receive(Object.class).onSignal(PostStop.class, signal -> {
                            stopEntered.countDown();
                            await(releaseStop);
                            return Behaviors.stopped();
                        }).build();
                    }), "blocked-post-stop-" + UUID.randomUUID(), Props.empty());
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            closing = Thread.ofPlatform().name("pekko-system-close").start(() -> {
                try {
                    engine.close();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1L, releaseStop.getCount());
            closing.join(2_000);
            assertFalse(closing.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
        } finally {
            releaseStop.countDown();
            try {
                if (closing == null) {
                    engine.close();
                } else {
                    closing.join(2_000);
                    if (closing.isAlive()) closing.interrupt();
                    closing.join(TimeUnit.SECONDS.toMillis(CLEANUP_BOUND_SECONDS));
                    assertFalse(closing.isAlive(), "actor-system close thread did not settle");
                }
            } finally {
                awaitTerminated(engine);
            }
        }
    }

    private static PekkoExecutionEngine engine(PekkoExecutionEngine.DomainReplyGate gate) {
        return new PekkoExecutionEngine("pekko-lifecycle-policy-" + UUID.randomUUID(),
                new ExecutionEnginePolicy(8, SHORT_BOUND, 8), gate);
    }

    private static RavenNode countingNode(AtomicInteger starts, AtomicInteger stops,
                                          CountDownLatch started, CountDownLatch stopped) {
        return new RavenNode() {
            @Override
            public void onStart(NodeContext context) {
                starts.incrementAndGet();
                started.countDown();
            }

            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()));
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
                stopped.countDown();
            }
        };
    }

    private static void awaitTerminated(PekkoExecutionEngine engine) throws Exception {
        engine.actorSystem().getWhenTerminated().toCompletableFuture()
                .get(CLEANUP_BOUND_SECONDS, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Void> watch(PekkoExecutionEngine engine, ActorRef<?> target)
            throws Exception {
        var ready = new CountDownLatch(1);
        var terminated = new CompletableFuture<Void>();
        engine.actorSystem().systemActorOf(watcher(target, ready, terminated),
                "policy-watch-" + UUID.randomUUID(), Props.empty());
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        return terminated;
    }

    private static void assertSiblingRemainsUsable(PekkoExecutionEngine engine,
                                                   ExecutionDomain domain,
                                                   ai.ravenroot.api.execution.NodeRef sibling)
            throws Exception {
        assertEquals("sibling", engine.send(sibling, message("sibling"))
                .toCompletableFuture().get(2, TimeUnit.SECONDS).payload());
        assertEquals(Set.of(sibling), domain.nodes());
    }

    private static Behavior<Void> watcher(ActorRef<?> target, CountDownLatch ready,
                                          CompletableFuture<Void> terminated) {
        return Behaviors.setup(context -> {
            context.watch(target);
            ready.countDown();
            return Behaviors.receiveSignal((ctx, signal) -> {
                if (signal instanceof Terminated) {
                    terminated.complete(null);
                    return Behaviors.stopped();
                }
                return Behaviors.same();
            });
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(CLEANUP_BOUND_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("test release was not signalled");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting test release", interruption);
        }
    }

    private static final class BlockingGate implements PekkoExecutionEngine.DomainReplyGate {
        private final boolean spawn;
        private final boolean stop;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch decision = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingGate(boolean spawn, boolean stop) {
            this.spawn = spawn;
            this.stop = stop;
        }

        @Override
        public void afterSpawnDecision() {
            decision.countDown();
        }

        @Override
        public void beforeSpawnReply() {
            if (spawn) block();
        }

        @Override
        public void beforeStopReply() {
            if (stop) block();
        }

        private void block() {
            entered.countDown();
            await(release);
        }
    }

    private static final class LostReplyRaceGate implements PekkoExecutionEngine.DomainReplyGate {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicReference<ActorRef<?>> created = new AtomicReference<>();
        private final CountDownLatch completionEntered = new CountDownLatch(1);
        private final CountDownLatch refusalEntered = new CountDownLatch(1);
        private final CountDownLatch allowRefusal = new CountDownLatch(1);
        private final CountDownLatch allowCompletion = new CountDownLatch(1);
        private final CountDownLatch decision = new CountDownLatch(1);

        private void arm() {
            armed.set(true);
        }

        @Override
        public void beforeSpawnReplyCompletion(ActorRef<?> actor) {
            if (!armed.get()) return;
            created.set(actor);
            completionEntered.countDown();
            await(allowCompletion);
        }

        @Override
        public void afterSpawnDecision() {
            if (armed.get()) decision.countDown();
        }

        @Override
        public void beforeSpawnRefusal() {
            if (!armed.get()) return;
            refusalEntered.countDown();
            await(allowRefusal);
            allowCompletion.countDown();
            await(decision);
        }
    }

    private static final class FailureWinsRaceGate implements PekkoExecutionEngine.DomainReplyGate {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicReference<ActorRef<?>> created = new AtomicReference<>();
        private final CountDownLatch completionEntered = new CountDownLatch(1);
        private final CountDownLatch allowCompletion = new CountDownLatch(1);
        private final CountDownLatch decision = new CountDownLatch(1);

        private void arm() {
            armed.set(true);
        }

        @Override
        public void beforeSpawnReplyCompletion(ActorRef<?> actor) {
            if (!armed.get()) return;
            created.set(actor);
            completionEntered.countDown();
            await(allowCompletion);
        }

        @Override
        public void afterSpawnDecision() {
            if (armed.get()) decision.countDown();
        }
    }
}
