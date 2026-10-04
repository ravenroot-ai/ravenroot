package ai.ravenroot.api.activity;

/** Classified payload-safe archive failure. Messages never include captured content. */
public final class ActivityArchiveException extends RuntimeException {
  public enum Reason {
    /** Archive cannot currently serve the operation. */
    UNAVAILABLE,
    /** Stable identity is already bound to different immutable content. */
    CONFLICT,
    /** Requested cursor predates retained history. */
    CURSOR_EXPIRED,
    /** Query or event violates the archive contract. */
    INVALID_REQUEST
  }

  private final Reason reason;
  private final long retainedFromCursor;
  private final String failureClass;

  /** Creates a classified failure with a payload-safe message. */
  public ActivityArchiveException(Reason reason, String safeMessage) {
    this(reason, safeMessage, 0, null);
  }

  /** Creates a classified failure while retaining only the original failure class. */
  public ActivityArchiveException(Reason reason, String safeMessage, Throwable cause) {
    this(reason, safeMessage, 0, cause);
  }

  /** Creates an expired-cursor failure with its recovery floor. */
  public ActivityArchiveException(Reason reason, String safeMessage, long retainedFromCursor) {
    this(reason, safeMessage, retainedFromCursor, null);
  }

  private ActivityArchiveException(
      Reason reason, String safeMessage, long retainedFromCursor, Throwable cause) {
    super(safeMessage);
    this.reason = java.util.Objects.requireNonNull(reason, "reason");
    this.retainedFromCursor = retainedFromCursor;
    this.failureClass = cause == null ? null : cause.getClass().getName();
  }

  /** @return classified reason */
  public Reason reason() {
    return reason;
  }

  /** @return recovery floor for {@link Reason#CURSOR_EXPIRED}, otherwise zero */
  public long retainedFromCursor() {
    return retainedFromCursor;
  }

  /** @return failure class retained without its potentially sensitive message, or {@code null} */
  public String failureClass() {
    return failureClass;
  }
}
