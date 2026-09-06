package ai.ravenroot.api.deployment.registry;

/**
 * Optimistic-concurrency expectation on the <em>deployment generation</em> (ADR 0038 D1, D10).
 *
 * <h2>Why a second expectation beside {@code RevisionExpectation}</h2>
 * <p>A deployment aggregate carries three monotone axes that are deliberately disjoint:
 * {@code revision} advances on <em>every</em> accepted mutation and is the compare-and-set token;
 * {@code fence} advances only when lease ownership moves; {@code generation} advances only when the
 * lifecycle intent moves. A caller that only pinned the revision would be pinning "nothing else has
 * been written", which a lease renewal from the current owner also violates — a correct, unrelated
 * write that has nothing to do with the lifecycle decision the caller made. Pinning the generation
 * as well expresses the question the caller actually asked: "advance the lifecycle from exactly the
 * generation I decided against".</p>
 *
 * <h2>Name</h2>
 * <p>This is the <em>deployment</em> generation. It is not
 * {@code ProcessInventoryEntry#lifecycleGeneration}, which counts authoritative status transitions of
 * one process instance and is already published with that meaning. The two are different axes on
 * different aggregates, and ADR 0038 D3 fixes {@code deploymentGeneration} as the name for this one
 * wherever it needs qualifying.</p>
 *
 * <h2>There is no {@code NotPresent}</h2>
 * <p>{@code RevisionExpectation.NotPresent} exists because instance creation must be exactly-once
 * against a row that may not exist. A generation only has meaning once the aggregate does, so the
 * absent case is already answered by {@link DeploymentRegistry.FailureReason.NotFound} and a third
 * member would only be a second way to spell it.</p>
 *
 * <h2>Violation is a conflict, not a new failure reason</h2>
 * <p>A registry that finds a different generation rejects with
 * {@link DeploymentRegistry.FailureReason.Conflict}, exactly as a revision mismatch does.
 * {@code FailureReason} is sealed and describes <em>store</em> faults; the client-facing distinction
 * between "someone else wrote" and "the lifecycle moved under you" belongs to
 * {@code DeploymentCommandOutcome.StaleGeneration}, which a coordinator produces from the record it
 * already holds. Adding a member here would break every exhaustive switch on {@code FailureReason}
 * to say something the layer above can already say.</p>
 */
public sealed interface GenerationExpectation {

    /** The caller accepts whatever generation it finds. The default for non-lifecycle mutations. */
    record Any() implements GenerationExpectation {
        private static final Any INSTANCE = new Any();
    }

    /**
     * The stored deployment generation must equal {@code generation} exactly.
     *
     * <p>Equality, never {@code >=}: a barrier is half-open (ADR 0038 D6), and an ordering test would
     * silently accept a command decided against a generation two lifecycle moves ago.</p>
     * @param generation the exact deployment generation this mutation was decided against.
     */
    record Exactly(long generation) implements GenerationExpectation {
        /** Rejects a negative generation, which no aggregate can ever hold. */
        public Exactly {
            if (generation < 0) throw new IllegalArgumentException("deployment generation cannot be negative");
        }
    }

    /**
     * Returns the shared expectation that pins no generation.
     * @return singleton expectation accepting any stored deployment generation.
     */
    static GenerationExpectation any() {
        return Any.INSTANCE;
    }

    /**
     * Creates an expectation for one exact stored deployment generation.
     * @param generation the exact deployment generation required for the mutation to apply.
     * @return exact deployment-generation expectation.
     */
    static GenerationExpectation exactly(long generation) {
        return new Exactly(generation);
    }
}
