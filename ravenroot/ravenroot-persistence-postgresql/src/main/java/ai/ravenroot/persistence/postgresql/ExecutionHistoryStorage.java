package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.persistence.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Metadata-only keyset reads: invocation/attempt/payload collections are never loaded as an aggregate. */
final class ExecutionHistoryStorage {
    private static final String PROCESS = "tenant_id = ? AND process_instance_id = ?";
    private static final String TRAVERSAL = PROCESS + " AND traversal_id = ?";
    private ExecutionHistoryStorage() { }
    static boolean selectionExists(Connection db, ExecutionKey key, UUID traversal, Optional<UUID> invocation,
            Optional<UUID> attempt) throws SQLException {
        Objects.requireNonNull(traversal); Objects.requireNonNull(invocation); Objects.requireNonNull(attempt);
        if (attempt.isPresent() && invocation.isEmpty()) throw new IllegalArgumentException("attempt requires visit");
        if (invocation.isEmpty()) return traversal(db, key, traversal).isPresent();
        if (!visitExists(db, key, traversal, invocation.orElseThrow())) return false;
        if (attempt.isEmpty()) return true;
        try (var statement = db.prepareStatement("SELECT 1 FROM attempt WHERE " + PROCESS + " AND invocation_id = ? AND attempt_id = ?")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, invocation.orElseThrow()); bindUuid(statement, at, attempt.orElseThrow());
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    static HistoryPage<TraversalSummary> uncorrelated(Connection db, BusinessScope scope, Optional<String> cursor,
            int limit, int maximum) throws SQLException {
        requireLimit(limit, maximum); String filter = "uncorrelated-traversals:" + limit;
        var after = cursor.map(value -> BusinessCursor.decode(scope, filter, value, 2));
        UUID process = after.map(value -> UUID.fromString(value.getFirst())).orElse(null);
        long position = after.map(value -> Long.parseLong(value.get(1))).orElse(-1L);
        if (position < -1) throw new IllegalArgumentException("invalid traversal cursor");
        var items = new ArrayList<TraversalSummary>(); String lastProcess = null; long lastPosition = -1;
        String sql = "SELECT t.process_instance_id, t.traversal_id, t.position, t.ingress_node_id, t.status, t.termination_reason, "
                + "(SELECT COUNT(*) FROM invocation i WHERE i.tenant_id = t.tenant_id AND i.process_instance_id = t.process_instance_id AND i.traversal_id = t.traversal_id) AS visits "
                + "FROM traversal t JOIN process_instance p ON p.tenant_id = t.tenant_id AND p.process_instance_id = t.process_instance_id "
                + "WHERE p.tenant_id = ? AND p.deployment_id = ? AND p.graph_version_pin = ? "
                + "AND NOT EXISTS (SELECT 1 FROM correlated_traversal c WHERE c.tenant_id = t.tenant_id AND c.process_id = CAST(t.process_instance_id AS TEXT) AND c.traversal_id = CAST(t.traversal_id AS TEXT)) "
                + (process == null ? "" : "AND (t.process_instance_id > ? OR (t.process_instance_id = ? AND t.position > ?)) ")
                + "ORDER BY t.process_instance_id, t.position LIMIT ?";
        try (var statement = db.prepareStatement(sql)) {
            int at = 1; statement.setString(at++, scope.tenantId()); statement.setString(at++, scope.deploymentId()); statement.setString(at++, scope.graphVersion());
            if (process != null) { bindUuid(statement, at++, process); bindUuid(statement, at++, process); statement.setLong(at++, position); }
            statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID owner = uuid(rows, "process_instance_id");
                    items.add(new TraversalSummary(owner, uuid(rows, "traversal_id"), rows.getString("ingress_node_id"),
                            TraversalStatus.valueOf(rows.getString("status")), rows.getString("termination_reason") == null ? null :
                            ExecutionTerminationReason.valueOf(rows.getString("termination_reason")), rows.getLong("visits"), null, null));
                    if (items.size() <= limit) { lastProcess = owner.toString(); lastPosition = rows.getLong("position"); }
                }
            }
        }
        boolean more = items.size() > limit;
        return new HistoryPage<>(more ? items.subList(0, limit) : items,
                more ? Optional.of(BusinessCursor.encode(scope, filter, List.of(lastProcess, Long.toString(lastPosition)))) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    static HistoryPage<TraversalSummary> traversals(Connection db, ExecutionKey key, Optional<String> cursor,
                                                   int limit, int maximum) throws SQLException {
        requireLimit(limit, maximum); String query = "traversals:" + limit;
        long after = position(key, null, query, cursor);
        if (!exists(db, key)) return HistoryPage.missing();
        var items = new ArrayList<TraversalSummary>(); long last = after;
        try (var statement = db.prepareStatement("SELECT traversal_id, position, ingress_node_id, status, termination_reason, "
                + "(SELECT COUNT(*) FROM invocation i WHERE i.tenant_id = t.tenant_id AND i.process_instance_id = t.process_instance_id "
                + "AND i.traversal_id = t.traversal_id) AS visits FROM traversal t WHERE " + PROCESS + " AND position > ? ORDER BY position LIMIT ?")) {
            int at = bindKey(statement, 1, key); statement.setLong(at++, after); statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID traversal = uuid(rows, "traversal_id");
                    items.add(traversal(db, key, traversal, rows.getString("ingress_node_id"), rows.getString("status"),
                            rows.getString("termination_reason"), rows.getLong("visits")));
                    if (items.size() <= limit) last = rows.getLong("position");
                }
            }
        }
        return page(items, limit, key, null, query, Long.toString(last));
    }
    static Map<UUID, String> invocationNodeIds(Connection db, ExecutionKey key, Set<UUID> requested) throws SQLException {
        var ids = List.copyOf(requested);
        if (ids.size() > 1000) throw new IllegalArgumentException("invocation binding limit is 1000");
        if (ids.isEmpty()) return Map.of();
        var bindings = new HashMap<UUID, String>();
        try (var statement = db.prepareStatement("SELECT invocation_id, node_id FROM invocation WHERE " + PROCESS
                + " AND invocation_id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")")) {
            int at = bindKey(statement, 1, key);
            for (var id : ids) bindUuid(statement, at++, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) bindings.put(uuid(rows, "invocation_id"), rows.getString("node_id"));
            }
        }
        return Map.copyOf(bindings);
    }
    static Optional<TraversalSummary> traversal(Connection db, ExecutionKey key, UUID traversal) throws SQLException {
        try (var statement = db.prepareStatement("SELECT ingress_node_id, status, termination_reason, "
                + "(SELECT COUNT(*) FROM invocation i WHERE i.tenant_id = t.tenant_id AND i.process_instance_id = t.process_instance_id "
                + "AND i.traversal_id = t.traversal_id) AS visits FROM traversal t WHERE " + TRAVERSAL)) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at, traversal);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(traversal(db, key, traversal,
                    rows.getString("ingress_node_id"), rows.getString("status"), rows.getString("termination_reason"), rows.getLong("visits"))) : Optional.empty(); }
        }
    }
    private static TraversalSummary traversal(Connection db, ExecutionKey key, UUID traversal,
            String entry, String status, String reason, long visits) throws SQLException {
        var context = BusinessHistoryStorage.traversal(db, key.tenantId(), key.processInstanceId(), traversal);
        return new TraversalSummary(key.processInstanceId(), traversal, entry, TraversalStatus.valueOf(status),
                reason == null ? null : ExecutionTerminationReason.valueOf(reason), visits,
                context.map(CorrelatedTraversal::context).orElse(null), context.map(CorrelatedTraversal::cause).orElse(null), context.map(CorrelatedTraversal::derivation).orElse(null));
    }
    static HistoryPage<NodeVisitSummary> visits(Connection db, ExecutionKey key, UUID traversal,
            Optional<String> node, Optional<String> cursor, int limit, int maximum) throws SQLException {
        requireLimit(limit, maximum); node.ifPresent(value -> {
            if (value.isBlank() || value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid node filter");
        });
        String query = "visits:" + node.orElse("") + ':' + limit;
        long after = position(key, traversal, query, cursor);
        if (traversal(db, key, traversal).isEmpty()) return HistoryPage.missing();
        var items = new ArrayList<NodeVisitSummary>(); long last = after;
        String sql = "SELECT invocation_id, position, node_id, status, node_command, "
                + "(SELECT COUNT(*) FROM attempt a WHERE a.tenant_id = i.tenant_id AND a.process_instance_id = i.process_instance_id AND a.invocation_id = i.invocation_id) AS attempts, "
                + "(SELECT COUNT(*) FROM invocation_parent p WHERE p.tenant_id = i.tenant_id AND p.process_instance_id = i.process_instance_id AND p.invocation_id = i.invocation_id) AS parents "
                + "FROM invocation i WHERE " + TRAVERSAL + (node.isPresent() ? " AND node_id = ?" : "") + " AND position > ? ORDER BY position LIMIT ?";
        try (var statement = db.prepareStatement(sql)) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, traversal);
            if (node.isPresent()) statement.setString(at++, node.orElseThrow());
            statement.setLong(at++, after); statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID invocation = uuid(rows, "invocation_id");
                    var status = NodeInvocationStatus.valueOf(rows.getString("status"));
                    // Only selected page members get timing lookups. The extra row proves a next page.
                    var first = items.size() < limit ? attemptId(db, key, invocation, false) : Optional.<UUID>empty();
                    var finalAttempt = items.size() < limit && status.terminal() ? attemptId(db, key, invocation, true) : Optional.<UUID>empty();
                    var started = first.isPresent() ? timing(db, key, invocation, first.orElseThrow(), false) : Optional.<Observation>empty();
                    var settled = finalAttempt.isPresent() ? timing(db, key, invocation, finalAttempt.orElseThrow(), true) : Optional.<Observation>empty();
                    items.add(new NodeVisitSummary(key.processInstanceId(), traversal, invocation, rows.getString("node_id"),
                            rows.getLong("position"), status, rows.getString("node_command"), rows.getLong("attempts"), rows.getLong("parents"),
                            started.map(Observation::at).orElse(null), settled.map(Observation::at).orElse(null),
                            settled.flatMap(value -> NodeOutcomeEventData.outcome(value.data())).orElse(null), started.isPresent() || settled.isPresent()));
                    if (items.size() <= limit) last = rows.getLong("position");
                }
            }
        }
        return page(items, limit, key, traversal, query, Long.toString(last));
    }
    static HistoryPage<NodeAttemptSummary> attempts(Connection db, ExecutionKey key, UUID traversal, UUID invocation,
            Optional<String> cursor, int limit, int maximum) throws SQLException {
        requireLimit(limit, maximum); String query = "attempts:" + invocation + ':' + limit;
        long after = position(key, traversal, query, cursor);
        if (!visitExists(db, key, traversal, invocation)) return HistoryPage.missing();
        var items = new ArrayList<NodeAttemptSummary>(); long last = after;
        try (var statement = db.prepareStatement("SELECT attempt_id, ordinal, status, completion FROM attempt WHERE "
                + PROCESS + " AND invocation_id = ? AND ordinal > ? ORDER BY ordinal LIMIT ?")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, invocation); statement.setLong(at++, after); statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID attempt = uuid(rows, "attempt_id"); String completion = rows.getString("completion");
                    var started = items.size() < limit ? timing(db, key, invocation, attempt, false) : Optional.<Observation>empty();
                    var settled = items.size() < limit ? timing(db, key, invocation, attempt, true) : Optional.<Observation>empty();
                    items.add(new NodeAttemptSummary(attempt, rows.getInt("ordinal"), NodeAttemptStatus.valueOf(rows.getString("status")),
                            completion == null ? null : NodeAttemptCompletion.valueOf(completion), started.map(Observation::at).orElse(null),
                            settled.map(Observation::at).orElse(null), started.isPresent() || settled.isPresent()));
                    if (items.size() <= limit) last = rows.getLong("ordinal");
                }
            }
        }
        return page(items, limit, key, traversal, query, Long.toString(last));
    }
    static HistoryPage<VisitParent> parents(Connection db, ExecutionKey key, UUID traversal, UUID invocation,
            Optional<String> cursor, int limit, int maximum) throws SQLException {
        requireLimit(limit, maximum); String query = "parents:" + invocation + ':' + limit;
        UUID after = cursor.map(value -> UUID.fromString(ExecutionHistoryCursor.decode(key, traversal, query, value))).orElse(null);
        if (!visitExists(db, key, traversal, invocation)) return HistoryPage.missing();
        var items = new ArrayList<VisitParent>(); String last = "";
        try (var statement = db.prepareStatement("SELECT p.parent_invocation_id, i.traversal_id, i.node_id FROM invocation_parent p "
                + "JOIN invocation i ON i.tenant_id = p.tenant_id AND i.process_instance_id = p.process_instance_id AND i.invocation_id = p.parent_invocation_id "
                + "WHERE p.tenant_id = ? AND p.process_instance_id = ? AND p.invocation_id = ? "
                + (after == null ? "" : "AND p.parent_invocation_id > ? ") + "ORDER BY p.parent_invocation_id LIMIT ?")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, invocation);
            if (after != null) bindUuid(statement, at++, after); statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) { while (rows.next()) {
                UUID parent = uuid(rows, "parent_invocation_id");
                items.add(new VisitParent(key.processInstanceId(), uuid(rows, "traversal_id"), parent, rows.getString("node_id")));
                if (items.size() <= limit) last = parent.toString();
            }}
        }
        return page(items, limit, key, traversal, query, last);
    }
    private record Observation(Instant at, OpaquePayload data) { }
    private static Optional<Observation> timing(Connection db, ExecutionKey key, UUID invocation, UUID attempt, boolean terminal) throws SQLException {
        String types = terminal ? "event_type IN ('NODE_COMPLETED','NODE_FAILED','NODE_BYPASSED','SOURCE_BOUNDARY')" : "event_type = 'NODE_STARTED'";
        try (var statement = db.prepareStatement("SELECT occurred_at_epoch_second, occurred_at_nano, payload_content_type, "
                + "CASE WHEN payload_content_type = 'application/vnd.ravenroot.node-outcome+json;version=1' AND LENGTH(payload_bytes) <= 1024 THEN payload_bytes ELSE NULL END AS outcome_bytes FROM event_journal WHERE "
                + PROCESS + " AND invocation_id = ? AND attempt_id = ? AND " + types + " ORDER BY stream_sequence " + (terminal ? "DESC" : "ASC") + " LIMIT 1")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, invocation); bindUuid(statement, at, attempt);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                byte[] data = rows.getBytes("outcome_bytes");
                return Optional.of(new Observation(Instant.ofEpochSecond(rows.getLong("occurred_at_epoch_second"), rows.getInt("occurred_at_nano")),
                        data == null ? null : OpaquePayload.of(data, rows.getString("payload_content_type"))));
            }
        }
    }
    private static Optional<UUID> attemptId(Connection db, ExecutionKey key, UUID invocation, boolean last) throws SQLException {
        try (var statement = db.prepareStatement("SELECT attempt_id FROM attempt WHERE " + PROCESS + " AND invocation_id = ? ORDER BY ordinal "
                + (last ? "DESC" : "ASC") + " LIMIT 1")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at, invocation);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(uuid(rows, "attempt_id")) : Optional.empty(); }
        }
    }
    private static boolean visitExists(Connection db, ExecutionKey key, UUID traversal, UUID invocation) throws SQLException {
        try (var statement = db.prepareStatement("SELECT 1 FROM invocation WHERE " + TRAVERSAL + " AND invocation_id = ?")) {
            int at = bindKey(statement, 1, key); bindUuid(statement, at++, traversal); bindUuid(statement, at, invocation);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static boolean exists(Connection db, ExecutionKey key) throws SQLException {
        try (var statement = db.prepareStatement("SELECT 1 FROM process_instance WHERE " + PROCESS)) {
            bindKey(statement, 1, key); try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static long position(ExecutionKey key, UUID traversal, String query, Optional<String> cursor) {
        try {
            long value = cursor.map(text -> Long.parseLong(ExecutionHistoryCursor.decode(key, traversal, query, text))).orElse(-1L);
            if (value < -1) throw new IllegalArgumentException("invalid history position"); return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid history position"); }
    }
    private static <T> HistoryPage<T> page(List<T> rows, int limit, ExecutionKey key, UUID traversal, String query, String last) {
        boolean more = rows.size() > limit; if (more) rows.removeLast();
        return new HistoryPage<>(rows, more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query, last)) : Optional.empty(),
                HistoryPage.Availability.AVAILABLE);
    }
    private static void requireLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("history page size exceeds configured bound");
    }
    private static int bindKey(PreparedStatement statement, int at, ExecutionKey key) throws SQLException {
        statement.setString(at++, key.tenantId()); bindUuid(statement, at++, key.processInstanceId()); return at;
    }
    private static void bindUuid(PreparedStatement statement, int at, UUID value) throws SQLException { statement.setObject(at, value); }
    private static UUID uuid(ResultSet rows, String name) throws SQLException { return UUID.fromString(rows.getString(name)); }
}
