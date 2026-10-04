package ai.ravenroot.api.activity;

/** Classified payload-safe archive failure. Messages never include captured content. */
public final class ActivityArchiveException extends RuntimeException {
  /** Classifies failures without exposing captured content. */
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

  /** Payload-safe classification exposed to callers. */
  private final Reason reason;
  /** Recovery floor supplied for an expired cursor. */
  private final long retainedFromCursor;
  /** Original failure type retained without the failure object or message. */
  private final String failureClass;

  /**
   * Creates a classified failure with a payload-safe message.
   *
   * @param reason payload-safe failure classification
   * @param safeMessage diagnostic message that contains no captured content
   */
  public ActivityArchiveException(Reason reason, String safeMessage) {
    this(reason, safeMessage, 0, null);
  }

  /**
   * Creates a classified failure while retaining only the original failure class.
   *
   * @param reason payload-safe failure classification
   * @param safeMessage diagnostic message that contains no captured content
   * @param cause original failure used only to retain its class name
   */
  public ActivityArchiveException(Reason reason, String safeMessage, Throwable cause) {
    this(reason, safeMessage, 0, cause);
  }

  /**
   * Creates an expired-cursor failure with its recovery floor.
   *
   * @param reason payload-safe failure classification
   * @param safeMessage diagnostic message that contains no captured content
   * @param retainedFromCursor earliest cursor that remains readable
   */
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

  /**
   * Returns the payload-safe failure classification.
   *
   * @return classified reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the cursor from which a caller can resume after a retention gap.
   *
   * @return recovery floor for {@link Reason#CURSOR_EXPIRED}, otherwise zero
   */
  public long retainedFromCursor() {
    return retainedFromCursor;
  }

  /**
   * Returns the original failure type without retaining its potentially sensitive details.
   *
   * @return failure class retained without its potentially sensitive message, or {@code null}
   */
  public String failureClass() {
    return failureClass;
  }
}
