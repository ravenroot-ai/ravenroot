package ai.ravenroot.api.deployment;

/**
 * What {@link TrustedIngress#offerDurably} did with an offered event, distinguishing every outcome an
 * external source needs to act on differently (PERS-INGRESS).
 *
 * <h2>Single-pod durable, and that is the whole of the claim</h2>
 * <p>{@link DurablyCommitted} and {@link Duplicate} are true within one process holding one
 * {@link ai.ravenroot.api.persistence.ExecutionStore}. They are <b>not</b> a cross-replica guarantee.
 * Two pods pointed at physically separate stores are not ordered against each other at all, and
 * {@code recordInboxDelivery}'s first-time/duplicate answer is per-store: a consumer author who reads
 * "durably committed" and deploys two replicas without shared, fenced ownership gets at-most-once
 * <em>per pod</em>, which is at-least-once <em>overall</em> — silently, because nothing here can see
 * the other pod. Closing that gap is distributed ownership with lease and fencing across replicas
 * (PERS-08) and removing the single-replica constraint on top of it (PLAT-03). Until one of
 * those lands, run one replica per deployment, or accept at-least-once and keep effects themselves
 * idempotent.</p>
 *
 * <h2>Only a durable outcome may advance an external ack, offset or checkpoint</h2>
 * <p>{@link #acknowledgeable()} is {@code true} only for {@link DurablyCommitted} and
 * {@link Duplicate} — both mean the store has already recorded this event, under this key, before this
 * call returned. {@link VolatileCustody} is exactly what {@code TrustedIngress#offer}'s
 * {@code IngressDisposition#ACCEPTED} has always meant: in-memory only, gone if the process dies. An
 * adapter that acks its broker on a non-acknowledgeable receipt reintroduces the loss window this
 * type exists to close.</p>
 */
public sealed interface IngressReceipt {
/**
 * Whether an external ack, offset or checkpoint may advance past the event this receipt answers for.
 * @return whether the external source may safely advance its ack, offset, or checkpoint.
 */
    boolean acknowledgeable();

/**
 * The event was not admitted at all. See {@code reason} for which of {@link IngressDisposition}'s refusals.
 * @param reason bounded refusal explanation for the source operator.
 */
    record Refused(String reason) implements IngressReceipt {
        @Override
        public boolean acknowledgeable() {
            return false;
        }
    }

    /**
     * Admitted into the deployment's in-memory buffer only — no durable store is configured for this
     * deployment. Identical in substance to today's {@code IngressDisposition#ACCEPTED}; carried as
     * its own receipt case so a caller of {@code offerDurably} never has to also inspect a disposition
     * to learn it got the weaker guarantee.
     */
    record VolatileCustody() implements IngressReceipt {
        @Override
        public boolean acknowledgeable() {
            return false;
        }
    }

    /**
     * Recorded durably, for the first time, under {@code idempotentKey}, before this call returned.
     * See the type Javadoc for exactly what "durably" does and does not span.
 * @param idempotentKey durable source key recorded with the first accepted event.
     */
    record DurablyCommitted(String idempotentKey) implements IngressReceipt {
        @Override
        public boolean acknowledgeable() {
            return true;
        }
    }

    /**
     * {@code idempotentKey} was already durably recorded by an earlier call — this offer changed
     * nothing and started no second traversal. Still {@link #acknowledgeable()}: the store's answer
     * is unchanged by how many times the same key arrives, which is the property that makes a source's
     * retry-after-restart safe rather than merely harmless.
 * @param idempotentKey already-recorded durable source key.
     */
    record Duplicate(String idempotentKey) implements IngressReceipt {
        @Override
        public boolean acknowledgeable() {
            return true;
        }
    }

    /**
     * The durable commit itself is unknown, not refused. The in-memory admission and the store write
     * are two separate operations against two separate systems, so a failure between them (the store
     * call did not return before the caller needs an answer, for instance) cannot honestly be reported
     * as either success or failure. {@code acknowledgeable()} is {@code false} on purpose: an ambiguous
     * receipt must be <em>reconciled</em>, not advanced past. Reconciliation is re-offering the same
     * {@code idempotentKey} — {@link Duplicate} means it landed, a fresh {@link DurablyCommitted} or
     * another {@link Ambiguous} means it did not (yet).
 * @param idempotentKey source key whose durable commit cannot yet be determined.
 * @param reason bounded diagnostic explaining why reconciliation is required.
     */
    record Ambiguous(String idempotentKey, String reason) implements IngressReceipt {
        @Override
        public boolean acknowledgeable() {
            return false;
        }
    }
}
