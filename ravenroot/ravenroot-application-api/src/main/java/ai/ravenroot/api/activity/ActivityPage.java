package ai.ravenroot.api.activity;

import java.util.List;
import java.util.Objects;

/**
 * Ordered incremental page and the current retained floor for explicit gap handling.
 * @param records ascending retained records
 * @param nextCursor cursor to pass as the next exclusive starting point
 * @param retainedFromCursor earliest cursor that can still be read without a gap
 */
public record ActivityPage(
    List<ActivityArchiveRecord> records, long nextCursor, long retainedFromCursor) {
  public ActivityPage {
    records = List.copyOf(Objects.requireNonNull(records, "records"));
    if (nextCursor < 0) throw new IllegalArgumentException("nextCursor cannot be negative");
    if (retainedFromCursor < 1)
      throw new IllegalArgumentException("retainedFromCursor starts at one");
  }
}
