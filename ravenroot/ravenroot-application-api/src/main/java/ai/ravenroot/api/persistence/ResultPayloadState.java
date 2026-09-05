package ai.ravenroot.api.persistence;

/**
 * What became of a terminal execution's payload on its way into the durable record, and what a
 * reader is therefore being offered.
 *
 * <p>Four different facts are collapsed into "no payload came back" by every design that models this
 * as a nullable field, and a caller cannot act on any of them: <em>the run produced nothing</em> is a
 * normal completion, <em>it was too large</em> is a limit an operator can raise, <em>it could not be
 * converted</em> is a defect in the producing node, and <em>it aged out</em> is a retention policy
 * working as configured. This enum is the component that keeps them apart, and it is why the durable
 * record has a payload state rather than a payload that is sometimes absent.</p>
 *
 * <h2>{@link #EXPIRED} is produced by a read and never by a write</h2>
 * <p>Every other member describes a decision taken at the moment the result was recorded, and is
 * stored. {@link #EXPIRED} describes the record's age against the store's own clock, which is not a
 * property a writer can know, so it is derived on the way out and is never written down. A durable
 * row therefore never carries it, and an adapter that stored it would be recording a fact that
 * becomes true later.</p>
 */
public enum ResultPayloadState {

    /**
     * The execution produced no payload at all, and nothing was withheld.
     *
     * <p>The ordinary state for a failure and for a cancellation, and also legitimate for a
     * completion whose terminal node returned nothing. It is a positive statement — "there was
     * nothing to keep" — and must never be used to report that something was kept and then
     * dropped.</p>
     */
    NONE,

    /**
     * A payload was produced and its bounded projection is stored and returned.
     *
     * <p>Bounded, not verbatim: {@link ExecutionResultPayload#redacted()} and
     * {@link ExecutionResultPayload#truncated()} say whether recognised credential material was
     * replaced and whether the projection was shortened. Both may be true of a
     * {@code RETAINED} payload, which is the point of keeping them as flags beside the state rather
     * than as further members here — they qualify a payload that is present, while every other
     * member of this enum says one is not.</p>
     */
    RETAINED,

    /**
     * A payload was produced and none of it is stored, because its projection exceeded the byte cap
     * the adapter publishes.
     *
     * <p>{@link ExecutionResultPayload#bytes()} still reports the size that was refused, so an
     * operator deciding whether to raise the cap can see by how much. Storing a prefix instead was
     * rejected: a prefix of an encoded document is not a document, and a reader handed one would
     * have to guess whether it was truncated data or corrupt data.</p>
     */
    WITHHELD,

    /**
     * A payload was produced and none of it is stored, because it does not project onto the closed
     * payload model at all.
     *
     * <p>Distinct from {@link #WITHHELD} because the two call for opposite actions: a size refusal is
     * a limit to raise, and this is a node returning a value no remote adapter could ever persist.
     * Reporting it as an absent payload would make the second silently look like a run that produced
     * nothing.</p>
     */
    UNCONVERTIBLE,

    /**
     * A payload was stored and is no longer offered, because the record's retention deadline has
     * passed on the store's clock.
     *
     * <p>The record itself survives until a purge removes it, so the terminal status and the
     * termination reason beside it are still reported. Read the two together: past this horizon the
     * status alone says a cancelled execution failed, which is the wrong answer rather than a partial
     * one.</p>
     */
    EXPIRED
}
