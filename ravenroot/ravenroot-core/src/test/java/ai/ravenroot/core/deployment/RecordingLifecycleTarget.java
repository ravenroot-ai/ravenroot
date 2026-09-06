package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A deployment runtime that records what was asked of it and enforces the port's own idempotence
 * rule, so a test can prove an action was not performed twice rather than assuming it.
 *
 * <h2>Two counters, not one</h2>
 * <p>{@link #calls()} is every invocation the port received, and {@link #effects()} is only those
 * that actually did something. The gap between them is the whole point: recovery re-drives an
 * interrupted command, so the port is legitimately called again, and what must not happen twice is
 * the <em>effect</em>. A fixture that recorded only invocations could not tell a correct re-drive
 * from a duplicated action, and a fixture that recorded only effects could not show that the
 * re-drive happened at all.</p>
 *
 * <h2>The engine is shared and this class cannot reach it</h2>
 * <p>Every target built over one {@link SharedEngine} answers for a different deployment, exactly as
 * several deployments hosted on one actor system do. Nothing here can drain or close it, because
 * {@link DeploymentLifecycleTarget} has no method that means either — which is the structural form
 * ADR 0038 D9 gives the constraint, and what the sibling-isolation test reads off it.</p>
 */
class RecordingLifecycleTarget implements DeploymentLifecycleTarget {

    /** Something several deployments share, standing in for the engine and its actor system. */
    static final class SharedEngine {
        private boolean drained;
        private boolean closed;

        /** Whether anything drained the shared engine. Must stay false for every lifecycle command. */
        boolean drained() { return drained; }

        /** Whether anything closed the shared engine. Must stay false for every lifecycle command. */
        boolean closed() { return closed; }

        /**
         * The only way to drain the shared engine, deliberately unreachable from the lifecycle port.
         * Present so that "nothing reached it" is an assertion about a real, callable operation
         * rather than about an operation that does not exist.
         */
        void drainEverything() { drained = true; }

        /** The only way to close the shared engine, deliberately unreachable from the lifecycle port. */
        void closeEverything() { closed = true; }
    }

    private final SharedEngine engine;
    private final List<String> calls = new ArrayList<>();
    private final List<String> effects = new ArrayList<>();
    private final Set<String> applied = new LinkedHashSet<>();

    private ObservedKind state = ObservedKind.COLD;
    private Long activeVersion;
    private long inFlight;
    private RuntimeException nextFailure;
    private boolean drainFinishes = true;

    RecordingLifecycleTarget(SharedEngine engine) {
        this.engine = engine;
    }

    /** Every invocation this port received, in order, as {@code operation@generation}. */
    List<String> calls() { return List.copyOf(calls); }

    /** Only the invocations that changed something, in order, as {@code operation@generation}. */
    List<String> effects() { return List.copyOf(effects); }

    /** The engine this target shares with its siblings, for isolation assertions. */
    SharedEngine engine() { return engine; }

    /** How many admitted units of work this target reports as unfinished. */
    void inFlight(long count) { inFlight = count; }

    /** Makes the next effectful operation fail, so a classified failure outcome can be asserted. */
    void failNext(RuntimeException failure) { nextFailure = failure; }

    /** Makes a drain report that its bound elapsed with work still running. */
    void drainOutlivesItsBound() { drainFinishes = false; }

    @Override
    public CompletionStage<Void> start(long graphVersion, long deploymentGeneration) {
        return effect("start:" + graphVersion, deploymentGeneration, () -> {
            state = ObservedKind.READY;
            activeVersion = graphVersion;
        });
    }

    @Override
    public CompletionStage<Void> closeAdmission(long deploymentGeneration) {
        return effect("closeAdmission", deploymentGeneration, () -> {
            if (activeVersion != null) state = ObservedKind.PAUSED;
        });
    }

    @Override
    public CompletionStage<Void> openAdmission(long deploymentGeneration) {
        return effect("openAdmission", deploymentGeneration, () -> {
            if (activeVersion != null) state = ObservedKind.READY;
        });
    }

    @Override
    public CompletionStage<Void> barrier(long deploymentGeneration) {
        return effect("barrier", deploymentGeneration, () -> inFlight = 0);
    }

    @Override
    public CompletionStage<Boolean> drain(Duration bound, long deploymentGeneration) {
        String operation = "drain";
        calls.add(operation + "@" + deploymentGeneration);
        if (!applied.add(operation + "@" + deploymentGeneration)) {
            return CompletableFuture.completedFuture(drainFinishes);
        }
        if (raise()) return CompletableFuture.failedFuture(consumeFailure());
        effects.add(operation + "@" + deploymentGeneration);
        if (drainFinishes) {
            inFlight = 0;
            if (activeVersion != null) state = ObservedKind.DRAINED;
        }
        return CompletableFuture.completedFuture(drainFinishes);
    }

    @Override
    public CompletionStage<Void> terminateDomain(long deploymentGeneration) {
        return effect("terminateDomain", deploymentGeneration, () -> {
            state = ObservedKind.STOPPED;
            activeVersion = null;
            inFlight = 0;
        });
    }

    @Override
    public CompletionStage<Reading> observe() {
        return CompletableFuture.completedFuture(new Reading(state, activeVersion, inFlight));
    }

    private CompletionStage<Void> effect(String operation, long generation, Runnable body) {
        calls.add(operation + "@" + generation);
        // The port's contract: applying the same operation twice at one generation has the effect of
        // applying it once. Recorded rather than merely honoured, so a test can prove the second call
        // reached the runtime and still did nothing.
        if (!applied.add(operation + "@" + generation)) return CompletableFuture.completedFuture(null);
        if (raise()) return CompletableFuture.failedFuture(consumeFailure());
        effects.add(operation + "@" + generation);
        body.run();
        return CompletableFuture.completedFuture(null);
    }

    private boolean raise() { return nextFailure != null; }

    private RuntimeException consumeFailure() {
        RuntimeException failure = nextFailure;
        nextFailure = null;
        return failure;
    }
}
