package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.persistence.JoinFailureReason;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.api.persistence.JoinStoreException;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deadlines remain effective while the first report crosses the asynchronous join-store boundary. */
class JoinFirstWriteDeadlineTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void timeoutCreatesTheTerminalRecordWhenTheFirstReportWriteHasNotStarted() throws Exception {
        var clock = new MutableClock(EPOCH);
        var store = new ControlledJoinStore(FirstWrite.HOLD_BEFORE_SUCCESS, TimeoutLoad.DELEGATE,
                TimeoutWrite.DELEGATE);
        try (var subject = subject(store, clock)) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWriteEntered();

            assertEquals(1, subject.coordinator.liveTimeoutCount());
            assertEquals(Optional.empty(), store.stored(subject.key).toCompletableFuture()
                    .get(BOUND.toMillis(), TimeUnit.MILLISECONDS));
            clock.advance(Duration.ofSeconds(7));

            assertEquals(1, subject.engine.manualScheduler().fireAll());
            JoinRecord timedOut = store.record(subject.key);
            assertEquals(1, timedOut.revision());
            assertEquals(JoinPhase.FAILED, timedOut.phase());
            assertEquals(JoinFailureReason.TIMEOUT, timedOut.failureReason());
            assertEquals(EPOCH, timedOut.openedAt(),
                    "the durable timeout must retain the first report's opening instant");

            store.releaseFirstWrite();
            JoinFailureException failure = failedDecision(report);
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
            assertEquals(List.of("b0", "b1"), failure.outstanding());
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.timeoutRelinquished.get());
            assertEquals(Duration.ofSeconds(7), onlyJoinFailureEvent(subject.monitor).joinWaitDuration());

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void persistedTimeoutIsRetainedUntilTheFirstReportCompletionHandsOffToItsWaiter() throws Exception {
        var store = new ControlledJoinStore(FirstWrite.HOLD_AFTER_SUCCESS, TimeoutLoad.DELEGATE,
                TimeoutWrite.DELEGATE);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWritePersisted();

            assertFalse(report.isDone(), "the durable OPEN write completed but its caller is still held");
            assertEquals(JoinPhase.OPEN, store.record(subject.key).phase());
            assertEquals(1, subject.engine.manualScheduler().fireAll());
            assertEquals(JoinPhase.FAILED, store.record(subject.key).phase());

            store.releaseFirstWrite();
            assertEquals(JoinFailureException.Reason.TIMEOUT, failedDecision(report).reason(),
                    "the late handoff must receive the timeout rather than Discarded(LATE)");
            assertEquals(1, countEvents(subject.monitor, ExecutionEventType.JOIN_FAILED));

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void failedTimeoutPersistenceFailsLocallyAndOverridesTheDelayedInitialWriteFailure() throws Exception {
        var store = new ControlledJoinStore(FirstWrite.HOLD_BEFORE_FAILURE, TimeoutLoad.DELEGATE,
                TimeoutWrite.THROW);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWriteEntered();

            assertEquals(1, subject.engine.manualScheduler().fireAll());
            store.awaitTimeoutWriteEntered();
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.timeoutRelinquished.get());
            assertEquals(0, countEvents(subject.monitor, ExecutionEventType.JOIN_FAILED),
                    "a local fallback must not claim its failed store write was durable");

            store.releaseFirstWrite();
            JoinFailureException failure = failedDecision(report);
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
            JoinStoreException storeFailure = assertInstanceOf(JoinStoreException.class, failure.getCause());
            assertEquals(JoinStoreException.Reason.UNAVAILABLE, storeFailure.reason());

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void failedTimeoutPersistencePreventsTheDelayedInitialWriteFromParking() throws Exception {
        var store = new ControlledJoinStore(FirstWrite.HOLD_BEFORE_SUCCESS, TimeoutLoad.DELEGATE,
                TimeoutWrite.FAIL);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWriteEntered();

            assertEquals(1, subject.engine.manualScheduler().fireAll());
            store.awaitTimeoutWriteEntered();
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.timeoutRelinquished.get());
            assertEquals(0, countEvents(subject.monitor, ExecutionEventType.JOIN_FAILED),
                    "a failed timeout write must not publish a durable failure event");
            assertFalse(report.isDone(), "the accepted initial write still has to drain");

            store.releaseFirstWrite();
            assertEquals(JoinPhase.OPEN, store.record(subject.key).phase(),
                    "the delayed writer demonstrates the dangerous OPEN-after-deadline ordering");
            JoinFailureException failure = failedDecision(report);
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
            assertEquals(JoinStoreException.Reason.UNAVAILABLE,
                    assertInstanceOf(JoinStoreException.class, failure.getCause()).reason());

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void throwingTimeoutLoadFailsLocallyWithoutConsumingTheDeadlineSilently() throws Exception {
        var store = new ControlledJoinStore(FirstWrite.HOLD_BEFORE_SUCCESS, TimeoutLoad.THROW,
                TimeoutWrite.DELEGATE);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWriteEntered();

            assertEquals(1, subject.engine.manualScheduler().fireAll());
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.timeoutRelinquished.get());
            assertEquals(0, store.timeoutWriteCount());
            assertEquals(0, countEvents(subject.monitor, ExecutionEventType.JOIN_FAILED));

            store.releaseFirstWrite();
            JoinFailureException failure = failedDecision(report);
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
            assertEquals(JoinStoreException.Reason.UNAVAILABLE,
                    assertInstanceOf(JoinStoreException.class, failure.getCause()).reason());

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void pauseInvalidatesAnUnsubmittedTimeoutWriteAndResumeReusesTheRemainingBudget() throws Exception {
        var store = new ControlledJoinStore(FirstWrite.IMMEDIATE, TimeoutLoad.HOLD, TimeoutWrite.DELEGATE);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var parked = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWritePersisted();
            assertFalse(parked.isDone());

            assertEquals(1, subject.engine.manualScheduler().fireAll());
            store.awaitTimeoutLoadEntered();
            subject.coordinator.suspendTimeouts();
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.coordinator.suspendedTimeoutCount());

            store.releaseTimeoutLoad();
            assertEquals(0, store.timeoutWriteCount(),
                    "the generation changed while load was pending, so no fresh write may start");
            assertEquals(JoinPhase.OPEN, store.record(subject.key).phase());

            subject.coordinator.resumeTimeouts();
            assertEquals(List.of(TIMEOUT, TIMEOUT), subject.engine.manualScheduler().requestedDelays());
            assertEquals(1, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.engine.manualScheduler().fireAll());
            assertEquals(JoinFailureException.Reason.TIMEOUT, failedWaiter(parked).reason());
            assertEquals(1, store.timeoutWriteCount());
            assertEquals(0, subject.coordinator.liveTimeoutCount());
            assertEquals(1, subject.timeoutRelinquished.get());

            subject.terminate();
            assertEquals(0, recordCount(store));
        }
    }

    @Test
    void terminationDrainsBothAcceptedWritesAndKeepsItsLateDiscardVerdict() throws Exception {
        verifyTerminationDrain(false);
        verifyTerminationDrain(true);
    }

    private void verifyTerminationDrain(boolean timeoutWriteFirst) throws Exception {
        var store = new ControlledJoinStore(FirstWrite.HOLD_BEFORE_SUCCESS, TimeoutLoad.DELEGATE,
                TimeoutWrite.HOLD);
        try (var subject = subject(store, new MutableClock(EPOCH))) {
            var report = subject.coordinator.arrive("join", arrival()).toCompletableFuture();
            store.awaitFirstWriteEntered();
            assertEquals(1, subject.engine.manualScheduler().fireAll());
            store.awaitTimeoutWriteEntered();

            CompletionStage<Void> termination = subject.coordinator.terminate();
            assertSame(termination, subject.coordinator.terminate());
            assertFalse(termination.toCompletableFuture().isDone(),
                    "discard must remain behind both accepted store operations");
            assertEquals(0, subject.coordinator.liveTimeoutCount());

            if (timeoutWriteFirst) {
                store.releaseTimeoutWrite();
                store.releaseFirstWrite();
            } else {
                store.releaseFirstWrite();
                store.releaseTimeoutWrite();
            }

            termination.toCompletableFuture().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
            assertInstanceOf(JoinDecision.Discarded.class,
                    report.get(BOUND.toMillis(), TimeUnit.MILLISECONDS),
                    "termination won before either write completed, so the late report stays discarded");
            assertEquals(0, recordCount(store));
            assertEquals(1, subject.timeoutRelinquished.get());
        }
    }

    private static Subject subject(ControlledJoinStore store, MutableClock clock) {
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                processInstanceId, traversalId);
        var timeoutRelinquished = new AtomicInteger();
        var coordinator = new JoinCoordinator(store, engine.scheduler(), monitor, identity,
                Map.of("join", new JoinSpec("join", List.of("b0", "b1"), 2, TIMEOUT)), clock,
                timeoutRelinquished::incrementAndGet);
        return new Subject(engine, monitor, store, coordinator,
                new JoinKey(TestIdentities.TENANT_A.tenantId(), processInstanceId, traversalId, "join"),
                timeoutRelinquished);
    }

    private static JoinArrival arrival() {
        return new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of());
    }

    private static JoinFailureException failedDecision(CompletableFuture<JoinDecision> report) throws Exception {
        JoinDecision decision = report.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        return assertInstanceOf(JoinDecision.Failed.class, decision).failure();
    }

    private static long recordCount(ControlledJoinStore store) throws Exception {
        return store.recordCount(TestIdentities.TENANT_A.tenantId()).toCompletableFuture()
                .get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static JoinFailureException failedWaiter(CompletableFuture<JoinDecision> report) {
        try {
            report.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError("expected the parked join branch to fail");
        } catch (ExecutionException failed) {
            return assertInstanceOf(JoinFailureException.class, failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a typed join failure", interrupted);
        } catch (Exception unexpected) {
            throw new AssertionError("expected a typed join failure", unexpected);
        }
    }

    private static ai.ravenroot.api.application.ExecutionEvent onlyJoinFailureEvent(ExecutionMonitor monitor) {
        var matches = monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_FAILED)
                .toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    private static long countEvents(ExecutionMonitor monitor, ExecutionEventType type) {
        return monitor.eventsAfter(0).stream().filter(event -> event.type() == type).count();
    }

    private record Subject(JoinTestEngine engine, ExecutionMonitor monitor, ControlledJoinStore store,
                           JoinCoordinator coordinator, JoinKey key, AtomicInteger timeoutRelinquished)
            implements AutoCloseable {
        void terminate() throws Exception {
            coordinator.terminate().toCompletableFuture().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            store.releaseAll();
            try {
                terminate();
            } finally {
                try {
                    engine.close();
                } finally {
                    store.close();
                }
            }
        }
    }

    private enum FirstWrite { IMMEDIATE, HOLD_BEFORE_SUCCESS, HOLD_BEFORE_FAILURE, HOLD_AFTER_SUCCESS }

    private enum TimeoutLoad { DELEGATE, HOLD, THROW }

    private enum TimeoutWrite { DELEGATE, FAIL, THROW, HOLD }

    /** Controls effects and returned stages independently, while delegating the actual CAS semantics. */
    private static final class ControlledJoinStore implements JoinStore {
        private final InMemoryJoinStore delegate = new InMemoryJoinStore();
        private final FirstWrite firstWrite;
        private final TimeoutLoad timeoutLoad;
        private final TimeoutWrite timeoutWrite;
        private final AtomicBoolean firstWriteClaimed = new AtomicBoolean();
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger timeoutWrites = new AtomicInteger();
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch firstWritePersisted = new CountDownLatch(1);
        private final CountDownLatch timeoutLoadEntered = new CountDownLatch(1);
        private final CountDownLatch timeoutWriteEntered = new CountDownLatch(1);
        private final CompletableFuture<Void> releaseFirstWrite = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseTimeoutLoad = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseTimeoutWrite = new CompletableFuture<>();

        private ControlledJoinStore(FirstWrite firstWrite, TimeoutLoad timeoutLoad, TimeoutWrite timeoutWrite) {
            this.firstWrite = firstWrite;
            this.timeoutLoad = timeoutLoad;
            this.timeoutWrite = timeoutWrite;
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
            int attempt = loads.incrementAndGet();
            if (attempt == 2) {
                return switch (timeoutLoad) {
                    case DELEGATE -> delegate.load(key);
                    case HOLD -> {
                        timeoutLoadEntered.countDown();
                        yield releaseTimeoutLoad.thenCompose(ignored -> delegate.load(key));
                    }
                    case THROW -> throw unavailable(key, "timeout load");
                };
            }
            return delegate.load(key);
        }

        @Override
        public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
            if (desired.phase() == JoinPhase.OPEN && firstWriteClaimed.compareAndSet(false, true)) {
                firstWriteEntered.countDown();
                return switch (firstWrite) {
                    case IMMEDIATE -> persistFirst(desired);
                    case HOLD_BEFORE_SUCCESS -> releaseFirstWrite.thenCompose(ignored -> persistFirst(desired));
                    case HOLD_BEFORE_FAILURE -> releaseFirstWrite.thenCompose(
                            ignored -> failed(desired.key(), "initial write"));
                    case HOLD_AFTER_SUCCESS -> delegate.compareAndSet(desired).thenCompose(stored -> {
                        firstWritePersisted.countDown();
                        return releaseFirstWrite.thenApply(ignored -> stored);
                    });
                };
            }
            if (desired.phase() == JoinPhase.FAILED && desired.failureReason() == JoinFailureReason.TIMEOUT) {
                timeoutWrites.incrementAndGet();
                timeoutWriteEntered.countDown();
                return switch (timeoutWrite) {
                    case DELEGATE -> delegate.compareAndSet(desired);
                    case FAIL -> failed(desired.key(), "timeout write");
                    case THROW -> throw unavailable(desired.key(), "timeout write");
                    case HOLD -> releaseTimeoutWrite.thenCompose(ignored -> delegate.compareAndSet(desired));
                };
            }
            return delegate.compareAndSet(desired);
        }

        private CompletionStage<JoinRecord> persistFirst(JoinRecord desired) {
            return delegate.compareAndSet(desired).thenApply(stored -> {
                firstWritePersisted.countDown();
                return stored;
            });
        }

        private static CompletionStage<JoinRecord> failed(JoinKey key, String operation) {
            return CompletableFuture.failedFuture(unavailable(key, operation));
        }

        private static JoinStoreException unavailable(JoinKey key, String operation) {
            return new JoinStoreException(JoinStoreException.Reason.UNAVAILABLE,
                    key, operation + " unavailable");
        }

        private void awaitFirstWriteEntered() throws InterruptedException {
            assertTrue(firstWriteEntered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }

        private void awaitFirstWritePersisted() throws InterruptedException {
            assertTrue(firstWritePersisted.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }

        private void awaitTimeoutLoadEntered() throws InterruptedException {
            assertTrue(timeoutLoadEntered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }

        private void awaitTimeoutWriteEntered() throws InterruptedException {
            assertTrue(timeoutWriteEntered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }

        private void releaseFirstWrite() {
            releaseFirstWrite.complete(null);
        }

        private void releaseTimeoutLoad() {
            releaseTimeoutLoad.complete(null);
        }

        private void releaseTimeoutWrite() {
            releaseTimeoutWrite.complete(null);
        }

        private void releaseAll() {
            releaseFirstWrite();
            releaseTimeoutLoad();
            releaseTimeoutWrite();
        }

        private int timeoutWriteCount() {
            return timeoutWrites.get();
        }

        private JoinRecord record(JoinKey key) throws Exception {
            return stored(key).toCompletableFuture().get(BOUND.toMillis(), TimeUnit.MILLISECONDS).orElseThrow();
        }

        private CompletionStage<Optional<JoinRecord>> stored(JoinKey key) {
            return delegate.load(key);
        }

        @Override
        public CompletionStage<Boolean> discard(JoinKey key) {
            return delegate.discard(key);
        }

        @Override
        public CompletionStage<List<JoinRecord>> openJoins(String tenantId) {
            return delegate.openJoins(tenantId);
        }

        @Override
        public CompletionStage<Long> recordCount(String tenantId) {
            return delegate.recordCount(tenantId);
        }

        @Override
        public CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff) {
            return delegate.purgeSettledBefore(tenantId, cutoff);
        }

        @Override
        public void close() {
            releaseAll();
            delegate.close();
        }
    }
}
