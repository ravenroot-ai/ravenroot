package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyBinding;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyLimits;
import ai.ravenroot.api.deployment.RequestReplyOutcome;
import ai.ravenroot.api.deployment.RequestReplyProjection;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.ScheduledTask;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestReplyCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant-a", "source-a",
            PrincipalType.WORKLOAD, "urn:test");

    @Test
    void completedResultWinsOnceAndIsProjectedThroughAReadOnlyBoundedStage() throws Exception {
        var fixture = fixture(2, limits(2, 256, 256));
        RequestReplyExchange exchange = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.map(Map.of("request", PayloadValue.of("value"))), NOW.plusSeconds(5)));

        assertEquals(IDENTITY, fixture.control.security);
        assertEquals(Map.of("request", "value"), fixture.control.payload);
        assertEquals(exchange.processInstanceId(), fixture.control.processInstanceId);
        assertEquals(exchange.traversalId(), fixture.control.traversalId);

        // Completing a CompletableFuture copy obtained by the caller cannot publish into the
        // coordinator's minimal stage.
        RequestReplyOutcome fabricated = cancelled(exchange);
        assertTrue(exchange.completion().toCompletableFuture().complete(fabricated));

        fixture.control.complete(new GraphExecutionResult(exchange.processInstanceId(), exchange.traversalId(),
                Map.of("reply", "ok"), Set.of("start", "end"), Set.of(), Set.of(), Set.of()));
        RequestReplyOutcome outcome = exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state());
        assertEquals(Map.of("reply", "ok"), outcome.payload());
        assertEquals(Set.of("start", "end"), outcome.executionOutcome().orElseThrow().visitedNodes());
        assertFalse(exchange.cancel(), "a terminal result cannot be replaced by cancellation");
        assertEquals(0, fixture.coordinator.pendingCount());
        assertEquals(2, fixture.executionPermits.availablePermits());
        assertEquals(0, fixture.control.cancelled.size());
    }

    @Test
    void timeoutDetachesWaiterButExecutionCapacityRemainsHeldUntilLateTraversalSettles() throws Exception {
        var fixture = fixture(1, limits(1, 256, 256));
        RequestReplyExchange first = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("one"), NOW.plusSeconds(2)));

        fixture.clock.advance(Duration.ofSeconds(2));
        assertEquals(1, fixture.scheduler.fireAll());
        RequestReplyOutcome timedOut = first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(RequestReplyTerminalState.TIMED_OUT, timedOut.state());
        assertEquals(0, fixture.coordinator.pendingCount(), "deadline must detach the waiter immediately");
        assertEquals(java.util.List.of(first.traversalId()), fixture.control.cancelled);
        assertFalse(first.cancel(), "timeout already owns the one terminal transition");
        assertEquals(java.util.List.of(first.traversalId()), fixture.control.cancelled,
                "a losing cancellation must not repeat cooperative cancellation");

        assertRefused(fixture.coordinator.request(IngressTarget.start(), PayloadValue.of("two"),
                fixture.clock.instant().plusSeconds(2)), RequestReplyRefusal.CAPACITY_EXHAUSTED);

        fixture.control.complete(new GraphExecutionResult(first.processInstanceId(), first.traversalId(),
                "late", Set.of("end"), Set.of(), Set.of(), Set.of()));
        assertEquals(RequestReplyTerminalState.TIMED_OUT,
                first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());

        RequestReplyExchange replacement = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("three"), fixture.clock.instant().plusSeconds(2)));
        assertFalse(replacement.traversalId().equals(first.traversalId()));
    }

    @Test
    void explicitCancelBeatsDeadlineAndLateResultAndIsIdempotent() throws Exception {
        var fixture = fixture(1, limits(1, 256, 256));
        RequestReplyExchange exchange = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("value"), NOW.plusSeconds(3)));

        assertTrue(exchange.cancel());
        assertFalse(exchange.cancel());
        fixture.clock.advance(Duration.ofSeconds(3));
        fixture.scheduler.fireAll();
        fixture.control.complete(new GraphExecutionResult(exchange.processInstanceId(), exchange.traversalId(),
                "late", Set.of("end"), Set.of(), Set.of(), Set.of()));

        assertEquals(RequestReplyTerminalState.CANCELLED,
                exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(java.util.List.of(exchange.traversalId()), fixture.control.cancelled);
        assertEquals(0, fixture.coordinator.pendingCount());
    }

    @Test
    void absoluteDeadlineWinsWhenSchedulerWakeupIsDelayed() throws Exception {
        var fixture = fixture(1, limits(1, 256, 256));
        RequestReplyExchange exchange = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("value"), NOW.plusSeconds(1)));

        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.control.complete(new GraphExecutionResult(exchange.processInstanceId(), exchange.traversalId(),
                "too-late", Set.of("end"), Set.of(), Set.of(), Set.of()));

        assertEquals(RequestReplyTerminalState.TIMED_OUT,
                exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(java.util.List.of(exchange.traversalId()), fixture.control.cancelled,
                "the clock, not delayed scheduler delivery, is the deadline authority");
    }

    @Test
    void requestAndOutcomePayloadBoundsFailClosedWithoutLeakingContent() throws Exception {
        var fixture = fixture(1, limits(1, 16, 16));

        assertRefused(fixture.coordinator.request(IngressTarget.start(), PayloadValue.of("0123456789abcdef"),
                NOW.plusSeconds(1)), RequestReplyRefusal.PAYLOAD_REJECTED);
        assertEquals(0, fixture.control.executeCount);

        RequestReplyExchange exchange = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("ok"), NOW.plusSeconds(1)));
        fixture.control.complete(new GraphExecutionResult(exchange.processInstanceId(), exchange.traversalId(),
                "0123456789abcdef", Set.of("end"), Set.of(), Set.of(), Set.of()));
        RequestReplyOutcome failed = exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RequestReplyTerminalState.FAILED, failed.state());
        assertNull(failed.payload());
        assertEquals(Set.of("end"), failed.executionOutcome().orElseThrow().visitedNodes());
    }

    @Test
    void closeCancelsEveryWaiterAndLateCompletionsCannotAffectReplacementGeneration() throws Exception {
        var fixture = fixture(2, limits(2, 256, 256));
        RequestReplyExchange first = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("one"), NOW.plusSeconds(5)));
        RequestReplyExchange second = accepted(fixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("two"), NOW.plusSeconds(5)));

        fixture.coordinator.close();
        fixture.coordinator.close();
        assertEquals(RequestReplyTerminalState.CANCELLED,
                first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(RequestReplyTerminalState.CANCELLED,
                second.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(0, fixture.coordinator.pendingCount());
        assertRefused(fixture.coordinator.request(IngressTarget.start(), PayloadValue.of("late"),
                NOW.plusSeconds(1)), RequestReplyRefusal.ADMISSION_CLOSED);

        fixture.control.completeAt(0, result(first, "old-one"));
        fixture.control.completeAt(1, result(second, "old-two"));
        assertEquals(RequestReplyTerminalState.CANCELLED,
                first.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(2, fixture.executionPermits.availablePermits());

        var replacementFixture = fixture(1, limits(1, 256, 256));
        RequestReplyExchange replacement = accepted(replacementFixture.coordinator.request(IngressTarget.start(),
                PayloadValue.of("new"), NOW.plusSeconds(1)));
        replacementFixture.control.complete(result(replacement, "new-result"));
        assertEquals("new-result", replacement.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).payload());
    }

    @Test
    void inlineDeadlineCallbackReturnsCapacityAndNeverDispatches() throws Exception {
        var clock = new MutableClock(NOW);
        var scheduler = new ManualScheduler(true);
        var control = new ManualControl();
        var permits = new Semaphore(1);
        var coordinator = new RequestReplyCoordinator("deployment-a", 7, IDENTITY, identities(), scheduler,
                clock, limits(1, 256, 256), permits, control);

        RequestReplyExchange exchange = accepted(coordinator.request(IngressTarget.start(), PayloadValue.of("value"),
                NOW.plusSeconds(1)));

        assertEquals(RequestReplyTerminalState.TIMED_OUT,
                exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(0, control.executeCount);
        assertEquals(1, permits.availablePermits());
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void timeoutBetweenDeadlineArmingAndDispatchPublicationPreventsUnobservedTraversal() throws Exception {
        var clock = new MutableClock(NOW);
        var scheduler = new ManualScheduler(false);
        var control = new ManualControl();
        var permits = new Semaphore(1);
        var reachedDispatchBoundary = new CountDownLatch(1);
        var releaseDispatchBoundary = new CountDownLatch(1);
        var firstRequest = new java.util.concurrent.atomic.AtomicBoolean(true);
        var coordinator = new RequestReplyCoordinator("deployment-a", 7, IDENTITY, identities(), scheduler,
                clock, limits(1, 256, 256), permits, control, () -> {
                    if (firstRequest.compareAndSet(true, false)) {
                        reachedDispatchBoundary.countDown();
                        await(releaseDispatchBoundary);
                    }
                });

        try (var requester = Executors.newSingleThreadExecutor()) {
            var admission = requester.submit(() -> coordinator.request(IngressTarget.start(),
                    PayloadValue.of("first"), NOW.plusSeconds(1)));
            assertTrue(reachedDispatchBoundary.await(1, TimeUnit.SECONDS));

            clock.advance(Duration.ofSeconds(1));
            assertEquals(1, scheduler.fireAll());
            assertEquals(0, control.executeCount,
                    "a deadline that owns the pre-dispatch handshake must start no traversal");
            assertEquals(0, coordinator.pendingCount(), "timeout must detach the waiter immediately");
            assertEquals(1, permits.availablePermits(), "pre-dispatch timeout must return execution capacity");

            releaseDispatchBoundary.countDown();
            RequestReplyExchange timedOut = accepted(admission.get(1, TimeUnit.SECONDS));
            assertEquals(RequestReplyTerminalState.TIMED_OUT,
                    timedOut.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
            assertFalse(timedOut.cancel(), "the pre-dispatch timeout must remain the only terminal winner");
            assertEquals(0, control.executeCount,
                    "releasing the stale request thread must not start terminal work");
            assertEquals(0, control.cancelled.size(), "work that never started needs no cancellation");

            RequestReplyExchange replacement = accepted(coordinator.request(IngressTarget.start(),
                    PayloadValue.of("replacement"), clock.instant().plusSeconds(1)));
            assertFalse(replacement.processInstanceId().equals(timedOut.processInstanceId()));
            assertFalse(replacement.traversalId().equals(timedOut.traversalId()));
            assertEquals(1, control.executeCount,
                    "both waiter and execution permits must be reusable after pre-dispatch timeout");
            control.complete(result(replacement, "ok"));
            assertEquals(RequestReplyTerminalState.COMPLETED,
                    replacement.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void barrierSynchronizedResultCancelTimeoutRacePublishesExactlyOneTerminal() throws Exception {
        for (int iteration = 0; iteration < 40; iteration++) {
            var fixture = fixture(1, limits(1, 256, 256));
            RequestReplyExchange exchange = accepted(fixture.coordinator.request(IngressTarget.start(),
                    PayloadValue.of("value"), NOW.plusSeconds(1)));
            fixture.clock.advance(Duration.ofSeconds(1));
            var barrier = new CyclicBarrier(3);
            try (var pool = Executors.newFixedThreadPool(3)) {
                var result = pool.submit(() -> {
                    barrier.await();
                    fixture.control.complete(result(exchange, "result"));
                    return null;
                });
                var cancel = pool.submit(() -> {
                    barrier.await();
                    exchange.cancel();
                    return null;
                });
                var timeout = pool.submit(() -> {
                    barrier.await();
                    fixture.scheduler.fireAll();
                    return null;
                });
                result.get(1, TimeUnit.SECONDS);
                cancel.get(1, TimeUnit.SECONDS);
                timeout.get(1, TimeUnit.SECONDS);
            }
            RequestReplyOutcome winner = exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertTrue(winner.state() == RequestReplyTerminalState.CANCELLED
                    || winner.state() == RequestReplyTerminalState.TIMED_OUT);
            assertEquals(0, fixture.coordinator.pendingCount());
            assertEquals(1, fixture.executionPermits.availablePermits());
        }
    }

    // ------------------------------------------------------- pre-dispatch payload projection

    @Test
    void projectionSeesOnlyRuntimeIssuedMetadataAndItsPayloadIsWhatReachesTheRunner() throws Exception {
        var fixture = fixture(2, limits(2, 256, 256));
        var seen = new AtomicReference<RequestReplyContext>();
        Instant deadline = NOW.plusSeconds(5);

        RequestReplyExchange exchange = accepted(fixture.coordinator.requestProjected(IngressTarget.start(),
                Optional.of("source-node"), context -> {
                    seen.set(context);
                    return PayloadValue.map(Map.of("traversal",
                            PayloadValue.of(context.traversalId().toString())));
                }, deadline));

        RequestReplyContext context = seen.get();
        assertEquals(exchange.correlationId(), context.correlationId());
        assertEquals(exchange.processInstanceId(), context.processInstanceId());
        assertEquals(exchange.traversalId(), context.traversalId());
        assertEquals(exchange.deadline(), context.deadline());
        assertEquals(deadline, context.deadline());
        assertEquals(IngressTarget.start(), context.target());
        assertEquals(new RequestReplyBinding("tenant-a", "deployment-a", 7, Optional.of("source-node")),
                context.binding(), "the binding is the coordinator's own fence, not caller input");
        assertEquals(Map.of("traversal", exchange.traversalId().toString()), fixture.control.payload,
                "the projected payload, not the caller's, is what the traversal receives");
        assertEquals(IDENTITY, fixture.control.security);
        assertEquals(1, fixture.control.executeCount);
    }

    /**
     * Every way a projection can fail is one refusal shape: nothing admitted, nothing pending, nothing
     * dispatched, and both permits back. The loop runs on a fixture with exactly one waiter and one
     * execution permit, so a single leaked permit on any iteration makes the next one refuse
     * {@code CAPACITY_EXHAUSTED} instead of {@code PAYLOAD_REJECTED}.
     */
    @Test
    void everyProjectionFailureModeRefusesBeforeDispatchAndReturnsBothPermits() {
        var fixture = fixture(1, limits(1, 16, 16));
        var failing = List.<RequestReplyProjection>of(
                context -> null,
                context -> {
                    throw PayloadException.tooLarge(64, 16);
                },
                context -> {
                    throw new IllegalStateException("a projection may fail in ways this runtime cannot name");
                },
                context -> PayloadValue.of("0123456789abcdef"));

        for (RequestReplyProjection projection : failing) {
            assertRefused(fixture.coordinator.requestProjected(IngressTarget.start(), Optional.empty(),
                    projection, NOW.plusSeconds(1)), RequestReplyRefusal.PAYLOAD_REJECTED);
            assertEquals(0, fixture.coordinator.pendingCount(), "a failed projection leaves no pending exchange");
            assertEquals(0, fixture.control.executeCount, "a failed projection admits no traversal");
            assertEquals(1, fixture.executionPermits.availablePermits());
        }

        // The waiter permit has no observable counter here, so its return is proved by admitting up to
        // the fixture's single waiter after four failures had already reserved and released it.
        accepted(fixture.coordinator.requestProjected(IngressTarget.start(), Optional.empty(),
                context -> PayloadValue.of("ok"), NOW.plusSeconds(1)));
        assertEquals(1, fixture.control.executeCount);
        assertEquals(0, fixture.executionPermits.availablePermits());
    }

    /**
     * Identifiers are runtime-owned in both directions: a projection cannot choose them, and the ones
     * allocated for an offer it then failed are discarded rather than handed to the next caller. The
     * fixture's identity source is a strict counter, so the assertion is exact rather than "distinct".
     */
    @Test
    void identifiersAreIssuedByTheRuntimeAndAreNeverReissuedAfterAFailedProjection() {
        var fixture = fixture(2, limits(2, 256, 256));
        var abandoned = new AtomicReference<RequestReplyContext>();

        assertRefused(fixture.coordinator.requestProjected(IngressTarget.start(), Optional.empty(),
                context -> {
                    abandoned.set(context);
                    throw PayloadException.tooLarge(64, 16);
                }, NOW.plusSeconds(1)), RequestReplyRefusal.PAYLOAD_REJECTED);
        assertEquals(new UUID(0, 1), abandoned.get().correlationId());
        assertEquals(new UUID(0, 3), abandoned.get().traversalId());

        // A projection that ignores its context cannot influence what the runtime issued.
        RequestReplyExchange first = accepted(fixture.coordinator.requestProjected(IngressTarget.start(),
                Optional.empty(), context -> PayloadValue.of("one"), NOW.plusSeconds(1)));
        RequestReplyExchange second = accepted(fixture.coordinator.requestProjected(IngressTarget.start(),
                Optional.empty(), context -> PayloadValue.of("two"), NOW.plusSeconds(1)));

        assertEquals(new UUID(0, 4), first.correlationId(), "discarded identifiers are not recycled");
        assertEquals(new UUID(0, 6), first.traversalId());
        assertEquals(new UUID(0, 7), second.correlationId());
        assertNotEquals(first.correlationId(), second.correlationId());
        assertNotEquals(first.processInstanceId(), second.processInstanceId());
        assertNotEquals(first.traversalId(), second.traversalId());
        assertNotEquals(abandoned.get().traversalId(), first.traversalId());
    }

    /**
     * The projected path enters the same dispatch handshake, so a deadline that wins the pre-dispatch
     * boundary still starts no traversal and still returns capacity. Driven inline from the hook, so
     * the interleave is fixed rather than raced.
     */
    @Test
    void deadlineWinningTheProjectedDispatchHandshakeStartsNoTraversal() throws Exception {
        var clock = new MutableClock(NOW);
        var scheduler = new ManualScheduler(false);
        var control = new ManualControl();
        var permits = new Semaphore(1);
        var firstDispatch = new java.util.concurrent.atomic.AtomicBoolean(true);
        var coordinator = new RequestReplyCoordinator("deployment-a", 7, IDENTITY, identities(), scheduler,
                clock, limits(1, 256, 256), permits, control, () -> {
                    if (firstDispatch.compareAndSet(true, false)) {
                        clock.advance(Duration.ofSeconds(1));
                        scheduler.fireAll();
                    }
                });

        RequestReplyExchange exchange = accepted(coordinator.requestProjected(IngressTarget.start(),
                Optional.empty(), context -> PayloadValue.of("value"), NOW.plusSeconds(1)));

        assertEquals(RequestReplyTerminalState.TIMED_OUT,
                exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(0, control.executeCount, "a deadline owning the handshake starts no traversal");
        assertEquals(0, control.cancelled.size(), "work that never started needs no cancellation");
        assertFalse(exchange.cancel(), "the pre-dispatch timeout is the only terminal winner");
        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, permits.availablePermits());
    }

    /** Cancellation after a projected admission behaves exactly as it does for an ordinary request. */
    @Test
    void cancellingAProjectedExchangeIsIdempotentAndBeatsALateResult() throws Exception {
        var fixture = fixture(1, limits(1, 256, 256));
        RequestReplyExchange exchange = accepted(fixture.coordinator.requestProjected(IngressTarget.start(),
                Optional.empty(), context -> PayloadValue.of("value"), NOW.plusSeconds(3)));

        assertTrue(exchange.cancel());
        assertFalse(exchange.cancel());
        fixture.control.complete(result(exchange, "late"));

        assertEquals(RequestReplyTerminalState.CANCELLED,
                exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        assertEquals(java.util.List.of(exchange.traversalId()), fixture.control.cancelled);
        assertEquals(0, fixture.coordinator.pendingCount());
        assertEquals(1, fixture.executionPermits.availablePermits());
    }

    @Test
    void closedAdmissionRefusesTheProjectedPathWithoutRunningItsProjection() {
        var fixture = fixture(1, limits(1, 256, 256));
        fixture.coordinator.close();
        var ran = new java.util.concurrent.atomic.AtomicBoolean();

        assertRefused(fixture.coordinator.requestProjected(IngressTarget.start(), Optional.empty(),
                context -> {
                    ran.set(true);
                    return PayloadValue.of("value");
                }, NOW.plusSeconds(1)), RequestReplyRefusal.ADMISSION_CLOSED);

        assertFalse(ran.get(), "a closed coordinator must not run caller code");
        assertEquals(1, fixture.executionPermits.availablePermits());
    }

    /**
     * Stop while a projection is still running. {@code close()} needs the lifecycle write lock, which
     * the in-flight projection holds for reading, so the stop is ordered after the projection rather
     * than interleaved with it: the exchange is admitted and then immediately cancelled by the stop.
     * What must hold either way is that nothing is left behind — no pending exchange, no retained
     * waiter, and the execution permit back once the detached traversal settles.
     */
    @Test
    void stopDuringAnInFlightProjectionLeavesNoPendingExchangeAndNoRetainedPermit() throws Exception {
        var fixture = fixture(1, limits(1, 256, 256));
        var insideProjection = new CountDownLatch(1);
        var releaseProjection = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<RequestReplyAdmission> admission =
                    pool.submit(() -> fixture.coordinator.requestProjected(IngressTarget.start(),
                            Optional.empty(), context -> {
                                insideProjection.countDown();
                                await(releaseProjection);
                                return PayloadValue.of("value");
                            }, NOW.plusSeconds(5)));
            assertTrue(insideProjection.await(1, TimeUnit.SECONDS));
            var stop = pool.submit(() -> {
                fixture.coordinator.close();
                return null;
            });

            releaseProjection.countDown();
            RequestReplyExchange exchange = accepted(admission.get(2, TimeUnit.SECONDS));
            stop.get(2, TimeUnit.SECONDS);

            assertEquals(RequestReplyTerminalState.CANCELLED,
                    exchange.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
            assertEquals(0, fixture.coordinator.pendingCount());
            // The detached traversal keeps its execution permit until the runner settles, exactly as
            // an ordinary cancelled request does; the waiter is released immediately.
            fixture.control.complete(result(exchange, "late"));
            assertEquals(1, fixture.executionPermits.availablePermits());
            assertRefused(fixture.coordinator.requestProjected(IngressTarget.start(), Optional.empty(),
                    context -> PayloadValue.of("after-stop"), NOW.plusSeconds(5)),
                    RequestReplyRefusal.ADMISSION_CLOSED);
        }
    }

    private static Fixture fixture(int capacity, RequestReplyLimits limits) {
        var clock = new MutableClock(NOW);
        var scheduler = new ManualScheduler(false);
        var control = new ManualControl();
        var permits = new Semaphore(capacity);
        var coordinator = new RequestReplyCoordinator("deployment-a", 7, IDENTITY, identities(), scheduler,
                clock, limits, permits, control);
        return new Fixture(coordinator, clock, scheduler, control, permits);
    }

    private static ExecutionIdentitySource identities() {
        var sequence = new AtomicLong();
        return ignored -> new UUID(0, sequence.incrementAndGet());
    }

    private static RequestReplyLimits limits(int waiters, int requestBytes, int outcomeBytes) {
        return new RequestReplyLimits(waiters, Duration.ofSeconds(10),
                payloadLimits(requestBytes), payloadLimits(outcomeBytes));
    }

    private static PayloadLimits payloadLimits(int bytes) {
        return new PayloadLimits(bytes, 8, 32, 128, 128, 64);
    }

    private static RequestReplyExchange accepted(RequestReplyAdmission admission) {
        return assertInstanceOf(RequestReplyAdmission.Accepted.class, admission).exchange();
    }

    private static void assertRefused(RequestReplyAdmission admission, RequestReplyRefusal reason) {
        assertEquals(reason, assertInstanceOf(RequestReplyAdmission.Refused.class, admission).reason());
    }

    private static RequestReplyOutcome cancelled(RequestReplyExchange exchange) {
        return new RequestReplyOutcome(exchange.processInstanceId(), exchange.traversalId(),
                RequestReplyTerminalState.CANCELLED, java.util.Optional.empty());
    }

    private static GraphExecutionResult result(RequestReplyExchange exchange, Object payload) {
        return new GraphExecutionResult(exchange.processInstanceId(), exchange.traversalId(), payload,
                Set.of("end"), Set.of(), Set.of(), Set.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for deterministic dispatch interleave");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for deterministic dispatch interleave", interrupted);
        }
    }

    private record Fixture(RequestReplyCoordinator coordinator, MutableClock clock,
                           ManualScheduler scheduler, ManualControl control, Semaphore executionPermits) {
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ManualScheduler implements Scheduler {
        private final boolean inline;
        private final java.util.List<Task> tasks = new ArrayList<>();

        private ManualScheduler(boolean inline) {
            this.inline = inline;
        }

        @Override
        public synchronized ScheduledTask schedule(Duration delay, Runnable task) {
            var scheduled = new Task(task);
            tasks.add(scheduled);
            if (inline) {
                scheduled.fire();
            }
            return scheduled;
        }

        synchronized int fireAll() {
            int fired = 0;
            for (Task task : java.util.List.copyOf(tasks)) {
                if (task.fire()) {
                    fired++;
                }
            }
            return fired;
        }

        private static final class Task implements ScheduledTask {
            private final Runnable command;
            private final java.util.concurrent.atomic.AtomicBoolean live = new java.util.concurrent.atomic.AtomicBoolean(true);

            private Task(Runnable command) {
                this.command = command;
            }

            boolean fire() {
                if (!live.compareAndSet(true, false)) {
                    return false;
                }
                command.run();
                return true;
            }

            @Override
            public boolean cancel() {
                return live.compareAndSet(true, false);
            }
        }
    }

    private static final class ManualControl implements RequestReplyCoordinator.TraversalControl {
        private final java.util.List<CompletableFuture<GraphExecutionResult>> executions = new ArrayList<>();
        private final java.util.List<UUID> cancelled = new ArrayList<>();
        private int executeCount;
        private SecurityContext security;
        private UUID processInstanceId;
        private UUID traversalId;
        private Object payload;

        @Override
        public synchronized CompletionStage<GraphExecutionResult> execute(SecurityContext security,
                UUID processInstanceId, UUID traversalId, Object payload) {
            executeCount++;
            this.security = security;
            this.processInstanceId = processInstanceId;
            this.traversalId = traversalId;
            this.payload = payload;
            var future = new CompletableFuture<GraphExecutionResult>();
            executions.add(future);
            return future;
        }

        @Override
        public synchronized void cancel(UUID traversalId) {
            cancelled.add(traversalId);
        }

        synchronized void complete(GraphExecutionResult result) {
            completeAt(executions.size() - 1, result);
        }

        synchronized void completeAt(int index, GraphExecutionResult result) {
            executions.get(index).complete(result);
        }
    }
}
