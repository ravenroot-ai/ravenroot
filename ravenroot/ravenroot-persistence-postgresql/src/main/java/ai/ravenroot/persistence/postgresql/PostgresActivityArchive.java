package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.activity.ActivityAppendResult;
import ai.ravenroot.api.activity.ActivityArchive;
import ai.ravenroot.api.activity.ActivityArchiveException;
import ai.ravenroot.api.activity.ActivityArchiveRecord;
import ai.ravenroot.api.activity.ActivityContentKind;
import ai.ravenroot.api.activity.ActivityEvent;
import ai.ravenroot.api.activity.ActivityPage;
import ai.ravenroot.api.activity.ActivityQuery;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Durable activity archive sharing the Service PostgreSQL DataSource. */
public final class PostgresActivityArchive implements ActivityArchive {
  private static final System.Logger LOG =
      System.getLogger(PostgresActivityArchive.class.getName());
  private final DataSource dataSource;
  private final Clock clock;
  private final int maximumPageSize;
  private static final int MAX_CONCURRENT_OPERATIONS = 4;
  private static final int MAX_QUEUED_OPERATIONS = 256;
  private final ThreadPoolExecutor executor;
  private final java.util.concurrent.ScheduledExecutorService maintenance;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean maintenanceRunning = new AtomicBoolean();

  public PostgresActivityArchive(DataSource dataSource, Clock clock, int maximumPageSize) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maximumPageSize < 1) throw new IllegalArgumentException("maximumPageSize must be positive");
    this.maximumPageSize = maximumPageSize;
    this.executor =
        new ThreadPoolExecutor(
            MAX_CONCURRENT_OPERATIONS,
            MAX_CONCURRENT_OPERATIONS,
            0L,
            TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(MAX_QUEUED_OPERATIONS),
            Thread.ofVirtual().name("ravenroot-postgres-activity-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    initialize();
    this.maintenance =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "ravenroot-postgres-activity-retention");
              thread.setDaemon(true);
              return thread;
            });
    maintenance.scheduleWithFixedDelay(this::scheduledPrune, 1, 1, TimeUnit.MINUTES);
  }

  @Override
  public int maximumPageSize() {
    return maximumPageSize;
  }

  @Override
  public CompletionStage<ActivityAppendResult> append(ActivityEvent event) {
    Objects.requireNonNull(event, "event");
    return submit(() -> appendNow(event));
  }

  @Override
  public CompletionStage<ActivityPage> read(String tenantId, ActivityQuery query) {
    if (tenantId == null || tenantId.isBlank())
      throw new IllegalArgumentException("tenantId cannot be blank");
    Objects.requireNonNull(query, "query");
    if (query.limit() > maximumPageSize)
      throw new ActivityArchiveException(
          ActivityArchiveException.Reason.INVALID_REQUEST,
          "activity query limit exceeds the configured maximum");
    return submit(() -> readNow(tenantId, query));
  }

  @Override
  public CompletionStage<Integer> pruneExpired() {
    return submit(this::pruneNow);
  }

  private int pruneNow() {
    var tenants = new ArrayList<String>();
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT tenant_id FROM activity_sequence")) {
      while (r.next()) tenants.add(r.getString(1));
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
    int removed = 0;
    for (String tenant : tenants) {
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          removed = Math.addExact(removed, compact(c, tenant, clock.instant()));
          c.commit();
        } catch (RuntimeException | SQLException failure) {
          rollback(c);
          throw failure;
        }
      } catch (SQLException | ArithmeticException failure) {
        throw unavailable(failure);
      }
    }
    return removed;
  }

  private void scheduledPrune() {
    if (closed.get() || !maintenanceRunning.compareAndSet(false, true)) return;
    pruneExpired()
        .whenComplete(
            (ignored, failure) -> {
              maintenanceRunning.set(false);
              if (failure != null)
                LOG.log(
                    System.Logger.Level.WARNING,
                    "activity retention maintenance failed: {0}",
                    failure.getClass().getName());
            });
  }

  private ActivityAppendResult appendNow(ActivityEvent event) {
    Instant now = clock.instant();
    if (!event.expiresAt().isAfter(now))
      throw new ActivityArchiveException(
          ActivityArchiveException.Reason.INVALID_REQUEST,
          "activity event is already outside retention");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        compact(connection, event.tenantId(), now);
        ActivityAppendResult duplicate = duplicate(connection, event);
        if (duplicate != null) {
          connection.commit();
          return duplicate;
        }
        ensureTenant(connection, event.tenantId());
        long cursor;
        try (PreparedStatement sequence =
            connection.prepareStatement(
                "SELECT next_cursor FROM activity_sequence WHERE tenant_id=? FOR UPDATE")) {
          sequence.setString(1, event.tenantId());
          try (ResultSet row = sequence.executeQuery()) {
            row.next();
            cursor = row.getLong(1);
          }
        }
        // The tenant sequence lock serializes the final identity check with insertion. A
        // concurrent replay can pass the optimistic check above while the first writer is
        // uncommitted; once this lock is acquired its committed row is visible and remains
        // an idempotent duplicate rather than becoming a transient unique-key failure.
        duplicate = duplicate(connection, event);
        if (duplicate != null) {
          connection.commit();
          return duplicate;
        }
        try (PreparedStatement advance =
            connection.prepareStatement(
                "UPDATE activity_sequence SET next_cursor=? WHERE tenant_id=?")) {
          advance.setLong(1, Math.addExact(cursor, 1));
          advance.setString(2, event.tenantId());
          advance.executeUpdate();
        }
        insert(connection, cursor, event);
        connection.commit();
        return new ActivityAppendResult(event.eventId(), cursor, false);
      } catch (RuntimeException | SQLException failure) {
        rollback(connection);
        throw failure;
      }
    } catch (ActivityArchiveException failure) {
      throw failure;
    } catch (SQLException | ArithmeticException failure) {
      throw unavailable(failure);
    }
  }

  private ActivityAppendResult duplicate(Connection connection, ActivityEvent event)
      throws SQLException {
    try (PreparedStatement duplicate =
        connection.prepareStatement(
            "SELECT cursor,event_digest FROM activity_event WHERE tenant_id=? AND event_id=?")) {
      duplicate.setString(1, event.tenantId());
      duplicate.setString(2, event.eventId());
      try (ResultSet row = duplicate.executeQuery()) {
        if (!row.next()) return null;
        if (!event.digest().equals(row.getString("event_digest"))) {
          throw new ActivityArchiveException(
              ActivityArchiveException.Reason.CONFLICT,
              "activity event identity conflicts with retained content");
        }
        return new ActivityAppendResult(event.eventId(), row.getLong("cursor"), true);
      }
    }
  }

  private ActivityPage readNow(String tenantId, ActivityQuery query) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        compact(connection, tenantId, clock.instant());
        long retainedFrom = retainedFrom(connection, tenantId);
        if (query.afterCursor() < retainedFrom - 1)
          throw new ActivityArchiveException(
              ActivityArchiveException.Reason.CURSOR_EXPIRED,
              "activity cursor is older than retained history",
              retainedFrom);
        var sql = new StringBuilder("SELECT * FROM activity_event WHERE tenant_id=? AND cursor>?");
        var parameters = new ArrayList<Object>();
        parameters.add(tenantId);
        parameters.add(query.afterCursor());
        filter(sql, parameters, "process_instance_id", query.processInstanceId());
        filter(sql, parameters, "traversal_id", query.traversalId());
        filter(sql, parameters, "node_id", query.nodeId());
        filter(sql, parameters, "invocation_id", query.invocationId());
        filter(sql, parameters, "attempt_id", query.attemptId());
        if (!query.contentKinds().isEmpty()) {
          sql.append(" AND content_kind IN (")
              .append(
                  String.join(",", java.util.Collections.nCopies(query.contentKinds().size(), "?")))
              .append(')');
          query.contentKinds().stream()
              .sorted(Comparator.comparing(Enum::name))
              .map(Enum::name)
              .forEach(parameters::add);
        }
        sql.append(" ORDER BY cursor ASC LIMIT ?");
        parameters.add(query.limit());
        var records = new ArrayList<ActivityArchiveRecord>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
          bind(statement, parameters);
          try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) records.add(readRecord(rows));
          }
        }
        connection.commit();
        return new ActivityPage(
            records,
            records.isEmpty() ? query.afterCursor() : records.getLast().cursor(),
            retainedFrom);
      } catch (RuntimeException | SQLException failure) {
        rollback(connection);
        throw failure;
      }
    } catch (ActivityArchiveException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private void initialize() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS activity_sequence (tenant_id TEXT PRIMARY KEY, next_cursor"
              + " BIGINT NOT NULL)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS activity_watermark (tenant_id TEXT PRIMARY KEY, retained_from"
              + " BIGINT NOT NULL)");
      statement.execute(
          """
CREATE TABLE IF NOT EXISTS activity_event (
  tenant_id TEXT NOT NULL, cursor BIGINT NOT NULL, event_id TEXT NOT NULL,
  event_digest TEXT NOT NULL, graph_id TEXT NOT NULL, graph_version TEXT NOT NULL,
  graph_hash TEXT NOT NULL, process_instance_id UUID NOT NULL, traversal_id UUID NOT NULL,
  node_id TEXT NOT NULL, invocation_id UUID NOT NULL, attempt_id UUID NOT NULL,
  attempt_ordinal INTEGER NOT NULL, content_kind TEXT NOT NULL, command TEXT NOT NULL,
  outcome TEXT, occurred_at_second BIGINT NOT NULL, occurred_at_nano INTEGER NOT NULL,
  expires_at_second BIGINT NOT NULL, expires_at_nano INTEGER NOT NULL,
  parent_invocation_ids TEXT NOT NULL, journal_causation_id UUID,
  causation_activity_id TEXT, content_type TEXT NOT NULL, content BYTEA NOT NULL,
  PRIMARY KEY (tenant_id,cursor), UNIQUE (tenant_id,event_id))
""");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS activity_expiry ON activity_event"
              + " (tenant_id,expires_at_second,expires_at_nano)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS activity_process ON activity_event"
              + " (tenant_id,process_instance_id,cursor)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS activity_traversal ON activity_event"
              + " (tenant_id,traversal_id,cursor)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS activity_invocation ON activity_event"
              + " (tenant_id,invocation_id,cursor)");
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private void insert(Connection connection, long cursor, ActivityEvent event) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
INSERT INTO activity_event (tenant_id,cursor,event_id,event_digest,graph_id,graph_version,
graph_hash,process_instance_id,traversal_id,node_id,invocation_id,attempt_id,attempt_ordinal,
content_kind,command,outcome,occurred_at_second,occurred_at_nano,expires_at_second,
expires_at_nano,parent_invocation_ids,journal_causation_id,causation_activity_id,content_type,content)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""")) {
      int i = 1;
      statement.setString(i++, event.tenantId());
      statement.setLong(i++, cursor);
      statement.setString(i++, event.eventId());
      statement.setString(i++, event.digest());
      statement.setString(i++, event.graphId());
      statement.setString(i++, event.graphVersion());
      statement.setString(i++, event.graphHash());
      StoredUuid.bind(statement, i++, event.processInstanceId());
      StoredUuid.bind(statement, i++, event.traversalId());
      statement.setString(i++, event.nodeId());
      StoredUuid.bind(statement, i++, event.invocationId());
      StoredUuid.bind(statement, i++, event.attemptId());
      statement.setInt(i++, event.attemptOrdinal());
      statement.setString(i++, event.contentKind().name());
      statement.setString(i++, event.command());
      statement.setString(i++, event.outcome());
      statement.setLong(i++, event.occurredAt().getEpochSecond());
      statement.setInt(i++, event.occurredAt().getNano());
      statement.setLong(i++, event.expiresAt().getEpochSecond());
      statement.setInt(i++, event.expiresAt().getNano());
      statement.setString(i++, parents(event.parentInvocationIds()));
      StoredUuid.bind(statement, i++, event.journalCausationId());
      statement.setString(i++, event.causationActivityId());
      statement.setString(i++, event.content().contentType());
      statement.setBytes(i, event.content().bytes());
      statement.executeUpdate();
    }
  }

  private ActivityArchiveRecord readRecord(ResultSet row) throws SQLException {
    String tenantId = row.getString("tenant_id");
    UUID processInstanceId =
        StoredUuid.required(row, "activity_event", "process_instance_id", tenantId);
    var key = new ExecutionKey(tenantId, processInstanceId);
    var event =
        new ActivityEvent(
            row.getString("event_id"),
            tenantId,
            row.getString("graph_id"),
            row.getString("graph_version"),
            row.getString("graph_hash"),
            processInstanceId,
            StoredUuid.required(row, "activity_event", "traversal_id", key),
            row.getString("node_id"),
            StoredUuid.required(row, "activity_event", "invocation_id", key),
            StoredUuid.required(row, "activity_event", "attempt_id", key),
            row.getInt("attempt_ordinal"),
            ActivityContentKind.valueOf(row.getString("content_kind")),
            row.getString("command"),
            row.getString("outcome"),
            instant(row, "occurred_at"),
            instant(row, "expires_at"),
            parseParents(row.getString("parent_invocation_ids"), key),
            StoredUuid.optional(row, "activity_event", "journal_causation_id"),
            row.getString("causation_activity_id"),
            OpaquePayload.of(row.getBytes("content"), row.getString("content_type")));
    return new ActivityArchiveRecord(row.getLong("cursor"), event);
  }

  private int compact(Connection connection, String tenantId, Instant now) throws SQLException {
    long removedThrough = 0;
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(cursor),0) FROM activity_event WHERE tenant_id=? AND"
                + " (expires_at_second<? OR (expires_at_second=? AND expires_at_nano<=?))")) {
      select.setString(1, tenantId);
      select.setLong(2, now.getEpochSecond());
      select.setLong(3, now.getEpochSecond());
      select.setInt(4, now.getNano());
      try (ResultSet row = select.executeQuery()) {
        row.next();
        removedThrough = row.getLong(1);
      }
    }
    if (removedThrough == 0) return 0;
    int removed;
    // Preserve a contiguous incremental suffix when clocks/retention produce out-of-order expiry.
    try (PreparedStatement delete =
        connection.prepareStatement("DELETE FROM activity_event WHERE tenant_id=? AND cursor<=?")) {
      delete.setString(1, tenantId);
      delete.setLong(2, removedThrough);
      removed = delete.executeUpdate();
    }
    ensureTenant(connection, tenantId);
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE activity_watermark SET retained_from="
                + "GREATEST(retained_from,?) WHERE tenant_id=?")) {
      update.setLong(1, Math.addExact(removedThrough, 1));
      update.setString(2, tenantId);
      update.executeUpdate();
    }
    return removed;
  }

  private void ensureTenant(Connection connection, String tenantId) throws SQLException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO activity_sequence (tenant_id,next_cursor) VALUES (?,1) ON CONFLICT"
                + " (tenant_id) DO NOTHING")) {
      s.setString(1, tenantId);
      s.executeUpdate();
    }
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO activity_watermark (tenant_id,retained_from) VALUES (?,1) ON CONFLICT"
                + " (tenant_id) DO NOTHING")) {
      s.setString(1, tenantId);
      s.executeUpdate();
    }
  }

  private long retainedFrom(Connection connection, String tenantId) throws SQLException {
    ensureTenant(connection, tenantId);
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT retained_from FROM activity_watermark WHERE tenant_id=?")) {
      s.setString(1, tenantId);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private <T> CompletionStage<T> submit(java.util.concurrent.Callable<T> operation) {
    if (closed.get())
      return CompletableFuture.failedFuture(
          new ActivityArchiveException(
              ActivityArchiveException.Reason.UNAVAILABLE, "activity archive is closed"));
    var result = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              result.complete(operation.call());
            } catch (Throwable failure) {
              result.completeExceptionally(failure);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException saturated) {
      result.completeExceptionally(
          new ActivityArchiveException(
              ActivityArchiveException.Reason.UNAVAILABLE,
              "activity archive operation capacity is exhausted"));
    }
    return result;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      maintenance.shutdown();
      executor.shutdown();
    }
  }

  private static void rollback(Connection c) {
    try {
      c.rollback();
    } catch (SQLException ignored) {
    }
  }

  private static void filter(
      StringBuilder sql, List<Object> parameters, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      parameters.add(value);
    }
  }

  private static void bind(PreparedStatement s, List<Object> values) throws SQLException {
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof Integer n) s.setInt(i + 1, n);
      else if (value instanceof Long n) s.setLong(i + 1, n);
      else if (value instanceof UUID id) StoredUuid.bind(s, i + 1, id);
      else s.setString(i + 1, value.toString());
    }
  }

  private static String parents(Set<UUID> ids) {
    return ids.stream()
        .sorted(Comparator.comparing(UUID::toString))
        .map(UUID::toString)
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static Set<UUID> parseParents(String value, ExecutionKey key) {
    if (value == null || value.isEmpty()) return Set.of();
    var result = new LinkedHashSet<UUID>();
    Arrays.stream(value.split(","))
        .map(stored -> StoredUuid.required(stored, "activity_event", "parent_invocation_ids", key))
        .forEach(result::add);
    return Set.copyOf(result);
  }

  private static Instant instant(ResultSet row, String prefix) throws SQLException {
    return Instant.ofEpochSecond(row.getLong(prefix + "_second"), row.getInt(prefix + "_nano"));
  }

  private static ActivityArchiveException unavailable(Throwable failure) {
    return new ActivityArchiveException(
        ActivityArchiveException.Reason.UNAVAILABLE,
        "PostgreSQL activity archive is unavailable",
        failure);
  }
}
