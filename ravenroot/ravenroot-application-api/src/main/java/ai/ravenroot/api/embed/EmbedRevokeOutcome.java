package ai.ravenroot.api.embed;

/** Closed revocation vocabulary; see {@link EmbedProvisionOutcome} for why failure is a value. */
public sealed interface EmbedRevokeOutcome {

    /** The registration is terminally revoked at {@code revision}.
     * @param revision new terminal aggregate revision
     */
    record Revoked(long revision) implements EmbedRevokeOutcome { }

    /** Compare-and-set conflict; no revocation was applied.
     * @param expectedRevision revision named by the operator
     * @param currentRevision revision held by the aggregate
     */
    record Conflict(long expectedRevision, long currentRevision) implements EmbedRevokeOutcome { }

    /**
     * The revocation <em>was</em> committed and its terminal audit record was not.
     *
     * <p>See {@link EmbedProvisionOutcome.AppliedUnrecorded}. This is the more consequential of the
     * two: an operator who reads "unavailable" after revoking and concludes the embed is still live
     * will go looking for a second way to stop it, and an operator who concludes the revocation
     * failed may re-run a provision. The registration at {@code revision} is terminally revoked.</p>
     * @param revision terminal revision that was committed without a terminal audit record
     */
    record AppliedUnrecorded(long revision) implements EmbedRevokeOutcome { }

    /** No registration with that id under that tenant. Not distinguishable from a foreign tenant's. */
    enum NotFound implements EmbedRevokeOutcome { /** Singleton non-disclosing result. */ INSTANCE }

    /** Already terminal. Re-revoking is not an error, but it does not mint a new revision either.
     * @param revision existing terminal revision
     */
    record AlreadyRevoked(long revision) implements EmbedRevokeOutcome { }

    /** The authority could not determine an outcome; callers must not infer that revocation failed. */
    enum Unavailable implements EmbedRevokeOutcome { /** Singleton unavailable result. */ INSTANCE }
}
