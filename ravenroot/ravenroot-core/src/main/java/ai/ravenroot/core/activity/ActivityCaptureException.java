package ai.ravenroot.core.activity;

/** Payload-safe explicit failure at a STRICT activity persistence boundary. */
public final class ActivityCaptureException extends RuntimeException {
  public enum Reason {
    CAPACITY,
    REDACTION,
    ENCODING,
    WRITE,
    TIMEOUT
  }

  private final Reason reason;
  private final String eventId;
  private final String failureClass;

  ActivityCaptureException(Reason reason, String eventId, Throwable cause) {
    super(
        "activity capture "
            + reason.name().toLowerCase(java.util.Locale.ROOT)
            + " for event "
            + eventId);
    this.reason = reason;
    this.eventId = eventId;
    this.failureClass =
        cause instanceof ai.ravenroot.api.activity.ActivityArchiveException archive
                && archive.failureClass() != null
            ? archive.failureClass()
            : cause == null ? null : cause.getClass().getName();
  }

  public Reason reason() {
    return reason;
  }

  public String eventId() {
    return eventId;
  }

  public String failureClass() {
    return failureClass;
  }
}
