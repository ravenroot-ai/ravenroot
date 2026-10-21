package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.correlation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Transaction-local CAS state and its release intent; a READY snapshot is never published separately. */
final class CorrelatedJoinStorage {
    private static final String SCOPE = "tenant_id = ? AND deployment_id = ? AND graph_version = ?";
    private static final String KEY = SCOPE + " AND application_id = ? AND node_id = ? AND round_id = ?";
    private CorrelatedJoinStorage() { }
    static Optional<CorrelatedJoinRecord> load(Connection db, CorrelatedJoinKey key) throws SQLException {
        try (var statement = db.prepareStatement("SELECT snapshot, revision, phase, updated_ms FROM correlated_business_join WHERE " + KEY)) {
            bindKey(statement, 1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var data = rows.getBytes(1);
                if (data == null) throw new BusinessHistoryException(EXPIRED_HISTORY);
                var value = CorrelatedJoinCodec.decode(data);
                return Optional.of(new CorrelatedJoinRecord(value.key(), rows.getLong(2),
                        CorrelatedJoinRecord.Phase.valueOf(rows.getString(3)), value.expectedIds(), value.expectedCount(),
                        value.sealed(), value.contributions(), value.openedBy(), value.openedAt(), value.deadlineAt(),
                        Instant.ofEpochMilli(rows.getLong(4))));
            }
        }
    }
    static CorrelatedJoinRecord write(Connection db, CorrelatedJoinRecord desired, BusinessHistoryPolicy policy,
                                      Instant now) throws SQLException {
        var key = desired.key();
        try (var lock = db.prepareStatement("UPDATE business_history SET revision = revision WHERE " + SCOPE + " AND application_id = ?")) {
            int at = bindScope(lock, 1, key.scope()); lock.setString(at, key.applicationCorrelationId());
            if (lock.executeUpdate() == 0) throw new BusinessHistoryException(UNKNOWN_HISTORY);
        }
        BusinessHistoryStorage.expire(db, key.scope(), now);
        var prior = load(db, key);
        CorrelatedJoinTransitions.require(prior, desired, policy, now);
        var group = BusinessHistoryStorage.history(db, key.scope(), key.applicationCorrelationId()).orElseThrow();
        if ((desired.phase() == CorrelatedJoinRecord.Phase.OPEN || desired.phase() == CorrelatedJoinRecord.Phase.READY)
                && group.lifecycle() != BusinessHistory.Lifecycle.OPEN)
            throw new BusinessHistoryException(group.lifecycle() == BusinessHistory.Lifecycle.EXPIRED ? EXPIRED_HISTORY : CLOSED_HISTORY);
        if (prior.isEmpty()) {
            try (var count = db.prepareStatement("SELECT COUNT(*), SUM(CASE WHEN phase IN ('OPEN','READY') THEN 1 ELSE 0 END) "
                    + "FROM correlated_business_join WHERE " + SCOPE + " AND application_id = ?")) {
                int at = bindScope(count, 1, key.scope()); count.setString(at, key.applicationCorrelationId());
                try (var rows = count.executeQuery()) {
                    rows.next(); if (rows.getLong(1) >= policy.maxRetainedJoinsPerHistory()
                            || rows.getLong(2) >= policy.maxOpenJoinsPerHistory()) throw new BusinessHistoryException(RESOURCE_LIMIT);
                }
            }
        }
        for (var contribution : desired.contributions().values()) {
            if (prior.isPresent() && prior.orElseThrow().contributions().containsKey(contribution.contributionId())) continue;
            var relation = BusinessHistoryStorage.traversal(db, key.scope().tenantId(), contribution.processInstanceId(),
                    contribution.traversalId()).orElseThrow(() -> new BusinessHistoryException(CONTEXT_CONFLICT));
            if (!relation.scope().equals(key.scope()) || !relation.context().applicationCorrelationId().equals(key.applicationCorrelationId())
                    || !relation.context().contributionId().equals(Optional.of(contribution.contributionId()))
                    || !relation.context().roundId().equals(key.roundId())) throw new BusinessHistoryException(CONTEXT_CONFLICT);
        }
        byte[] encoded = CorrelatedJoinCodec.encode(desired);
        if (prior.isEmpty()) {
            try (var insert = db.prepareStatement("INSERT INTO correlated_business_join (tenant_id, deployment_id, graph_version, "
                    + "application_id, node_id, round_id, revision, phase, expected_count, received_count, sealed, retained_bytes, opened_ms, updated_ms, deadline_ms, continuation_process_id, "
                    + "continuation_traversal_id, snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                int at = bindKey(insert, 1, key); insert.setLong(at++, desired.revision()); insert.setString(at++, desired.phase().name());
                insert.setInt(at++, desired.expectedCount() == null ? desired.expectedIds().size() : desired.expectedCount());
                insert.setInt(at++, desired.contributions().size()); insert.setInt(at++, desired.sealed() ? 1 : 0);
                insert.setLong(at++, desired.payloadBytes()); insert.setLong(at++, desired.openedAt().toEpochMilli());
                insert.setLong(at++, desired.updatedAt().toEpochMilli());
                insert.setLong(at++, desired.deadlineAt().toEpochMilli()); insert.setString(at++, key.continuationProcessId().toString());
                insert.setString(at++, key.continuationTraversalId().toString()); insert.setBytes(at, encoded);
                if (insert.executeUpdate() == 0) throw new BusinessHistoryException(REVISION_CONFLICT);
            }
        } else {
            try (var update = db.prepareStatement("UPDATE correlated_business_join SET revision = ?, phase = ?, snapshot = ?, expected_count = ?, received_count = ?, sealed = ?, retained_bytes = ?, updated_ms = ? "
                    + "WHERE " + KEY + " AND revision = ?")) {
                update.setLong(1, desired.revision()); update.setString(2, desired.phase().name()); update.setBytes(3, encoded);
                update.setInt(4, desired.expectedCount() == null ? desired.expectedIds().size() : desired.expectedCount());
                update.setInt(5, desired.contributions().size()); update.setInt(6, desired.sealed() ? 1 : 0);
                update.setLong(7, desired.payloadBytes()); update.setLong(8, desired.updatedAt().toEpochMilli());
                int at = bindKey(update, 9, key); update.setLong(at, desired.revision() - 1);
                if (update.executeUpdate() == 0) throw new BusinessHistoryException(REVISION_CONFLICT);
            }
        }
        if (desired.phase() == CorrelatedJoinRecord.Phase.FAILED
                || desired.phase() == CorrelatedJoinRecord.Phase.EXPIRED) {
            try (var close = db.prepareStatement("UPDATE business_history SET lifecycle = ?, incomplete = ?, revision = revision + 1, "
                    + "updated_ms = ? WHERE " + SCOPE + " AND application_id = ? AND lifecycle = 'OPEN'")) {
                close.setString(1, desired.phase() == CorrelatedJoinRecord.Phase.CANCELLED ? "CANCELLED" : "FAILED");
                close.setInt(2, desired.phase() == CorrelatedJoinRecord.Phase.EXPIRED ? 1 : 0);
                close.setLong(3, now.toEpochMilli()); int at = bindScope(close, 4, key.scope()); close.setString(at, key.applicationCorrelationId());
                close.executeUpdate();
            }
            cancelPending(db, key.scope(), key.applicationCorrelationId(), now);
        }
        return desired;
    }
    static void cancelPending(Connection db, BusinessScope scope, String applicationId, Instant now) throws SQLException {
        try (var update = db.prepareStatement("UPDATE correlated_business_join SET phase = 'CANCELLED', revision = revision + 1, "
                + "updated_ms = ? WHERE " + SCOPE + " AND application_id = ? AND phase IN ('OPEN','READY')")) {
            update.setLong(1, now.toEpochMilli()); int at = bindScope(update, 2, scope); update.setString(at, applicationId); update.executeUpdate();
        }
    }
    static CorrelatedJoinPage page(Connection db, BusinessScope scope, String applicationId,
            Set<CorrelatedJoinRecord.Phase> phases, Optional<String> cursor, int limit, BusinessHistoryPolicy policy) throws SQLException {
        if (limit < 1 || limit > policy.maxPageSize()) throw new BusinessHistoryException(INVALID_REQUEST);
        var ordered = phases.stream().map(Enum::name).sorted().toList();
        String filter = "joins:" + Objects.toString(applicationId, "") + '\0' + ordered + '\0' + limit;
        var position = cursor.map(value -> BusinessCursor.decode(scope, filter, value, 3));
        var sql = new StringBuilder("SELECT application_id, node_id, round_id, revision, phase, expected_count, received_count, sealed, retained_bytes, opened_ms, deadline_ms, updated_ms FROM correlated_business_join WHERE " + SCOPE);
        if (applicationId != null) sql.append(" AND application_id = ?");
        if (!phases.isEmpty()) sql.append(" AND phase IN (").append(String.join(",", Collections.nCopies(phases.size(), "?"))).append(")");
        if (position.isPresent()) sql.append(" AND (application_id > ? OR (application_id = ? AND node_id > ?) OR "
                + "(application_id = ? AND node_id = ? AND round_id > ?))");
        sql.append(" ORDER BY application_id, node_id, round_id LIMIT ?");
        var items = new ArrayList<CorrelatedJoinSummary>();
        try (var statement = db.prepareStatement(sql.toString())) {
            int at = bindScope(statement, 1, scope);
            if (applicationId != null) statement.setString(at++, applicationId);
            for (String phase : ordered) statement.setString(at++, phase);
            if (position.isPresent()) {
                var p = position.orElseThrow();
                for (String value : List.of(p.get(0), p.get(0), p.get(1), p.get(0), p.get(1), p.get(2))) statement.setString(at++, value);
            }
            statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) { while (rows.next()) items.add(new CorrelatedJoinSummary(
                    new CorrelatedJoinKey(scope, rows.getString(1), rows.getString(2), rows.getString(3)),
                    rows.getLong(4), CorrelatedJoinRecord.Phase.valueOf(rows.getString(5)), rows.getInt(6), rows.getInt(7),
                    rows.getInt(8) != 0, rows.getLong(9), Instant.ofEpochMilli(rows.getLong(10)),
                    Instant.ofEpochMilli(rows.getLong(11)), Instant.ofEpochMilli(rows.getLong(12)))); }
        }
        boolean more = items.size() > limit; if (more) items.removeLast();
        Optional<String> next = Optional.empty();
        if (more) { var last = items.getLast().key(); next = Optional.of(BusinessCursor.encode(scope, filter,
                List.of(last.applicationCorrelationId(), last.nodeId(), last.roundId()))); }
        return new CorrelatedJoinPage(items, next);
    }
    private static int bindScope(PreparedStatement statement, int at, BusinessScope scope) throws SQLException {
        statement.setString(at++, scope.tenantId()); statement.setString(at++, scope.deploymentId());
        statement.setString(at++, scope.graphVersion()); return at;
    }
    private static int bindKey(PreparedStatement statement, int at, CorrelatedJoinKey key) throws SQLException {
        at = bindScope(statement, at, key.scope()); statement.setString(at++, key.applicationCorrelationId());
        statement.setString(at++, key.nodeId()); statement.setString(at++, key.roundId()); return at;
    }
}
