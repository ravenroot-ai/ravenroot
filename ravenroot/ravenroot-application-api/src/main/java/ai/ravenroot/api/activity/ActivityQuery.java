package ai.ravenroot.api.activity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded tenant-scoped activity query; tenant identity is supplied separately by the caller.
 * @param afterCursor exclusive tenant-local cursor
 * @param limit maximum records to return
 * @param processInstanceId optional process filter
 * @param traversalId optional traversal filter
 * @param nodeId optional node filter
 * @param invocationId optional invocation filter
 * @param attemptId optional attempt filter
 * @param contentKinds optional content-kind filters
 */
public record ActivityQuery(
    long afterCursor,
    int limit,
    UUID processInstanceId,
    UUID traversalId,
    String nodeId,
    UUID invocationId,
    UUID attemptId,
    Set<ActivityContentKind> contentKinds) {
  /**
   * Validates and snapshots one tenant-scoped archive query.
   *
   * @param afterCursor exclusive tenant-local cursor
   * @param limit maximum records to return
   * @param processInstanceId optional process filter
   * @param traversalId optional traversal filter
   * @param nodeId optional node filter
   * @param invocationId optional invocation filter
   * @param attemptId optional attempt filter
   * @param contentKinds optional content-kind filters
   */
  public ActivityQuery {
    if (afterCursor < 0) throw new IllegalArgumentException("afterCursor cannot be negative");
    if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    if (nodeId != null && nodeId.isBlank())
      throw new IllegalArgumentException("nodeId cannot be blank");
    contentKinds = Set.copyOf(Objects.requireNonNull(contentKinds, "contentKinds"));
  }

  /**
   * Creates an unfiltered incremental query.
   * @param cursor exclusive cursor
   * @param limit maximum records
   * @return bounded query
   */
  public static ActivityQuery after(long cursor, int limit) {
    return new ActivityQuery(cursor, limit, null, null, null, null, null, Set.of());
  }
}
