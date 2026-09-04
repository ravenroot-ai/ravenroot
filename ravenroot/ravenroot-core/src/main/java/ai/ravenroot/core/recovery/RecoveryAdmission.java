package ai.ravenroot.core.recovery;

import java.util.Objects;

/**
 * Whether recovery may act on one claimed item's execution, and when it may not, whether waiting
 * could change the answer.
 *
 * <h2>Why a refusal is not one thing</h2>
 * <p>The two ways an execution is refused call for opposite dispositions, and a boolean collapses
 * them into the wrong one. A store that is briefly unreachable will answer differently on the next
 * sweep, so waiting is exactly right and consuming a delivery budget during a store outage would
 * park healthy work en masse the moment the store came back. A pinned document that was never stored
 * will refuse identically forever, so waiting repairs nothing on its own — only a redeployment does,
 * and the item it strands meanwhile is an effect that already happened whose outcome nobody knows.
 * Withholding that one indefinitely is worse than the park it replaced, because the park at least
 * put the decision in front of a human.</p>
 *
 * <p>So a deterministic refusal is withheld for a bounded number of deliveries — a grace period in
 * which an operator can correct the deployment — and then parked with a cause naming the deployment
 * fault rather than reporting the attempt as though its author had declared nothing. A retryable
 * refusal is withheld for as long as it lasts.</p>
 *
 * @param disposition what this deployment may do with the item now.
 * @param detail bounded, operator-safe explanation of a refusal; empty when admitted.
 */
public record RecoveryAdmission(Disposition disposition, String detail) {

    /** Rejects an admission whose refusal explains nothing. */
    public RecoveryAdmission {
        Objects.requireNonNull(disposition, "disposition");
        detail = detail == null ? "" : detail;
        if (disposition != Disposition.ADMITTED && detail.isBlank()) {
            throw new IllegalArgumentException("a withheld admission must carry a diagnosis");
        }
    }

    /** What recovery may do with the item on this pass. */
    public enum Disposition {

        /** The execution resolves here; the item proceeds to its ordinary decision. */
        ADMITTED,

        /**
         * The answer is not known yet and is expected to become known. Withheld without consuming
         * the delivery budget, because burning it during a store outage would park work whose only
         * fault was being outstanding at the wrong moment.
         */
        WITHHELD_RETRYABLE,

        /**
         * The execution will refuse identically until the deployment changes. Withheld for a bounded
         * number of deliveries and then parked, so an unrunnable execution reaches a human instead of
         * being redelivered forever.
         */
        WITHHELD_DETERMINISTIC
    }

    /**
     * Admits the item.
     *
     * @return an admission carrying no refusal.
     */
    public static RecoveryAdmission admitted() {
        return new RecoveryAdmission(Disposition.ADMITTED, "");
    }

    /**
     * Withholds the item until the condition clears, without bounding the wait.
     *
     * @param detail bounded, operator-safe explanation.
     * @return a retryable refusal.
     */
    public static RecoveryAdmission retryable(String detail) {
        return new RecoveryAdmission(Disposition.WITHHELD_RETRYABLE, detail);
    }

    /**
     * Withholds the item for a bounded number of deliveries, after which it parks.
     *
     * @param detail bounded, operator-safe explanation naming the deployment fault.
     * @return a deterministic refusal.
     */
    public static RecoveryAdmission deterministic(String detail) {
        return new RecoveryAdmission(Disposition.WITHHELD_DETERMINISTIC, detail);
    }

    /**
     * Whether the item may proceed to its ordinary decision.
     *
     * <p>Named for what it permits rather than mirroring {@link #admitted()}, which is the factory
     * that builds the permitting value; one name cannot be both without the reader having to
     * remember which is which at every call site.</p>
     *
     * @return {@code true} only for {@link Disposition#ADMITTED}.
     */
    public boolean proceeds() {
        return disposition == Disposition.ADMITTED;
    }

    /**
     * Whether waiting alone could change this answer.
     *
     * @return {@code true} only for {@link Disposition#WITHHELD_RETRYABLE}.
     */
    public boolean repairableByWaiting() {
        return disposition == Disposition.WITHHELD_RETRYABLE;
    }
}
