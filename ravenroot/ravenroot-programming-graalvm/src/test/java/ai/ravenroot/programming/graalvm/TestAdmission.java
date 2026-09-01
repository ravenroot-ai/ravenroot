package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ProgramAdmission} over a fixed artifact, for the tests in this module.
 *
 * <p><b>Why a fixed admission is legitimate here and would not be in core.</b> These tests exercise
 * the sandbox adapter -- wire protocol, deadlines, cleanup, cancellation. The property they assert is
 * never "a revoked artifact is refused"; that property belongs to the registry's admission and is
 * asserted against the real {@code InMemoryArtifactRegistry} in
 * {@code ProgramAdmissionTocTouTest}. Substituting a fixed admission there would author the very
 * evidence under test, which would make the assertion vacuous. Here the admission is plumbing: it exists
 * so {@code execute} has something to redeem.
 *
 * <p>{@link #redemptions} is what keeps this honest. The adapter's contract is that it redeems
 * exactly once, at the last moment before the source reaches the worker, so a test can assert the
 * count rather than trust the comment.
 */
final class TestAdmission implements ProgramAdmission {
    private final GeneratedArtifact artifact;
    final AtomicInteger redemptions = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    volatile Runnable revocation;

    private TestAdmission(GeneratedArtifact artifact) {
        this.artifact = artifact;
    }

    static TestAdmission of(GeneratedArtifact artifact) {
        return new TestAdmission(artifact);
    }

    @Override
    public String artifactId() {
        return artifact.id();
    }

    @Override
    public GeneratedArtifact unverifiedSnapshot() {
        return artifact;
    }

    @Override
    public GeneratedArtifact redeem() {
        redemptions.incrementAndGet();
        return artifact;
    }

    @Override
    public void onRevoked(Runnable cancellation) {
        this.revocation = cancellation;
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }
}
