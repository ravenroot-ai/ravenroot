package ai.ravenroot.api.execution;

import ai.ravenroot.api.persistence.Retryability;

/**
 * Implemented by a failure that states its own retry classification, so the orchestrator does not
 * have to guess from a type name.
 *
 * <h2>Why the vocabulary is {@link Retryability} and not a second enum</h2>
 * <p>{@link Retryability} already answers exactly this question for the persistence port — "did this
 * apply, and is repeating it safe" — and a node's effect poses the same question about a different
 * target. A parallel enum would have the same four cases under different names, and every reader
 * would then have to learn which of the two spellings a given surface uses. Reusing it also means an
 * {@link ai.ravenroot.api.persistence.ExecutionStoreException} surfacing through a node needs no
 * translation: its failure's own classification is already the answer.</p>
 *
 * <h2>{@link Retryability#INDETERMINATE} is a refusal, not a retry</h2>
 * <p>It means the effect may have landed. Repeating it is precisely what ADR 0022 forbids doing
 * automatically, so the orchestration retry policy stops on it exactly as it stops on
 * {@link Retryability#DETERMINISTIC_REJECT}. It is not a park: a park is the disposition of an
 * outcome that was never <em>learned</em>, and a failure that reaches this interface has been learned
 * — it is a {@link Throwable} in hand.</p>
 */
public interface RetryClassified {

    /**
     * How this failure should be classified for orchestration retry purposes.
     *
     * <p>Never {@code null}. An implementation that cannot decide must return
     * {@link Retryability#INDETERMINATE} rather than {@code null}, because the caller's fail-closed
     * default for an unclassified failure is to refuse the retry, and returning {@code null} would
     * make an implementation's omission indistinguishable from a considered refusal.</p>
     *
     * @return the classification this failure asserts about itself
     */
    Retryability retryability();
}
