package ai.ravenroot.api.activity;

import ai.ravenroot.api.payload.PayloadLimits;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable embedded/runtime policy for optional node activity content capture.
 * @param enabled whether capture is active
 * @param failurePolicy processing behavior on capture loss
 * @param nodeIds exact selected nodes; empty selects all
 * @param contents selected content slots
 * @param payloadLimits post-redaction encoding limits
 * @param maxInFlightWrites process-local pending-write bound
 * @param writeTimeout per-record persistence deadline
 * @param retention logical content retention
 * @param redactor trusted pre-persistence redactor
 */
public record ActivityCapturePolicy(
    boolean enabled,
    ActivityFailurePolicy failurePolicy,
    Set<String> nodeIds,
    Set<ActivityContentKind> contents,
    PayloadLimits payloadLimits,
    int maxInFlightWrites,
    Duration writeTimeout,
    Duration retention,
    ActivityRedactor redactor) {
  /** Safety ceiling for pending writes. */
  public static final int HARD_MAX_IN_FLIGHT_WRITES = 10_000;
  /** Safety ceiling for a persistence deadline. */
  public static final Duration HARD_MAX_WRITE_TIMEOUT = Duration.ofMinutes(5);
  /** Safety ceiling for configured retention. */
  public static final Duration HARD_MAX_RETENTION = Duration.ofDays(3_650);

  /** Disabled default used when no archive is composed. */
  public static final ActivityCapturePolicy DISABLED =
      new ActivityCapturePolicy(
          false,
          ActivityFailurePolicy.BEST_EFFORT,
          Set.of(),
          Set.of(),
          PayloadLimits.DEFAULTS,
          1,
          Duration.ofSeconds(1),
          Duration.ofDays(1),
          ActivityRedactor.none());

  /**
   * Validates and normalizes a capture policy before the runtime observes it.
   *
   * @param enabled whether capture is active
   * @param failurePolicy processing behavior on capture loss
   * @param nodeIds exact selected nodes; empty selects all
   * @param contents selected content slots
   * @param payloadLimits post-redaction encoding limits
   * @param maxInFlightWrites process-local pending-write bound
   * @param writeTimeout per-record persistence deadline
   * @param retention logical content retention
   * @param redactor trusted pre-persistence redactor
   */
  public ActivityCapturePolicy {
    Objects.requireNonNull(failurePolicy, "failurePolicy");
    nodeIds = Set.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
    if (nodeIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("activity node selectors cannot be blank");
    }
    contents = Set.copyOf(Objects.requireNonNull(contents, "contents"));
    Objects.requireNonNull(payloadLimits, "payloadLimits");
    if (maxInFlightWrites < 1 || maxInFlightWrites > HARD_MAX_IN_FLIGHT_WRITES)
      throw new IllegalArgumentException("maxInFlightWrites is outside the supported range");
    Objects.requireNonNull(writeTimeout, "writeTimeout");
    Objects.requireNonNull(retention, "retention");
    if (writeTimeout.isZero() || writeTimeout.isNegative()) {
      throw new IllegalArgumentException("writeTimeout must be positive");
    }
    if (writeTimeout.compareTo(HARD_MAX_WRITE_TIMEOUT) > 0) {
      throw new IllegalArgumentException("writeTimeout exceeds the supported safety ceiling");
    }
    if (retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("retention must be positive");
    }
    if (retention.compareTo(HARD_MAX_RETENTION) > 0) {
      throw new IllegalArgumentException("retention exceeds the supported safety ceiling");
    }
    Objects.requireNonNull(redactor, "redactor");
    if (enabled && contents.isEmpty()) {
      throw new IllegalArgumentException("enabled activity capture requires selected content");
    }
  }

  /**
   * Tests exact node and content selection.
   * @param nodeId runtime node id
   * @param kind content slot
   * @return whether this slot is selected
   */
  public boolean selects(String nodeId, ActivityContentKind kind) {
    return enabled && contents.contains(kind) && (nodeIds.isEmpty() || nodeIds.contains(nodeId));
  }
}
