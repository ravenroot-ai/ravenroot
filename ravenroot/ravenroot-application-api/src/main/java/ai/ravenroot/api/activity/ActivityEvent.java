package ai.ravenroot.api.activity;

import ai.ravenroot.api.persistence.OpaquePayload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One deduplicated node-content observation, separate from the execution event journal.
 * @param eventId stable content-slot identity
 * @param tenantId owning tenant
 * @param graphId graph identity
 * @param graphVersion pinned graph version
 * @param graphHash pinned canonical graph hash
 * @param processInstanceId process identity
 * @param traversalId traversal identity
 * @param nodeId node identity
 * @param invocationId node invocation identity
 * @param attemptId node attempt identity
 * @param attemptOrdinal one-based attempt ordinal
 * @param contentKind captured slot
 * @param command delivered node command
 * @param outcome result outcome for output slots, otherwise {@code null}
 * @param occurredAt first observation time
 * @param expiresAt logical expiry time
 * @param parentInvocationIds causal parent invocations
 * @param journalCausationId corresponding execution-journal event
 * @param causationActivityId selected input activity event for an output
 * @param content redacted bounded canonical content
 */
public record ActivityEvent(
    String eventId,
    String tenantId,
    String graphId,
    String graphVersion,
    String graphHash,
    UUID processInstanceId,
    UUID traversalId,
    String nodeId,
    UUID invocationId,
    UUID attemptId,
    int attemptOrdinal,
    ActivityContentKind contentKind,
    String command,
    String outcome,
    Instant occurredAt,
    Instant expiresAt,
    Set<UUID> parentInvocationIds,
    UUID journalCausationId,
    String causationActivityId,
    OpaquePayload content) {
  public ActivityEvent {
    eventId = requireText(eventId, "eventId");
    if (!eventId.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("eventId must be SHA-256 hex");
    tenantId = requireText(tenantId, "tenantId");
    graphId = requireText(graphId, "graphId");
    graphVersion = requireText(graphVersion, "graphVersion");
    graphHash = requireText(graphHash, "graphHash");
    Objects.requireNonNull(processInstanceId, "processInstanceId");
    Objects.requireNonNull(traversalId, "traversalId");
    nodeId = requireText(nodeId, "nodeId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(attemptId, "attemptId");
    if (attemptOrdinal < 1) throw new IllegalArgumentException("attemptOrdinal must be positive");
    Objects.requireNonNull(contentKind, "contentKind");
    command = requireText(command, "command");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(occurredAt))
      throw new IllegalArgumentException("expiresAt must follow occurredAt");
    parentInvocationIds =
        Set.copyOf(Objects.requireNonNull(parentInvocationIds, "parentInvocationIds"));
    if (parentInvocationIds.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("parentInvocationIds cannot contain null");
    }
    if (causationActivityId != null && !causationActivityId.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("causationActivityId must be SHA-256 hex");
    }
    Objects.requireNonNull(content, "content");
  }

  /**
   * Builds a stable content-slot identity; retries and re-entry attempts differ through typed ids.
   * @param tenantId tenant identity
   * @param graphId graph identity
   * @param graphVersion graph version
   * @param graphHash canonical graph hash
   * @param processInstanceId process identity
   * @param traversalId traversal identity
   * @param nodeId node identity
   * @param invocationId invocation identity
   * @param attemptId attempt identity
   * @param kind content slot
   * @return lowercase SHA-256 hex identity
   */
  public static String stableId(
      String tenantId,
      String graphId,
      String graphVersion,
      String graphHash,
      UUID processInstanceId,
      UUID traversalId,
      String nodeId,
      UUID invocationId,
      UUID attemptId,
      ActivityContentKind kind) {
    var parts =
        java.util.List.of(
            tenantId,
            graphId,
            graphVersion,
            graphHash,
            processInstanceId.toString(),
            traversalId.toString(),
            nodeId,
            invocationId.toString(),
            attemptId.toString(),
            kind.name());
    return digest(
        "ravenroot.activity.v1",
        parts.stream().map(value -> value.getBytes(StandardCharsets.UTF_8)).toList());
  }

  /** @return digest distinguishing an idempotent replay from conflicting immutable content */
  public String digest() {
    var parts = new java.util.ArrayList<byte[]>();
    for (String value :
        java.util.List.of(
            eventId,
            tenantId,
            graphId,
            graphVersion,
            graphHash,
            processInstanceId.toString(),
            traversalId.toString(),
            nodeId,
            invocationId.toString(),
            attemptId.toString(),
            Integer.toString(attemptOrdinal),
            contentKind.name(),
            command,
            outcome == null ? "" : outcome,
            journalCausationId == null ? "" : journalCausationId.toString(),
            causationActivityId == null ? "" : causationActivityId,
            content.contentType())) {
      parts.add(value.getBytes(StandardCharsets.UTF_8));
    }
    parentInvocationIds.stream()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(id -> parts.add(id.toString().getBytes(StandardCharsets.UTF_8)));
    parts.add(content.bytes());
    return digest("ravenroot.activity-record.v1", parts);
  }

  private static String digest(String domain, java.util.List<byte[]> parts) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      digest.update(domain.getBytes(StandardCharsets.US_ASCII));
      digest.update((byte) 0);
      for (byte[] part : parts) {
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
        digest.update(part);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " cannot be blank");
    return value;
  }
}
