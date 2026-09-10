package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ExecutionStore;

import java.time.Duration;
import java.util.Objects;

/**
 * The two facts a process needs before it can hold a lease: who it is, and for how long it claims.
 *
 * <h2>Why one type rather than two parameters</h2>
 * <p>They are one decision. A lease is a claim by a named worker for a bounded time, and a
 * composition that supplied a worker identity without a ttl — or the reverse — would be describing
 * half of an ownership. Carrying them together also stops the application's terminal constructor
 * growing a parameter every time this seam gains one.</p>
 *
 * <h2>Both values used to be literals</h2>
 * <p>The identity was a random UUID minted in a field initializer and the ttl was a
 * {@code Duration.ofSeconds(30)} beside it. Neither could be seen or changed from outside the
 * process, which is workable while there is exactly one process and stops being workable the moment
 * an operator has to answer "which replica holds this, and when does that claim lapse?" against a
 * deployment of several. {@link #defaults()} reproduces both literals exactly, so an embedder that
 * composes nothing keeps today's behaviour.</p>
 */
public record ExecutionOwnership(WorkerIdentity identity, Duration leaseTtl) {

    /**
     * The ttl this codebase has always used: comfortably longer than a renewal period, and short
     * enough that a crashed worker's instances become recoverable promptly. Kept as the default
     * rather than re-derived, because changing it silently is exactly what making it configurable
     * must not do.
     */
    public static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    public ExecutionOwnership {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }

    /** The unnamed-replica runtime identity and the historical ttl. */
    public static ExecutionOwnership defaults() {
        return new ExecutionOwnership(WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME),
                DEFAULT_LEASE_TTL);
    }

    /** The worker id handed to the store, already rendered. */
    public String workerId() {
        return identity.value();
    }

    /**
     * Refuses a ttl the composed store would refuse anyway, at construction instead of at the first
     * claim.
     *
     * <p>This is a construction invariant, not the operator's diagnostic: it knows no environment
     * variable name and deliberately says nothing about one. The composition root checks the same
     * bound first, against the variable the operator actually set, so a misconfiguration is reported
     * in the operator's own terms; this is the net for an embedder that builds the record directly.
     * A store above its own {@code maxLeaseTtl()} fails every claim with an invalid-request, which
     * would present as an application that starts cleanly and then cannot take a single lease.</p>
     *
     * @param store the composed store, or {@code null} when none is composed and no bound is
     *              published to check against.
     */
    public void requireCompatible(ExecutionStore store) {
        if (store == null) {
            return;
        }
        if (leaseTtl.compareTo(store.maxLeaseTtl()) > 0) {
            throw new IllegalArgumentException("execution lease ttl " + leaseTtl
                    + " exceeds the store's published maximum " + store.maxLeaseTtl());
        }
        if (leaseTtl.compareTo(store.maxClockSkew()) <= 0) {
            // Not a taste bound. maxClockSkew is how far two hosts' clocks may disagree, so a ttl at
            // or below it can be judged expired by a peer while the holder still believes it live —
            // which is the one thing a lease exists to prevent. A larger margin than "strictly
            // greater" is tempting and would be policy: it would reject the shipped default under an
            // adapter that declared a wider skew, turning a sanity check into a limit this class
            // never had the authority to set.
            throw new IllegalArgumentException("execution lease ttl " + leaseTtl
                    + " must be longer than the store's declared clock-skew budget "
                    + store.maxClockSkew());
        }
    }
}
