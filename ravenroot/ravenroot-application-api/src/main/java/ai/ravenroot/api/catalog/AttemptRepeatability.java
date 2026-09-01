package ai.ravenroot.api.catalog;

/**
 * What a node instance declares about repeating an effect whose outcome is unknown (ADR 0022,
 * documented contract 1: the declaration is per <em>node instance in the graph</em>, never per type).
 *
 * <p>The enum has three members and not two, because "declared not repeatable" and "declared
 * nothing" are different facts about the author even though PERS-04 disposes of them identically.
 * Collapsing them would erase the difference between a considered decision and an omission at
 * exactly the point where an operator needs to know which they are looking at.</p>
 *
 * <h2>"Repeatable" must survive being run twice at once, not only twice in a row</h2>
 * <p>The scenario this declaration exists for is not only "the worker crashed, then something else
 * retried after it was gone." A worker's fence can be revoked while this node's own effect is still
 * genuinely executing — an ordinary network partition or scheduling delay is enough, no crash
 * required — and the runtime cannot cancel that in-flight call once it has started. So a second
 * dispatch authorised by {@link #REPEATABLE} can, in the worst case, run <strong>concurrently</strong>
 * with the first one's own effect finishing, not strictly after it. {@code REPEATABLE} is honest only
 * if the effect is safe under that overlap — for example because the target system deduplicates on an
 * idempotency key derived from the attempt, which is exactly what the recovery re-dispatch uses (the
 * attempt id) — not merely safe to run a second time once the first is known to be over. An author who
 * has only verified the sequential case has not verified what this value promises.</p>
 */
public enum AttemptRepeatability {

    /** The instance declares the effect safe to repeat. The only value that authorises re-dispatch. */
    REPEATABLE,

    /** The instance declares the effect unsafe to repeat. */
    NOT_REPEATABLE,

    /**
     * Nothing usable was declared: the property was absent, blank, outside its allowed values, or the
     * node's type never declared it at all. Every one of those is a park (documented contract 2).
     */
    UNDECLARED;

    /**
     * Whether automatic re-dispatch is authorised. True for exactly one member, which is the whole
     * of the fail-closed posture: a new member added later is unsafe by default rather than safe.
     * @return {@code true} only for {@link #REPEATABLE}; all other states withhold re-dispatch authority.
     */
    public boolean authorisesReDispatch() {
        return this == REPEATABLE;
    }
}
