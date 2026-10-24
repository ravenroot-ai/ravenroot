package ai.ravenroot.api.history;

/** Safe public failure category; identifiers and payload values are never included in diagnostics. */
public final class HistoryReadException extends IllegalStateException {
    /** Safe source-bound read refusal categories. */
    public enum Reason {
        /** A required capability or bounded storage read is unavailable. */
        UNAVAILABLE,
        /** The captured physical deployment binding is no longer current. */
        SOURCE_CHANGED,
        /** The selection does not exist in this authenticated logical deployment and exact version. */
        NOT_FOUND
    }
    /** Closed source-bound refusal classification.
     * @serial Closed refusal category without selected identity values.
     */
    private final Reason reason;
    /** Creates a payload-safe categorized history refusal.
 * @param reason closed refusal category without selected values
 */
    public HistoryReadException(Reason reason) { super(reason.name()); this.reason = reason; }
    /** Exposes the closed refusal category without values from the rejected selection.
 * @return closed failure category suitable for a payload-safe response
 */
    public Reason reason() { return reason; }
}
