package ai.ravenroot.testkit.api;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance suite for {@link ProgramRuntime} adapters (SEC-12).
 *
 * <p><b>Why this exists.</b> ADR 0020 moved execution authorization into
 * {@link ProgramAdmission#redeem()}, so that check and acquisition are one act. The compiler enforces
 * half of that: an adapter must <em>accept</em> a {@code ProgramAdmission}. Nothing in the type system
 * enforces the other half. An adapter can take the handle, read
 * {@link ProgramAdmission#unverifiedSnapshot()}, execute that, compile cleanly, and reintroduce the
 * exact TOCTOU vulnerability that redemption closes — silently, inside somebody else's module. That
 * unenforced half is the whole
 * property, so it needs a conformance suite rather than a paragraph of documentation.
 *
 * <p><b>How the obligation is made checkable.</b> The admission handed to the adapter is rigged: its
 * {@code unverifiedSnapshot()} and its {@code redeem()} return <em>different artifacts</em>. An adapter
 * that honours the contract can only ever have seen the redeemed one, so
 * {@link #executesTheRedeemedArtifactAndNotTheSnapshot()} distinguishes conformance from its absence
 * by observation rather than by inspection. {@link #executeSubject(ProgramRuntime, ProgramAdmission)}
 * is the single hook an adapter implements to report which artifact it actually used.
 *
 * <p><b>The suite discriminates, and that was verified rather than assumed.</b> Pointing the shipped
 * {@code GraalVmProgramRuntime} at {@code admission::unverifiedSnapshot} instead of
 * {@code admission::redeem} -- a one-token change that compiles and passes every other test in its
 * module -- turns {@link #executesTheRedeemedArtifactAndNotTheSnapshot()} and
 * {@link #doesNotExecuteWhenRedemptionIsRefused()} red. Applied to a scratch copy and reverted; a
 * permanent suite should not sabotage itself on every run.
 *
 * <p>None of these tests measures time, spawns load, or depends on scheduling. The interleavings they
 * assert are sequences of ordinary method calls, because redemption is a call the adapter makes.
 */
public abstract class ProgramRuntimeContract {
    /** The adapter under test. A fresh instance per call is fine. */
    protected abstract ProgramRuntime runtime();

    /**
     * Drives {@code runtime.execute(admission, request)} to completion and reports which artifact the
     * adapter actually used to execute — the one it obtained from the admission.
     *
     * <p>Implementations must not answer from {@code admission.unverifiedSnapshot()}: that would make
     * the suite agree with the adapter merely because both consulted the same object, which is the
     * vacuity this suite exists to detect. Answer from what the adapter really handed to its sandbox
     * (the bytes it wrote, the source it compiled), or from what {@code redeem()} returned to it.
     *
     * @return the artifact the adapter executed, or {@code null} if it executed nothing
     * @throws Exception when the adapter refuses; the suite asserts on that
     */
    protected abstract GeneratedArtifact executeSubject(ProgramRuntime runtime, ProgramAdmission admission)
            throws Exception;

    /**
     * An artifact this adapter is able to execute, and a second, DIFFERENT one it is equally able to
     * execute. They must be distinguishable by {@link GeneratedArtifact#id()} — the suite uses the
     * first as the redeemed artifact and the second as the decoy snapshot.
     */
    protected abstract GeneratedArtifact executableArtifact(String marker);

    /**
     * The obligation the compiler cannot express. An adapter reading
     * {@code unverifiedSnapshot()} instead of redeeming compiles perfectly and fails here.
     */
    @Test
    final void executesTheRedeemedArtifactAndNotTheSnapshot() throws Exception {
        GeneratedArtifact redeemed = executableArtifact("redeemed");
        GeneratedArtifact decoy = executableArtifact("snapshot-decoy");
        var admission = new RiggedAdmission(decoy, redeemed, false);

        GeneratedArtifact used = executeSubject(runtime(), admission);

        assertNotNull(used, "the adapter executed nothing for an admission that redeems cleanly");
        assertEquals(redeemed.id(), used.id(),
                "the adapter executed the artifact from unverifiedSnapshot() rather than the one "
                        + "redeem() returned. That compiles, and it reintroduces the TOCTOU ADR 0020 "
                        + "closed: the snapshot is stale by construction and carries no authorization");
        assertTrue(admission.redemptions.get() >= 1, "an adapter must redeem before executing");
    }

    /**
     * A refused redemption must stop the execution, not be logged and stepped over. This is the
     * revocation path: by the time an adapter redeems, the artifact may have been retired.
     */
    @Test
    final void doesNotExecuteWhenRedemptionIsRefused() {
        var admission = new RiggedAdmission(executableArtifact("decoy"), executableArtifact("never"), true);

        Throwable refusal = assertThrows(Throwable.class, () -> executeSubject(runtime(), admission),
                "a refused redemption must surface as a failure, not a successful execution");

        assertTrue(unwrap(refusal) instanceof SecurityException,
                "a refused redemption must surface as the SecurityException redeem() threw, but "
                        + "surfaced as " + unwrap(refusal));
        assertTrue(admission.redemptions.get() >= 1, "the adapter must have attempted redemption");
    }

    /**
     * Registering a cancellation is what lets a revocation reach an execution that was already
     * admitted. ADR 0020 is explicit that redemption alone leaves the tail of the window open.
     */
    @Test
    final void registersACancellationSoRevocationCanReachAnInFlightExecution() throws Exception {
        var admission = new RiggedAdmission(executableArtifact("decoy"), executableArtifact("run"), false);

        executeSubject(runtime(), admission);

        assertNotNull(admission.cancellation.get(),
                "the adapter never called onRevoked(...), so retiring this artifact mid-execution "
                        + "would have had nothing to cancel and the source would have run to completion");
    }

    /** A completed execution must release its registration, or the registry leaks one per call. */
    @Test
    final void closesTheAdmissionWhenTheExecutionCompletes() throws Exception {
        var admission = new RiggedAdmission(executableArtifact("decoy"), executableArtifact("run"), false);

        executeSubject(runtime(), admission);

        assertTrue(admission.closes.get() >= 1,
                "the adapter did not close the admission, so its revocation registration outlives the "
                        + "execution it belonged to");
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * An admission whose snapshot and redemption deliberately disagree, so that "which one did the
     * adapter use" is an observation rather than an inspection.
     */
    protected static final class RiggedAdmission implements ProgramAdmission {
        private final GeneratedArtifact snapshot;
        private final GeneratedArtifact redeemed;
        private final boolean refuse;
        public final AtomicInteger redemptions = new AtomicInteger();
        public final AtomicInteger closes = new AtomicInteger();
        public final AtomicReference<Runnable> cancellation = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        RiggedAdmission(GeneratedArtifact snapshot, GeneratedArtifact redeemed, boolean refuse) {
            this.snapshot = snapshot;
            this.redeemed = redeemed;
            this.refuse = refuse;
        }

        @Override
        public String artifactId() {
            return redeemed.id();
        }

        @Override
        public GeneratedArtifact unverifiedSnapshot() {
            return snapshot;
        }

        @Override
        public GeneratedArtifact redeem() {
            redemptions.incrementAndGet();
            if (refuse) {
                throw new SecurityException("Program artifact is not ACTIVE: " + redeemed.id() + " (RETIRED)");
            }
            return redeemed;
        }

        @Override
        public void onRevoked(Runnable toCancel) {
            cancellation.set(toCancel);
        }

        @Override
        public void close() {
            closed.set(true);
            closes.incrementAndGet();
        }
    }

    /** Convenience for adapters that need a request instance. */
    protected static ProgramRequest request(Object payload) {
        return new ProgramRequest(UUID.randomUUID(), "conformance", payload, Map.of("count", 1));
    }
}
