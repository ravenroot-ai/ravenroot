package ai.ravenroot.api.activity;

/**
 * Result of a durable append; duplicate means the identical event already occupied this cursor.
 * @param eventId acknowledged stable event identity
 * @param cursor tenant-local durable cursor
 * @param duplicate whether an identical retained row already existed
 */
public record ActivityAppendResult(String eventId, long cursor, boolean duplicate) {
  /**
   * Validates the archive acknowledgement returned to the caller.
   *
   * @param eventId acknowledged stable event identity
   * @param cursor tenant-local durable cursor
   * @param duplicate whether an identical retained row already existed
   */
  public ActivityAppendResult {
    if (eventId == null || !eventId.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("eventId must be SHA-256 hex");
    }
    if (cursor < 1) throw new IllegalArgumentException("cursor starts at one");
  }
}
