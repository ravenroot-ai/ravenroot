package ai.ravenroot.api.activity;

import java.util.Objects;

/**
 * One retained activity event at its tenant-local incremental cursor.
 * @param cursor tenant-local durable cursor
 * @param event retained event
 */
public record ActivityArchiveRecord(long cursor, ActivityEvent event) {
  public ActivityArchiveRecord {
    if (cursor < 1) throw new IllegalArgumentException("cursor starts at one");
    Objects.requireNonNull(event, "event");
  }
}
