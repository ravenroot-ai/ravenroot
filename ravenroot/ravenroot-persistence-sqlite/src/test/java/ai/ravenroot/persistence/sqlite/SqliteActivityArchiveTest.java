package ai.ravenroot.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.*;
import ai.ravenroot.api.persistence.OpaquePayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteActivityArchiveTest {
  @TempDir Path directory;

  @Test
  void appendsReadsAndDeduplicatesAcrossAChangedObservationClock() {
    var clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    ActivityEvent first;
    try (var archive = archive(clock)) {
      first = event("tenant-a", clock.instant(), clock.instant().plusSeconds(100), "one");
      var appended = archive.append(first).toCompletableFuture().join();
      assertEquals(1, appended.cursor());
      clock.now = clock.instant().plusSeconds(5);
      var replay = copy(first, clock.instant(), clock.instant().plusSeconds(100), "one");
      var duplicate = archive.append(replay).toCompletableFuture().join();
      assertTrue(duplicate.duplicate());
      assertEquals(appended.cursor(), duplicate.cursor());

      var page = archive.read("tenant-a", ActivityQuery.after(0, 10)).toCompletableFuture().join();
      assertEquals(1, page.records().size());
      assertEquals(
          "one",
          new String(page.records().getFirst().event().content().bytes(), StandardCharsets.UTF_8));

      var conflict = copy(first, clock.instant(), clock.instant().plusSeconds(100), "two");
      var failed =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () -> archive.append(conflict).toCompletableFuture().join());
      assertEquals(
          ActivityArchiveException.Reason.CONFLICT,
          ((ActivityArchiveException) failed.getCause()).reason());
    }
    try (var reopened = archive(clock)) {
      assertEquals(
          first.eventId(),
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

  @Test
  void retentionKeepsAContiguousTenantLocalSuffixWhenExpiryIsOutOfOrder() {
    var clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    try (var archive = archive(clock)) {
      var earlyCursorLongRetention =
          event("tenant-a", clock.instant(), clock.instant().plusSeconds(100), "one");
      archive.append(earlyCursorLongRetention).toCompletableFuture().join();
      var laterCursorShortRetention =
          eventWithFreshIdentity(
              "tenant-a", clock.instant(), clock.instant().plusSeconds(10), "two");
      archive.append(laterCursorShortRetention).toCompletableFuture().join();
      archive
          .append(event("tenant-b", clock.instant(), clock.instant().plusSeconds(100), "other"))
          .toCompletableFuture()
          .join();

      clock.now = clock.instant().plusSeconds(11);
      var gap =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  archive
                      .read("tenant-a", ActivityQuery.after(0, 10))
                      .toCompletableFuture()
                      .join());
      var expired = (ActivityArchiveException) gap.getCause();
      assertEquals(ActivityArchiveException.Reason.CURSOR_EXPIRED, expired.reason());
      assertEquals(3, expired.retainedFromCursor());
      assertEquals(
          1,
          archive
              .read("tenant-b", ActivityQuery.after(0, 10))
              .toCompletableFuture()
              .join()
              .records()
              .size());
    }
  }

  @Test
  void maintenancePhysicallyDeletesExpiredIdleTenantContent() throws Exception {
    var clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    Path database = SqliteStoreLocation.underDirectory(directory).databaseFile();
    try (var archive = archive(clock)) {
      archive
          .append(event("tenant-a", clock.instant(), clock.instant().plusSeconds(1), "secret"))
          .toCompletableFuture()
          .join();
      clock.now = clock.instant().plusSeconds(2);
      assertEquals(1, archive.pruneExpired().toCompletableFuture().join());
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement();
          var row = statement.executeQuery("SELECT COUNT(*) FROM activity_event")) {
        assertTrue(row.next());
        assertEquals(0, row.getInt(1));
      }
    }
  }

  private SqliteActivityArchive archive(Clock clock) {
    return new SqliteActivityArchive(SqliteStoreLocation.underDirectory(directory), clock, 10);
  }

  private static ActivityEvent event(
      String tenant, Instant occurred, Instant expires, String content) {
    UUID process = UUID.nameUUIDFromBytes((tenant + "-process").getBytes(StandardCharsets.UTF_8));
    UUID traversal =
        UUID.nameUUIDFromBytes((tenant + "-traversal").getBytes(StandardCharsets.UTF_8));
    UUID invocation =
        UUID.nameUUIDFromBytes((tenant + "-invocation").getBytes(StandardCharsets.UTF_8));
    UUID attempt = UUID.nameUUIDFromBytes((tenant + "-attempt").getBytes(StandardCharsets.UTF_8));
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
        Set.of(),
        null,
        null,
        OpaquePayload.of(content.getBytes(StandardCharsets.UTF_8), "application/json"));
  }

  private static ActivityEvent eventWithFreshIdentity(
      String tenant, Instant occurred, Instant expires, String content) {
    var base = event(tenant, occurred, expires, content);
    UUID invocation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    String id =
        ActivityEvent.stableId(
            tenant,
            base.graphId(),
            base.graphVersion(),
            base.graphHash(),
            base.processInstanceId(),
            base.traversalId(),
            base.nodeId(),
            invocation,
            attempt,
            base.contentKind());
    return new ActivityEvent(
        id,
        tenant,
        base.graphId(),
        base.graphVersion(),
        base.graphHash(),
        base.processInstanceId(),
        base.traversalId(),
        base.nodeId(),
        invocation,
        attempt,
        2,
        base.contentKind(),
        base.command(),
        base.outcome(),
        occurred,
        expires,
        Set.of(),
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

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
