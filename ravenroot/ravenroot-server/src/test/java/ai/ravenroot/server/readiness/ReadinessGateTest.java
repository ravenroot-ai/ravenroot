package ai.ravenroot.server.readiness;

import ai.ravenroot.api.persistence.ExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAT-02 verification: each state must be reachable in a test, and each transition
 * observed rather than asserted. Every test here flips a live signal the gate reads and re-evaluates
 * -- none constructs a report directly and checks its shape, which would prove nothing about
 * whether the gate can actually detect the condition it claims to detect.
 *
 * <p>The one assertion that decides whether this class is real coverage or decoration: {@link
 * #reportsUnreadyWhenDraining()} and {@link #reportsUnreadyWhenTheStoreCheckFails()} both drive
 * {@code ready()} to {@code false}. A gate that could only ever be observed reporting {@code true}
 * would be exactly the kind of control this run has repeatedly found to be decoration.</p>
 */
class ReadinessGateTest {
    private ReadinessGate gate;

    @AfterEach
    void closeGate() {
        if (gate != null) {
            gate.close();
        }
    }

    @Test
    void reportsReadyWhenTheEngineIsRunningAndTheStoreCheckSucceeds() {
        gate = new ReadinessGate(() -> "RUNNING", StoreLivenessCheck.none(), List::of, ReadinessConfiguration.defaults());

        var report = gate.evaluate();

        assertTrue(report.ready());
        assertEquals(ReadinessState.READY, report.state());
    }

    @Test
    void reportsUnreadyWhenDraining() {
        // The engine-state supplier is a mutable reference under this test's control, not a fixed
        // constant -- the transition is observed, not merely constructed.
        var engineState = new AtomicReference<>("RUNNING");
        gate = new ReadinessGate(engineState::get, StoreLivenessCheck.none(), List::of,
                ReadinessConfiguration.defaults());

        assertTrue(gate.evaluate().ready(), "must start ready, or the transition below proves nothing");

        engineState.set("DRAINING");
        var report = gate.evaluate();

        assertFalse(report.ready());
        assertEquals(ReadinessState.DRAINING, report.state());
    }

    @Test
    void recoversToReadyWhenDrainingEnds() {
        var engineState = new AtomicReference<>("DRAINING");
        gate = new ReadinessGate(engineState::get, StoreLivenessCheck.none(), List::of,
                ReadinessConfiguration.defaults());

        assertFalse(gate.evaluate().ready());

        engineState.set("RUNNING");
        var report = gate.evaluate();

        assertTrue(report.ready(), "the gate must recover, not just be capable of degrading");
        assertEquals(ReadinessState.READY, report.state());
    }

    @Test
    void reportsUnreadyWhenTheStoreCheckFails() {
        var storeHealthy = new AtomicBoolean(true);
        StoreLivenessCheck flakyStore = () -> {
            if (!storeHealthy.get()) {
                throw new java.io.IOException("injected store failure");
            }
        };
        gate = new ReadinessGate(() -> "RUNNING", flakyStore, List::of, ReadinessConfiguration.defaults());

        assertTrue(gate.evaluate().ready(), "must start ready, or the transition below proves nothing");

        storeHealthy.set(false);
        var report = gate.evaluate();

        assertFalse(report.ready());
        assertEquals(ReadinessState.STORE_DEGRADED, report.state());

        storeHealthy.set(true);
        assertTrue(gate.evaluate().ready(), "must recover once the injected failure is cleared");
    }

    @Test
    void aStoreCheckThatHangsPastTheTimeoutDegradesReadinessRatherThanBlockingTheProbeForever() {
        var release = new java.util.concurrent.CountDownLatch(1);
        StoreLivenessCheck hangingStore = () -> release.await(10, java.util.concurrent.TimeUnit.SECONDS);
        gate = new ReadinessGate(() -> "RUNNING", hangingStore, List::of,
                new ReadinessConfiguration(Duration.ofMillis(100), Duration.ofSeconds(6), Duration.ofSeconds(10)));

        long start = System.nanoTime();
        var report = gate.evaluate();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(report.ready());
        assertEquals(ReadinessState.STORE_DEGRADED, report.state());
        assertTrue(elapsedMillis < 5_000, "the timeout, not the hang, must bound the probe: took "
                + elapsedMillis + "ms against a 100ms configured timeout and a 10s hang");
        release.countDown();
    }

    @Test
    void thousandsOfConcurrentCallersShareOneOutstandingProbeAndRecoverWhenItCompletes() throws Exception {
        var release = new CountDownLatch(1);
        var invocations = new AtomicInteger();
        StoreLivenessCheck hangingStore = () -> {
            invocations.incrementAndGet();
            release.await();
        };
        gate = new ReadinessGate(() -> "RUNNING", hangingStore, List::of,
                new ReadinessConfiguration(Duration.ofMillis(25), Duration.ofSeconds(6), Duration.ofSeconds(10)));

        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reports = new ArrayList<java.util.concurrent.Future<ReadinessReport>>();
            for (int index = 0; index < 2_000; index++) {
                reports.add(callers.submit(gate::evaluate));
            }
            for (var report : reports) {
                assertEquals(ReadinessState.STORE_DEGRADED, report.get(10, TimeUnit.SECONDS).state());
            }
        }

        assertEquals(1, invocations.get(), "a never-completing store must not grow an executor queue");
        release.countDown();
        assertTrue(gate.evaluate().ready(), "completion must permit readiness to recover");
    }

    @Test
    void executionStoreCheckSharesOneStageUntilCompletionThenStartsAFreshProbe() throws Exception {
        var invocations = new AtomicInteger();
        var currentStage = new AtomicReference<>(new CompletableFuture<Instant>());
        ExecutionStore store = (ExecutionStore) Proxy.newProxyInstance(
                ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("forgottenBefore")) {
                        invocations.incrementAndGet();
                        return currentStage.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StoreLivenessCheck check = StoreLivenessCheck.executionStore(store);

        Thread interruptedWaiter = Thread.ofVirtual().start(() -> {
            try {
                check.check();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        });
        while (invocations.get() == 0) {
            Thread.onSpinWait();
        }
        interruptedWaiter.interrupt();
        interruptedWaiter.join();

        var secondWaiterDone = new CountDownLatch(1);
        Thread secondWaiter = Thread.ofVirtual().start(() -> {
            try {
                check.check();
            } catch (Exception failed) {
                throw new AssertionError(failed);
            } finally {
                secondWaiterDone.countDown();
            }
        });
        assertFalse(secondWaiterDone.await(50, TimeUnit.MILLISECONDS));
        assertEquals(1, invocations.get(), "interrupting one waiter must not discard the shared store stage");
        currentStage.get().complete(Instant.EPOCH);
        secondWaiter.join();

        currentStage.set(CompletableFuture.completedFuture(Instant.EPOCH));
        check.check();
        assertEquals(2, invocations.get(), "a completed stage must not become stale readiness state");
    }

    @Test
    void drainingTakesPriorityOverAStoreFailureWhenBothHoldAtOnce() {
        StoreLivenessCheck alwaysFails = () -> {
            throw new IllegalStateException("store is down");
        };
        gate = new ReadinessGate(() -> "DRAINING", alwaysFails, List::of, ReadinessConfiguration.defaults());

        var report = gate.evaluate();

        assertEquals(ReadinessState.DRAINING, report.state(),
                "draining is the more specific, more actionable fact and must win the priority order");
    }

    @Test
    void dependenciesAreReportedButNeverGateReadiness() {
        gate = new ReadinessGate(() -> "RUNNING", StoreLivenessCheck.none(),
                () -> List.of(new DependencyStatus("program-runtime", false, "disabled")),
                ReadinessConfiguration.defaults());

        var report = gate.evaluate();

        assertTrue(report.ready(), "an optional dependency being down must not gate readiness");
        assertEquals(1, report.dependencies().size());
        assertFalse(report.dependencies().get(0).up());
    }
}
