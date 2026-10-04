package ai.ravenroot.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.*;
import ai.ravenroot.api.persistence.OpaquePayload;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresActivityArchiveTest {
  @Test
  void persistsTenantScopedIncrementalHistoryAndIdempotentReplayOnARealServer() {
    Instant now = Instant.parse("2026-10-04T00:00:00Z");
    var dataSource = PostgresTestDatabase.dataSourceFor("activity-" + UUID.randomUUID());
    var event = event("tenant-a", now, now.plusSeconds(60), "one");
    try (var archive =
        new PostgresActivityArchive(dataSource, Clock.fixed(now, ZoneOffset.UTC), 10)) {
      var first = archive.append(event).toCompletableFuture().join();
      var duplicate =
          archive
              .append(copy(event, now.plusSeconds(1), now.plusSeconds(61), "one"))
              .toCompletableFuture()
              .join();
      assertTrue(duplicate.duplicate());
      assertEquals(first.cursor(), duplicate.cursor());
      assertEquals(
          1,
          archive
              .read("tenant-a", ActivityQuery.after(0, 10))
              .toCompletableFuture()
              .join()
              .records()
              .size());
      assertTrue(
          archive
              .read("tenant-b", ActivityQuery.after(0, 10))
              .toCompletableFuture()
              .join()
              .records()
              .isEmpty());
      var concurrent = event("tenant-a", now, now.plusSeconds(60), "concurrent");
      var replays =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(ignored -> archive.append(concurrent).toCompletableFuture())
              .toList();
      var replayResults =
          replays.stream().map(java.util.concurrent.CompletableFuture::join).toList();
      assertEquals(1, replayResults.stream().filter(result -> !result.duplicate()).count());
      assertEquals(1, replayResults.stream().map(ActivityAppendResult::cursor).distinct().count());
      var conflict =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  archive
                      .append(copy(concurrent, now.plusSeconds(2), now.plusSeconds(62), "changed"))
                      .toCompletableFuture()
                      .join());
      assertEquals(
          ActivityArchiveException.Reason.CONFLICT,
          assertInstanceOf(ActivityArchiveException.class, conflict.getCause()).reason());
    }
    try (var reopened =
        new PostgresActivityArchive(dataSource, Clock.fixed(now, ZoneOffset.UTC), 10)) {
      assertEquals(
          event.eventId(),
          reopened
              .read("tenant-a", ActivityQuery.after(0, 10))
              .toCompletableFuture()
              .join()
              .records()
              .getFirst()
              .event()
              .eventId());
    }
  }

  private static ActivityEvent event(
      String tenant, Instant occurred, Instant expires, String content) {
    UUID process = UUID.randomUUID(),
        traversal = UUID.randomUUID(),
        invocation = UUID.randomUUID(),
        attempt = UUID.randomUUID();
    String id =
        ActivityEvent.stableId(
            tenant,
            "graph",
            "v1",
            "hash",
            process,
            traversal,
            "node",
            invocation,
            attempt,
            ActivityContentKind.INPUT_PAYLOAD);
    return new ActivityEvent(
        id,
        tenant,
        "graph",
        "v1",
        "hash",
        process,
        traversal,
        "node",
        invocation,
        attempt,
        1,
        ActivityContentKind.INPUT_PAYLOAD,
        "PROCESS",
        null,
        occurred,
        expires,
        Set.of(UUID.fromString("20000000-0000-0000-0000-000000000001")),
        null,
        null,
        OpaquePayload.of(content.getBytes(StandardCharsets.UTF_8), "application/json"));
  }

  private static ActivityEvent copy(
      ActivityEvent event, Instant occurred, Instant expires, String content) {
    return new ActivityEvent(
        event.eventId(),
        event.tenantId(),
        event.graphId(),
        event.graphVersion(),
        event.graphHash(),
        event.processInstanceId(),
        event.traversalId(),
        event.nodeId(),
        event.invocationId(),
        event.attemptId(),
        event.attemptOrdinal(),
        event.contentKind(),
        event.command(),
        event.outcome(),
        occurred,
        expires,
        event.parentInvocationIds(),
        event.journalCausationId(),
        event.causationActivityId(),
        OpaquePayload.of(content.getBytes(StandardCharsets.UTF_8), "application/json"));
  }
}
