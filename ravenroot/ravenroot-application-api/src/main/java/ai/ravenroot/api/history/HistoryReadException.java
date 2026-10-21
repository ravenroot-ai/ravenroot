package ai.ravenroot.api.history;

/** Safe public failure category; identifiers and payload values are never included in diagnostics. */
public final class HistoryReadException extends IllegalStateException {
    public enum Reason { UNAVAILABLE, SOURCE_CHANGED, NOT_FOUND }
    private final Reason reason;
    public HistoryReadException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
