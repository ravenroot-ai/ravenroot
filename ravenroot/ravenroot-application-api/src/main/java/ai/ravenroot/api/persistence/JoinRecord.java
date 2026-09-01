package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The durable, correlated state of one fan-in join (CORE-03).
 *
 * <h2>Correlation, deliberately not payload</h2>
 * <p>This record says <em>which branches are settled and how</em>. It does not carry the arriving
 * payloads, and must not be extended to. A branch arrival's payload is the output of the upstream
 * node's invocation, which {@link ExecutionStore} already owns; storing it here as well would create
 * two records of the same bytes with no rule for reconciling them when they disagree.</p>
 *
 * <p>The payload therefore comes back with the <em>redelivery</em>, not from this store. Delivery is
 * at-least-once ({@link PendingWork}), so after a restart the upstream work item is redelivered and
 * re-presents its payload to the join; this record is what tells the join that the branch already
 * counted, so the re-presentation refreshes the payload without moving the quorum. Correlation is
 * the thing that cannot be reconstructed and is therefore the thing that is stored.</p>
 *
 * <h2>Revision</h2>
 * <p>{@code revision} is the compare-and-set token. It starts at zero for a record that does not
 * exist yet and strictly increases on every accepted write. Two runtimes racing on the same join
 * both read the same revision, both compute a decision, and exactly one write succeeds — which is
 * what makes "the branch that met the quorum" a single well-defined branch rather than whichever
 * thread happened to be scheduled last.</p>
 *
 * <h2>Branch keys carry an iteration, and only where there is one</h2>
 * <p>A key in {@link #branches()} is whatever the runtime's branch identity serialises to. The
 * current form is {@code nodeId[#ordinal][@lap]}, and the {@code @lap} suffix appears only for laps
 * above zero — the same trick {@code BranchId} used to introduce {@code #ordinal} without changing a
 * byte of what was already persisted. So an acyclic graph writes exactly the keys it wrote before,
 * and this store still sees opaque strings and still knows nothing about topology.</p>
 *
 * @param key the stable key used to identify the requested resource.
 * @param revision revision assigned to the durable join.
 * @param branches branch arrivals recorded for the join.
 * @param phase lifecycle phase of the join.
 * @param openedAt instant at which the join was opened.
 * @param settledAt instant at which the join settled.
 * @param failureReason why the join failed, or {@code null} when it did not fail or when the
 *                      record predates this field — see {@link #failureReason()}
 * @param firedThrough  the highest iteration bucket this join has already fired, or {@code null}
 *                      when it has never fired or the record predates this field — see
 *                      {@link #firedThrough()}
 */
public record JoinRecord(JoinKey key, long revision, Map<String, JoinBranchOutcome> branches,
                         JoinPhase phase, Instant openedAt, Instant settledAt,
                         JoinFailureReason failureReason, Integer firedThrough) {

    /** Revision of a join that has never been written. */
    public static final long ABSENT_REVISION = 0L;

    /**
     * The record shape before {@link #firedThrough()}.
     *
     * <p>Kept so that an adapter or a test written against the seven-component shape still compiles
     * and still means the same thing: a record that carries no firing marker, which is exactly what
     * {@code null} denotes.</p>
     *
     * @param key the stable key used to identify the requested resource.
     * @param revision revision assigned to the durable join.
     * @param branches branch arrivals recorded for the join.
     * @param phase lifecycle phase of the join.
     * @param openedAt instant at which the join was opened.
     * @param settledAt instant at which the join settled.
     * @param failureReason why the join failed, or {@code null} when it did not fail.
     */
    public JoinRecord(JoinKey key, long revision, Map<String, JoinBranchOutcome> branches,
                      JoinPhase phase, Instant openedAt, Instant settledAt,
                      JoinFailureReason failureReason) {
        this(key, revision, branches, phase, openedAt, settledAt, failureReason, null);
    }

    /** Validates the durable state and terminal timestamps of one fan-in join. */
    public JoinRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(openedAt, "openedAt");
        if (revision < ABSENT_REVISION) {
            throw new IllegalArgumentException("revision cannot be negative");
        }
        var sorted = new TreeMap<String, JoinBranchOutcome>();
        // Copied entry by entry rather than through containsKey, which a natural-ordering TreeMap
        // answers by throwing on a null key instead of returning false.
        (branches == null ? Map.<String, JoinBranchOutcome>of() : branches).forEach((branch, outcome) -> {
            if (branch == null || outcome == null) {
                throw new IllegalArgumentException("branch outcomes cannot be null");
            }
            sorted.put(branch, outcome);
        });
        // Kept in branch-id order so every consumer — the merged payload, the diagnostics in
        // JoinFailureException, an adapter serialising the record — sees one stable order.
        branches = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sorted));
        if (phase.terminal() == (settledAt == null)) {
            throw new IllegalArgumentException(
                    "settledAt must be present exactly when the phase is terminal, got phase=" + phase
                            + " settledAt=" + settledAt);
        }
        // Asymmetric on purpose, and the asymmetry is the whole compatibility story. A reason on a
        // record that did not fail is nonsense and is rejected. A FAILED record with no reason is
        // *not* rejected, because rejecting it would mean a record written before this field
        // existed could no longer be read back — turning a missing diagnostic into an unreadable
        // store. See failureReason() for what that absence is taken to mean.
        if (failureReason != null && phase != JoinPhase.FAILED) {
            throw new IllegalArgumentException(
                    "a failure reason is only meaningful on a FAILED record, got phase=" + phase
                            + " failureReason=" + failureReason);
        }
        // Asymmetric for the same reason failureReason is: a record written before this field
        // existed has to stay readable, so absence is accepted everywhere. A *negative* marker is
        // not absence — it is a value that no writer can produce and that no reader has a meaning
        // for — so it is rejected rather than normalised into one.
        if (firedThrough != null && firedThrough < 0) {
            throw new IllegalArgumentException(
                    "firedThrough is an iteration index and cannot be negative, got " + firedThrough);
        }
    }

/**
 * A join that has not been written yet.
 * @param key the stable key used to identify the requested resource.
 * @param openedAt instant at which the join was opened.
 * @return newly opened join record at its initial revision.
 */
    public static JoinRecord opening(JoinKey key, Instant openedAt) {
        return new JoinRecord(key, ABSENT_REVISION, new TreeMap<>(), JoinPhase.OPEN, openedAt, null, null, null);
    }

    /**
     * The highest iteration bucket of this join that has already fired, or {@code null} when none has.
     *
     * <p>The buckets of one join fire strictly in order — an arrival can only carry lap {@code k+1}
     * if it descends causally from this join's own firing at lap {@code k} — so one scalar says
     * everything about which arrivals are still expected: bucket {@code firedThrough + 1} is the one
     * being filled, and anything at or below {@code firedThrough} is a redelivery of work that
     * already continued downstream.</p>
     *
     * <p><b>Why it has to be persisted rather than kept in memory.</b> Without it, a redelivery after
     * a restart would find a record that looks exactly like the one that fired — same branches, same
     * {@link JoinPhase#OPEN} — meet the quorum a second time and invoke everything downstream twice.
     * The one-shot runtime was protected from that by {@link JoinPhase#SATISFIED}, which a join that
     * re-arms cannot write.</p>
     *
     * <p>{@code null} means one of two things, and they are told apart by {@link #phase()}: the join
     * has genuinely never fired, or the record was written by a runtime that predates this field —
     * where a {@link JoinPhase#SATISFIED} record means "fired at bucket 0, one-shot" and an
     * {@link JoinPhase#OPEN} one means "never fired", which is what a reader must take them to mean.
     * No migration is required for existing records because no cyclic graph could execute before
     * re-arming joins, so no legacy record can hold a lap above zero.</p>
     *
     * @return the highest iteration bucket already fired, or {@code null} when none has been.
     */
    public Integer firedThrough() {
        return firedThrough;
    }

    /**
     * Why this join failed, or {@code null} when the reason is not available.
     *
     * <p>{@code null} means one of exactly two things, and they are told apart by {@link #phase()}:
     * </p>
     * <ul>
     *   <li>the phase is not {@link JoinPhase#FAILED} — there is no failure to explain;</li>
     *   <li>the phase <em>is</em> {@link JoinPhase#FAILED} and <strong>the reason was never
     *       recorded</strong>, because the record was written by a runtime that predates this
     *       field. It does not mean the join did not fail, and it must not be read as one: such a
     *       record is still a failure verdict that was never delivered to anybody, and a reader
     *       that treated the missing reason as permission to continue would reintroduce exactly
     *       the silent success this field exists to stop. The runtime therefore still fails the
     *       traversal, and states that the reason is unrecorded rather than inventing one.</li>
     * </ul>
 * @return recorded reason the join became unable to satisfy its quorum.
     */
    public JoinFailureReason failureReason() {
        return failureReason;
    }

/**
 * Whether {@code branchId} already has an outcome; the duplicate-arrival test.
 * @param branchId the stable branch id used to identify the requested resource.
 * @return whether this branch has a terminal recorded outcome.
 */
    public boolean isSettled(String branchId) {
        return branches.containsKey(branchId);
    }

/**
 * Counts terminal branch outcomes in this join record.
 * @param outcome terminal join outcome.
 * @return number of branches with the requested terminal outcome.
 */
    public long countOf(JoinBranchOutcome outcome) {
        return branches.values().stream().filter(outcome::equals).count();
    }

    /**
     * The successor revision of this record carrying {@code content}.
     *
     * <p>Deliberately one method rather than a chain of {@code withBranch(..).settled(..)} builders.
     * A chain bumps the revision once per link, so recording an arrival <em>and</em> settling the
     * join in the same decision produced a record two revisions ahead of the store — a
     * compare-and-set that could never match, and therefore a join that could never fire. One method
     * that takes the whole desired content makes "one accepted write, one revision" a property of
     * the type instead of a rule callers have to remember.</p>
     *
     * @param settledAt required when {@code phase} is terminal and rejected otherwise
 * @param content branch content retained by the join.
 * @param phase lifecycle phase of the join.
 * @return next record revision after validating the requested lifecycle phase.
     */
    public JoinRecord next(Map<String, JoinBranchOutcome> content, JoinPhase phase, Instant settledAt) {
        return next(content, phase, settledAt, null);
    }

    /**
     * The successor revision of this record carrying {@code content} and, when it failed,
     * {@code failureReason}.
     *
     * <p>The reason is a parameter of the <em>same</em> successor as the phase for the same reason
     * the branch content is: one accepted write, one revision. Recording "it failed" and "it failed
     * because of X" as two writes would leave a window in which the store says a join failed and
     * cannot say why — and a crash inside that window is precisely the situation the field exists
     * to survive.</p>
     *
     * @param failureReason required to be {@code null} unless {@code phase} is
     *                      {@link JoinPhase#FAILED}; {@code null} on a {@code FAILED} record is
     *                      accepted only so that records written before this field existed remain
     *                      readable, and no current writer produces one
 * @param content branch content retained by the join.
 * @param phase lifecycle phase of the join.
 * @param settledAt instant at which the join settled.
 * @return next record revision settled at the supplied instant.
     */
    public JoinRecord next(Map<String, JoinBranchOutcome> content, JoinPhase phase, Instant settledAt,
                           JoinFailureReason failureReason) {
        return new JoinRecord(key, revision + 1, content, phase, openedAt, settledAt, failureReason, firedThrough);
    }

    /**
     * The successor revision that also records this join firing through iteration
     * {@code firedThrough}.
     *
     * <p>Separate from the overloads above only in that they carry the marker forward unchanged,
     * which is what every write that is not itself a firing must do. Advancing it is one write with
     * the branch content and the phase, for the reason the failure reason is: a crash between "the
     * branches that satisfied bucket k are recorded" and "bucket k fired" would leave a record whose
     * redelivery fires the same bucket a second time, which is the whole hazard this field exists to
     * remove.</p>
     *
     * @param content branch content retained by the join.
     * @param phase lifecycle phase of the join.
     * @param settledAt instant at which the join settled.
     * @param failureReason why the join failed, or {@code null} when it did not fail.
     * @param firedThrough the bucket just fired; must not go backwards, because buckets fire in order
     * @return next record revision carrying the advanced firing marker.
     */
    public JoinRecord next(Map<String, JoinBranchOutcome> content, JoinPhase phase, Instant settledAt,
                           JoinFailureReason failureReason, Integer firedThrough) {
        if (firedThrough != null && this.firedThrough != null && firedThrough < this.firedThrough) {
            throw new IllegalArgumentException("firedThrough cannot go backwards, from " + this.firedThrough
                    + " to " + firedThrough);
        }
        return new JoinRecord(key, revision + 1, content, phase, openedAt, settledAt, failureReason, firedThrough);
    }

/**
 * The branch map of this record plus {@code branchId}, ready to be handed to {@link #next}.
 * @param branchId the stable branch id used to identify the requested resource.
 * @param outcome terminal join outcome.
 * @return next record revision including the supplied terminal branch outcome.
 */
    public SortedMap<String, JoinBranchOutcome> plus(String branchId, JoinBranchOutcome outcome) {
        if (branchId == null || branchId.isBlank()) {
            throw new IllegalArgumentException("branchId cannot be blank");
        }
        var content = new TreeMap<>(branches);
        content.put(branchId, Objects.requireNonNull(outcome, "outcome"));
        return content;
    }
}
