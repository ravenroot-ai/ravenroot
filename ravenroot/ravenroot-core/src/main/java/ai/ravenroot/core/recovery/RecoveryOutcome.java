package ai.ravenroot.core.recovery;

import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.persistence.ExecutionKey;

import java.util.UUID;

/**
 * What one recovery sweep decided about one claimed work item.
 *
 * <p>Sealed so a later kind cannot be added without every consumer being told, and returned rather
 * than only logged so the decision is assertable by a test and reportable on the operational surface
 * without a new port read.</p>
 */
public sealed interface RecoveryOutcome {

    ExecutionKey key();

    UUID workItemId();

    /**
     * The attempt had provably not started — it was still {@code SCHEDULED}, and the runtime persists
     * {@code RUNNING} before the engine send — so it was dispatched again with no ambiguity to
     * resolve. The {@code RUNNING} transition was committed under the fence before the send.
     */
    record Dispatched(ExecutionKey key, UUID workItemId, UUID attemptId) implements RecoveryOutcome {
    }

    /**
     * The attempt was ambiguous and its node instance declared the effect repeatable, so the same
     * attempt was sent again under its own id as the idempotency key.
     */
    record ReDispatched(ExecutionKey key, UUID workItemId, UUID attemptId) implements RecoveryOutcome {
    }

    /** A settled durable handler was handed to its registered re-entry dispatcher and acknowledged. */
    record HandlerDispatched(ExecutionKey key, UUID workItemId, UUID traversalId)
            implements RecoveryOutcome {
    }

    /**
     * The attempt was ambiguous and nothing authorised repeating it, so it was parked and
     * acknowledged. It has left the claim loop and waits for a human decision about the past.
     */
    record Parked(ExecutionKey key, UUID workItemId, UUID attemptId, AttemptRepeatability declaration,
                  String cause) implements RecoveryOutcome {
    }

    /**
     * The item was claimable and safe to act on, but no dispatcher could send it. Nothing was
     * written and nothing was acknowledged, so the item stays pending and will be reclaimed.
     *
     * <p>This is the ordinary outcome while graph definitions remain unstorable. It is deliberately
     * not an error: the alternative — acknowledging work nobody executed — is the silent loss this
     * recovery path prevents.</p>
     */
    record Deferred(ExecutionKey key, UUID workItemId, String reason) implements RecoveryOutcome {
    }

    /**
     * The claim referred to work that no longer needs doing — the instance, invocation or attempt is
     * gone, or the attempt already reached a terminal state or a park. Acknowledged so it stops being
     * redelivered.
     */
    record Stale(ExecutionKey key, UUID workItemId, String reason) implements RecoveryOutcome {
    }
}
