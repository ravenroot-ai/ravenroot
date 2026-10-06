package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledSourceLifecycleTest {
    @Test void lateSkipAdvancesCursorWithoutStartingTraversal() throws Exception {
        Instant baseline = Instant.parse("2030-01-01T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(baseline);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var node = new GraphNode("timer", NodeKind.BEHAVIOR, "timer", Map.of(
                "zoneId", "UTC", "times", "09:00:01", "mode", "RECURRING", "misfirePolicy", "SKIP"));
        var durable = new RecordingDurableIngress();
        var context = new TestContext(durable);
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var source = new TimerNodeBehaviorFactory(clock, scheduler).createSource(node, context);
            source.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            now.set(baseline.plus(Duration.ofHours(1)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (durable.cursors.getOrDefault("occurrences", 0L) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(durable.cursors.getOrDefault("occurrences", 0L) > 0,
                    "a skipped occurrence must be checkpointed");
            assertEquals(0, durable.offers.get());
            source.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void timelySkipPolicyFiresOnceAndRestartDoesNotReplay() throws Exception {
        Instant baseline = Instant.parse("2030-01-01T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(baseline);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var node = new GraphNode("timer", NodeKind.BEHAVIOR, "timer", Map.of(
                "zoneId", "UTC", "times", "09:00:01", "mode", "RECURRING", "misfirePolicy", "SKIP"));
        var durable = new RecordingDurableIngress();
        var context = new TestContext(durable);
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var factory = new TimerNodeBehaviorFactory(clock, scheduler);
            var source = factory.createSource(node, context);
            source.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            now.set(baseline.plusMillis(1_100));
            assertTrue(durable.fired.await(3, TimeUnit.SECONDS), "normal wake-up jitter must not be skipped");
            assertEquals(1, durable.offers.get());
            assertEquals(false, durable.payload.get("misfire"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (durable.cursors.getOrDefault("occurrences", 0L) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(durable.cursors.getOrDefault("occurrences", 0L) > 0);
            source.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);

            var restarted = factory.createSource(node, context);
            restarted.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            Thread.sleep(100);
            assertEquals(1, durable.offers.get(), "restart must resume the saved occurrence cursor");
            restarted.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void acceptedUnstartedOccurrenceSurvivesReceiptCrashAndLateSkipRestart() throws Exception {
        Instant baseline = Instant.parse("2030-01-01T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(baseline);
        Clock clock = adjustableClock(now);
        var node = new GraphNode("timer", NodeKind.BEHAVIOR, "timer", Map.of(
                "zoneId", "UTC", "times", "09:00:01", "mode", "RECURRING", "misfirePolicy", "SKIP"));
        var durable = new RecordingDurableIngress();
        durable.startImmediately.set(false);
        var context = new TestContext(durable);
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var factory = new TimerNodeBehaviorFactory(clock, scheduler);
            var first = factory.createSource(node, context);
            first.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            now.set(baseline.plusMillis(1_100));
            assertTrue(durable.fired.await(3, TimeUnit.SECONDS), "the first offer must return a durable receipt");
            assertEquals(0L, durable.cursors.getOrDefault("occurrences", 0L),
                    "a receipt with no InvocationAdded must not advance the occurrence cursor");
            first.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);

            // The next day's tick has also passed. A naive SKIP or latest selection would lose the
            // earlier accepted execution; the restarted source must still reconcile its exact key.
            now.set(baseline.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
            var restarted = factory.createSource(node, context);
            restarted.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            awaitAtLeast(durable.deliveries, 2);
            assertEquals(1, durable.offers.get(), "redelivery must not create a second traversal");
            assertEquals(0L, durable.cursors.getOrDefault("occurrences", 0L),
                    "even a Duplicate receipt cannot prove that the first invocation is durable");
            durable.markStarted();
            awaitCursor(durable);
            assertEquals(1, durable.offers.get(), "one occurrence may start only once after recovery");
            restarted.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void latestOnlyPinsSelectedOccurrenceBeforeItsFirstOffer() throws Exception {
        Instant baseline = Instant.parse("2030-01-01T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(baseline);
        var node = new GraphNode("timer", NodeKind.BEHAVIOR, "timer", Map.of(
                "zoneId", "UTC", "times", "09:00:01", "mode", "RECURRING", "misfirePolicy", "LATEST_ONLY"));
        var durable = new RecordingDurableIngress();
        durable.startImmediately.set(false);
        var context = new TestContext(durable);
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var factory = new TimerNodeBehaviorFactory(adjustableClock(now), scheduler);
            var first = factory.createSource(node, context);
            first.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            now.set(baseline.plus(Duration.ofDays(2)).plusSeconds(2));
            assertTrue(durable.fired.await(3, TimeUnit.SECONDS));
            assertEquals(baseline.plus(Duration.ofDays(1)).plusSeconds(1).getEpochSecond() + 1,
                    durable.cursors.getOrDefault("occurrences", 0L),
                    "the unstarted latest occurrence must be immediately after the saved cursor");
            first.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);

            now.set(baseline.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
            var restarted = factory.createSource(node, context);
            restarted.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            awaitAtLeast(durable.deliveries, 2);
            assertEquals(1, durable.offers.get(), "restart must reoffer the pinned latest occurrence");
            assertEquals(baseline.plus(Duration.ofDays(1)).plusSeconds(1).getEpochSecond() + 1,
                    durable.cursors.getOrDefault("occurrences", 0L));
            restarted.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void inboxOnlyCrashOverridesSkipWithoutInventingAnUnacceptedMisfire() throws Exception {
        Instant baseline = Instant.parse("2030-01-01T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(baseline);
        var node = new GraphNode("timer", NodeKind.BEHAVIOR, "timer", Map.of(
                "zoneId", "UTC", "times", "09:00:01", "mode", "RECURRING", "misfirePolicy", "SKIP"));
        var durable = new RecordingDurableIngress();
        durable.inboxOnlyForUnknown.set(true);
        var context = new TestContext(durable);
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var source = new TimerNodeBehaviorFactory(adjustableClock(now), scheduler).createSource(node, context);
            source.start(context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            now.set(baseline.plus(Duration.ofHours(1)));
            assertTrue(durable.fired.await(3, TimeUnit.SECONDS),
                    "a committed inbox row must be repaired despite the late SKIP policy");
            awaitCursor(durable);
            assertEquals(1, durable.offers.get());
            source.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static Clock adjustableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    private static void awaitAtLeast(AtomicInteger value, int minimum) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (value.get() < minimum && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(value.get() >= minimum);
    }

    private static void awaitCursor(RecordingDurableIngress durable) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (durable.cursors.getOrDefault("occurrences", 0L) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(durable.cursors.getOrDefault("occurrences", 0L) > 0);
    }

    private static final class TestContext implements InboundSourceContext {
        private final RecordingDurableIngress durable;
        private final SecurityContext identity = new SecurityContext("schedule-test", "tenant", "scheduler",
                PrincipalType.WORKLOAD, "test");
        TestContext(RecordingDurableIngress durable) { this.durable = durable; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("scheduled-test"); }
        @Override public String nodeId() { return "timer"; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return new TrustedIngress() {
            @Override public DurableConsumerIngress openDurableConsumer(String consumerId) { return durable; }
            @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
                return IngressDisposition.REJECTED_ADMISSION_CLOSED;
            }
            @Override public int bufferCapacity() { return 1; }
            @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
        }; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }

    private static final class RecordingDurableIngress implements DurableConsumerIngress {
        private final ConcurrentHashMap<String, Long> cursors = new ConcurrentHashMap<>();
        private final java.util.Set<String> acceptedKeys = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> startedKeys = ConcurrentHashMap.newKeySet();
        private final CountDownLatch fired = new CountDownLatch(1);
        private final AtomicInteger offers = new AtomicInteger();
        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicBoolean startImmediately = new AtomicBoolean(true);
        private final AtomicBoolean inboxOnlyForUnknown = new AtomicBoolean();
        private volatile String lastKey;
        private volatile Map<?, ?> payload;
        @Override public CompletionStage<JournalCursor> sourceCheckpoint(SecurityContext security, String sourceId) {
            return CompletableFuture.completedFuture(new JournalCursor(security.tenantId(), sourceId,
                    cursors.getOrDefault(sourceId, 0L)));
        }
        @Override public synchronized CompletionStage<JournalCursor> advanceSourceCheckpoint(
                JournalCursor expected, long position) {
            assertEquals(expected.deliveredThrough(), cursors.getOrDefault(expected.destination(), 0L));
            cursors.put(expected.destination(), position);
            return CompletableFuture.completedFuture(new JournalCursor(expected.tenantId(), expected.destination(), position));
        }
        @Override public IngressReceipt offerDurably(SecurityContext security, IngressTarget target, Object value,
                                                      String sourceId, String key) {
            deliveries.incrementAndGet();
            if (!acceptedKeys.add(key)) return new IngressReceipt.Duplicate(key);
            lastKey = key;
            payload = (Map<?, ?>) value;
            offers.incrementAndGet();
            if (startImmediately.get()) startedKeys.add(key);
            fired.countDown();
            return new IngressReceipt.DurablyCommitted(key);
        }
        @Override public CompletionStage<DurableIngressStartState> startState(
                SecurityContext security, String sourceId, String key) {
            return CompletableFuture.completedFuture(startedKeys.contains(key) ? DurableIngressStartState.STARTED
                    : acceptedKeys.contains(key) ? DurableIngressStartState.ACCEPTED_UNSTARTED
                    : inboxOnlyForUnknown.get() ? DurableIngressStartState.INBOX_ONLY
                    : DurableIngressStartState.ABSENT);
        }
        void markStarted() { startedKeys.add(lastKey); }
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            return IngressDisposition.REJECTED_ADMISSION_CLOSED;
        }
        @Override public int bufferCapacity() { return 1; }
        @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
        @Override public void close() { }
    }
}
