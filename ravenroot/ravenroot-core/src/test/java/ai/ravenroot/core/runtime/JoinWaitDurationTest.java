package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code JoinCoordinator} must report the real elapsed time between a join opening
 * and settling, not a value that only happens to be present.
 *
 * <p>Drives the real coordinator and the real in-memory store over a {@link MutableClock}, exactly
 * as {@code JoinTimeoutRaceTest} and {@code JoinRetainedStateTest} already do for other timing
 * properties, so the duration under test is computed by {@link JoinCoordinator#joinWait}, not
 * fabricated by the test. Nothing here is stubbed: the only thing supplied is how far the clock has
 * moved between two real branch reports.</p>
 */
class JoinWaitDurationTest {
    private static final Instant EPOCH = Instant.parse("2026-08-10T00:00:00Z");

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void joinSatisfiedReportsExactlyTheElapsedTimeBetweenTheOpeningAndTheSettlingArrival() throws Exception {
        var monitor = new ExecutionMonitor();
        var clock = new MutableClock(EPOCH);
        var coordinator = coordinator(monitor, clock, quorumTwoOfTwo());

        // The first branch to touch this join opens its record at the clock's current instant.
        // arrive() parks a Wait decision until the join itself settles (see its javadoc), so this
        // future is deliberately not awaited yet -- awaiting it here would block on the very event
        // the second call below is what produces.
        var opening = coordinator.arrive("join", new JoinArrival("b0", "p0", Map.of(), Set.of()));

        clock.advance(Duration.ofMillis(437));

        // The second branch meets quorum 2 of 2 and settles the join at the clock's new instant.
        var proceed = coordinator.arrive("join", new JoinArrival("b1", "p1", Map.of(), Set.of()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(JoinDecision.Proceed.class, proceed);

        // A branch that parked before the join settled observes Discarded(LATE), not Proceed --
        // completeWaiters() delivers that to every waiter on success (see its javadoc); only the
        // settling call's own return value carries the verdict. Proof that parking is what actually
        // happened here, rather than the future silently never completing.
        assertInstanceOf(JoinDecision.Discarded.class, opening.toCompletableFuture().get(5, TimeUnit.SECONDS));

        var satisfied = onlyEventOfType(monitor, ExecutionEventType.JOIN_SATISFIED);
        assertEquals(Duration.ofMillis(437), satisfied.joinWaitDuration(),
                "joinWaitDuration must be the exact clock delta between the opening arrival and the "
                        + "settling one, not zero and not an unrelated non-null value");
    }

    @Test
    void joinFailedReportsExactlyTheElapsedTimeBetweenTheOpeningAndTheSettlingFailure() throws Exception {
        var monitor = new ExecutionMonitor();
        var clock = new MutableClock(EPOCH);
        // Three branches, quorum 2: one arrival keeps the join open (2 of the other 2 remain
        // reachable, so quorum is still reachable), then two failures make it unreachable.
        var coordinator = coordinator(monitor, clock, quorumTwoOfThree());

        // Parks until the join settles (see arrive()'s javadoc); resolved and checked once the two
        // failures below have actually settled it, below.
        var opening = coordinator.arrive("join", new JoinArrival("b0", "p0", Map.of(), Set.of()));

        clock.advance(Duration.ofSeconds(9));

        var stillWaiting = coordinator.fail("join", "b1", new IllegalStateException("branch exploded"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(JoinDecision.Wait.class, stillWaiting,
                "one arrival and one failure out of three still leaves quorum 2 reachable "
                        + "(b0 arrived, b2 outstanding) -- this call must not settle the join, or the "
                        + "clock advance below would land on the wrong side of settlement");

        // No further clock advance: this is the call that makes quorum unreachable and settles.
        var failed = coordinator.fail("join", "b2", new IllegalStateException("branch exploded too"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(JoinDecision.Failed.class, failed);

        // A branch that parked before the join failed observes the failure itself, thrown out of its
        // future -- completeWaiters(failure) fails every waiter exceptionally (see its javadoc).
        // Proof that parking is what actually happened here, rather than the future silently never
        // completing.
        var openingOutcome = assertThrows(ExecutionException.class,
                () -> opening.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(JoinFailureException.class, openingOutcome.getCause());

        var failedEvent = onlyEventOfType(monitor, ExecutionEventType.JOIN_FAILED);
        assertEquals(Duration.ofSeconds(9), failedEvent.joinWaitDuration(),
                "joinWaitDuration must measure from the opening arrival to the settling failure, "
                        + "not from the most recent branch report");
    }

    private JoinCoordinator coordinator(ExecutionMonitor monitor, MutableClock clock, JoinSpec spec) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        JoinStore store = new InMemoryJoinStore();
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec), clock);
    }

    private static JoinSpec quorumTwoOfTwo() {
        return new JoinSpec("join", List.of("b0", "b1"), 2, null);
    }

    private static JoinSpec quorumTwoOfThree() {
        return new JoinSpec("join", List.of("b0", "b1", "b2"), 2, null);
    }

    private static ai.ravenroot.api.application.ExecutionEvent onlyEventOfType(ExecutionMonitor monitor,
                                                                               ExecutionEventType type) {
        var matches = monitor.eventsAfter(0).stream().filter(event -> event.type() == type).toList();
        assertEquals(1, matches.size(), () -> "expected exactly one " + type + " event, saw " + matches.size());
        return matches.getFirst();
    }
}
