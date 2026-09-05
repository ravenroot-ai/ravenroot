package ai.ravenroot.core.runtime;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Purges one tenant's expired execution results and leaves an audit record naming what happened --
 * the retention requirement wave 1 left open: {@link ExecutionStore#purgeExpiredExecutionResults}
 * deletes rows, and nothing about that deletion was written down anywhere a security reviewer could
 * later find.
 *
 * <h2>Why this composes two ports rather than living inside either one</h2>
 * <p>{@link ExecutionStore} is deliberately audit-agnostic -- {@link AuditTrail}'s own class Javadoc
 * states the two are "a different store... on purpose" -- and every persistence adapter must keep
 * working with no {@link AuditTrail} composed at all, exactly as it must keep working with no actor
 * framework in scope. Folding the append into an adapter such as {@code SqliteExecutionStore} would
 * make a SEC-13 concern a hard dependency of a PERS-02 port. This class is the place that holds both,
 * the same way {@code AuthorizedRavenrootApplication} is the place that appends an ACCESS fact beside
 * a read it performs rather than pushing that obligation into the store or the engine.</p>
 *
 * <h2>The tombstone shape</h2>
 * <p>Modelled on {@link AuditTrail#redact}, the one retention-adjacent operation in this codebase that
 * already appends a record naming what it removed: one {@link AuditCategory#ADMINISTRATION} fact --
 * the category {@link AuditCategory}'s own Javadoc names "e.g. retention" as its example -- naming the
 * tenant, the count purged and the operator who triggered the purge. Unlike {@code redact}, there is
 * no chain range to preserve in place: a purged execution-result row is gone outright rather than
 * replaced by a placeholder, so there is nothing here analogous to {@code redact}'s sequence-preserving
 * rewrite. What is preserved is the fact {@code redact}'s tombstone preserves all the same -- that a
 * legitimate removal happened, who caused it and how much was removed -- so a gap in the result table
 * is distinguishable from silent, unaccounted-for loss.</p>
 *
 * <h2>What this deliberately does not do</h2>
 * <p>Nothing in this codebase currently calls {@link ExecutionStore#purgeExpiredExecutionResults} in
 * production -- not a scheduler, not a CLI verb, not an HTTP route -- and the same is true of its two
 * siblings, {@link ExecutionStore#purgeExpiredIdempotencyRecords} and
 * {@link ExecutionStore#purgeExpiredProcessInstances}. Wiring an operator- or scheduler-facing trigger
 * is a separate, wider capability than "the audit record a result purge leaves behind", and is left to
 * whichever issue adds that trigger. This class is the ready-to-call, already-audited operation such a
 * trigger composes against, so the audit obligation is met the moment a caller exists rather than
 * discovered as a gap afterward.</p>
 */
public final class AuditedExecutionResultPurge {

    /** The action token this class writes to the trail, naming the store operation it wraps. */
    public static final String ACTION = "execution-result-purge";

    /** The resource type this class writes to the trail; the resource id is the purged tenant itself,
     * since a purge is tenant-wide rather than naming one execution. */
    public static final String RESOURCE_TYPE = "execution-result";

    private final ExecutionStore store;
    private final AuditTrail auditTrail;

    /**
     * Composes the purge with the trail it must leave a record in.
     *
     * @param store      the execution store whose expired results are purged.
     * @param auditTrail the durable trail the tombstone is appended to.
     */
    public AuditedExecutionResultPurge(ExecutionStore store, AuditTrail auditTrail) {
        this.store = Objects.requireNonNull(store, "store");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    /**
     * Purges {@code tenantId}'s expired execution results and appends the tombstone recording it.
     *
     * <p>The tombstone is appended after the purge itself settles, whether it succeeded or failed --
     * an attempted purge that the store refused is exactly the kind of fact this trail exists to keep,
     * not one to discard because nothing was removed. A failure is rethrown after being recorded, so a
     * caller still sees it.</p>
     *
     * @param tenantId the tenant whose expired results are purged.
     * @param operator the audit-stable identity that triggered this purge, e.g.
     *                 {@code SecurityContext.qualifiedIdentity()}.
     * @return the number of results removed.
     */
    public long purge(String tenantId, String operator) {
        requireText(tenantId, "tenantId");
        requireText(operator, "operator");
        long purged;
        try {
            purged = await(store.purgeExpiredExecutionResults(tenantId));
        } catch (RuntimeException failure) {
            append(tenantId, operator, AuditOutcome.FAILED,
                    "purged=0:" + failure.getClass().getName());
            throw failure;
        }
        append(tenantId, operator, AuditOutcome.ALLOWED, "purged=" + purged);
        return purged;
    }

    private void append(String tenantId, String operator, AuditOutcome outcome, String detail) {
        auditTrail.append(AuditEnvelope.of(tenantId, operator, AuditCategory.ADMINISTRATION, ACTION,
                RESOURCE_TYPE, tenantId, outcome, detail, UUID.randomUUID().toString(), Instant.now()));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
