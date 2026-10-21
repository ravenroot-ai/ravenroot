package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.HistoryPage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.persistence.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Transaction-local retained input custody; scalar recovery pages never select the snapshot BLOB. */
final class DeploymentEntryStorage {
    private static final String SCOPE = "tenant_id = ? AND deployment_id = ? AND graph_version = ?";
    private DeploymentEntryStorage() { }
    static Optional<DeploymentEntryIntent> load(Connection db, ExecutionKey key) throws SQLException {
        try (var statement = db.prepareStatement("SELECT snapshot, snapshot_digest FROM deployment_entry_intent WHERE tenant_id = ? AND process_id = ?")) {
            statement.setString(1, key.tenantId()); statement.setString(2, key.processInstanceId().toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try {
                    byte[] snapshot = rows.getBytes(1);
                    if (!digest(snapshot).equals(rows.getString(2))) throw new IllegalArgumentException("entry digest mismatch");
                    var record = DeploymentEntryCodec.decode(snapshot);
                    if (!record.key().equals(key)) throw new IllegalArgumentException("entry owner mismatch");
                    return Optional.of(record);
                } catch (IllegalArgumentException invalid) {
                    throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key, "retained source input integrity failed"));
                }
            }
        }
    }
    static DeploymentEntryAdmission admit(Connection db, DeploymentEntryIntent proposed, CorrelationMapping.Mode mode,
            BusinessHistoryPolicy policy, Instant now) throws SQLException {
        Objects.requireNonNull(mode); var scope = proposed.scope();
        if (proposed.checkpoint() != null) throw new BusinessHistoryException(INVALID_REQUEST);
        if (proposed.input().size() > policy.maxPendingEntryBytesPerScope()) throw new BusinessHistoryException(RESOURCE_LIMIT);
        PayloadJson.read(proposed.input().bytes(), policy.selection().input());
        if (!(PayloadJson.read(proposed.attributes().bytes(), policy.selection().input()) instanceof ai.ravenroot.api.payload.PayloadValue.MapValue))
            throw new BusinessHistoryException(INVALID_REQUEST);
        try (var insert = db.prepareStatement("INSERT INTO business_scope (tenant_id, deployment_id, graph_version) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            bindScope(insert, 1, scope); insert.executeUpdate();
        }
        try (var lock = db.prepareStatement("UPDATE business_scope SET graph_version = graph_version WHERE " + SCOPE)) {
            bindScope(lock, 1, scope); lock.executeUpdate();
        }
        var existing = load(db, proposed.key());
        if (existing.isPresent()) {
            existing.orElseThrow().requireSameAdmission(proposed);
            return new DeploymentEntryAdmission(DeploymentEntryAdmission.Outcome.PENDING, existing);
        }
        var prior = BusinessHistoryStorage.traversal(db, scope.tenantId(), proposed.processInstanceId(), proposed.traversalId());
        if (prior.isPresent()) {
            var original = prior.orElseThrow();
            if (!original.scope().equals(scope) || !original.context().equals(proposed.context())
                    || !original.entryNodeId().equals(proposed.entryNodeId()) || original.cause() != proposed.cause()
                    || !Objects.equals(original.derivation(), proposed.traversal().derivation()))
                throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return new DeploymentEntryAdmission(original.status().terminal() ? DeploymentEntryAdmission.Outcome.SETTLED
                    : DeploymentEntryAdmission.Outcome.INPUT_UNAVAILABLE, Optional.empty());
        }
        if (proposed.cause() == CorrelatedTraversal.Cause.DERIVED_CONTEXT) {
            var cause = proposed.derivedFrom();
            var original = BusinessHistoryStorage.traversal(db, scope.tenantId(), cause.processInstanceId(), cause.traversalId())
                    .orElseThrow(() -> new BusinessHistoryException(CONTEXT_CONFLICT));
            if (!original.scope().equals(scope) || original.context().applicationCorrelationId().equals(proposed.context().applicationCorrelationId())
                    || !ExecutionHistoryStorage.selectionExists(db, new ExecutionKey(scope.tenantId(), cause.processInstanceId()),
                            cause.traversalId(), Optional.of(cause.invocationId()), Optional.of(cause.attemptId())))
                throw new BusinessHistoryException(CONTEXT_CONFLICT);
        }
        var lifetime = java.time.Duration.between(proposed.acceptedAt(), proposed.expiresAt());
        if (lifetime.compareTo(policy.historyRetention()) > 0) lifetime = policy.historyRetention();
        var accepted = new DeploymentEntryIntent(scope, proposed.processInstanceId(), proposed.traversalId(), proposed.entryNodeId(),
                proposed.context(), proposed.identity(), proposed.input(), now, now.plus(lifetime), proposed.cause(),
                proposed.derivedFrom(), proposed.derivationReason(), null, proposed.attributes(), proposed.command());
        byte[] encoded = DeploymentEntryCodec.encode(accepted);
        try (var count = db.prepareStatement("SELECT COUNT(*), COALESCE(SUM(retained_bytes), 0) FROM deployment_entry_intent WHERE " + SCOPE)) {
            bindScope(count, 1, scope);
            try (var rows = count.executeQuery()) {
                rows.next();
                if (rows.getLong(1) >= policy.maxPendingEntriesPerScope()
                        || rows.getLong(2) > policy.maxPendingEntryBytesPerScope() - encoded.length - 128)
                    throw new BusinessHistoryException(RESOURCE_LIMIT);
            }
        }
        BusinessHistoryStorage.admit(db, accepted.traversal(), mode, policy, now);
        try (var insert = db.prepareStatement("INSERT INTO deployment_entry_intent (tenant_id, process_id, traversal_id, deployment_id, "
                + "graph_version, entry_node_id, application_id, cause, input_bytes, retained_bytes, accepted_seconds, accepted_nano, "
                + "expires_seconds, expires_nano, entry_started, entry_completed, snapshot, snapshot_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)")) {
            insert.setString(1, scope.tenantId()); insert.setString(2, accepted.processInstanceId().toString());
            insert.setString(3, accepted.traversalId().toString()); insert.setString(4, scope.deploymentId()); insert.setString(5, scope.graphVersion());
            insert.setString(6, accepted.entryNodeId()); insert.setString(7, accepted.context().applicationCorrelationId());
            insert.setString(8, accepted.cause().name()); insert.setInt(9, accepted.input().size()); insert.setInt(10, encoded.length + 128);
            insert.setLong(11, accepted.acceptedAt().getEpochSecond()); insert.setInt(12, accepted.acceptedAt().getNano());
            insert.setLong(13, accepted.expiresAt().getEpochSecond()); insert.setInt(14, accepted.expiresAt().getNano());
            insert.setBytes(15, encoded); insert.setString(16, digest(encoded)); insert.executeUpdate();
        }
        return new DeploymentEntryAdmission(DeploymentEntryAdmission.Outcome.ACCEPTED, Optional.of(accepted));
    }
    static HistoryPage<DeploymentEntrySummary> page(Connection db, BusinessScope scope, Optional<String> cursor,
            int limit, BusinessHistoryPolicy policy) throws SQLException {
        if (limit < 1 || limit > policy.maxPageSize()) throw new BusinessHistoryException(INVALID_REQUEST);
        String query = "deployment-entries:" + limit;
        String after = cursor.map(value -> BusinessCursor.decode(scope, query, value, 1).getFirst()).orElse("");
        if (!after.isEmpty()) UUID.fromString(after);
        var items = new ArrayList<DeploymentEntrySummary>();
        try (var statement = db.prepareStatement("SELECT process_id, traversal_id, entry_node_id, application_id, cause, input_bytes, "
                + "accepted_seconds, accepted_nano, expires_seconds, expires_nano, entry_started, entry_completed "
                + "FROM deployment_entry_intent WHERE " + SCOPE + " AND process_id > ? ORDER BY process_id LIMIT ?")) {
            int at = bindScope(statement, 1, scope); statement.setString(at++, after); statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) items.add(new DeploymentEntrySummary(scope, UUID.fromString(rows.getString("process_id")),
                        UUID.fromString(rows.getString("traversal_id")), rows.getString("entry_node_id"), rows.getString("application_id"),
                        CorrelatedTraversal.Cause.valueOf(rows.getString("cause")), rows.getLong("input_bytes"),
                        Instant.ofEpochSecond(rows.getLong("accepted_seconds"), rows.getInt("accepted_nano")),
                        Instant.ofEpochSecond(rows.getLong("expires_seconds"), rows.getInt("expires_nano")),
                        rows.getInt("entry_started") != 0, rows.getInt("entry_completed") != 0));
            }
        }
        boolean more = items.size() > limit; if (more) items.removeLast();
        return new HistoryPage<>(items, more ? Optional.of(BusinessCursor.encode(scope, query,
                List.of(items.getLast().processInstanceId().toString()))) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    static void project(Connection db, ExecutionBatch batch, ai.ravenroot.api.application.ProcessInstance folded) throws SQLException {
        var entry = load(db, batch.key()).orElse(null); if (entry == null) return;
        var traversal = folded.traversals().get(entry.traversalId());
        if (folded.status().terminal() || traversal != null && traversal.status().terminal()) {
            try (var delete = db.prepareStatement("DELETE FROM deployment_entry_intent WHERE tenant_id = ? AND process_id = ?")) {
                delete.setString(1, batch.key().tenantId()); delete.setString(2, batch.key().processInstanceId().toString()); delete.executeUpdate();
            }
            return;
        }
        var checkpoint = DeploymentEntryCheckpoint.project(entry, batch, folded);
        if (Objects.equals(checkpoint, entry.checkpoint())) return;
        var updated = entry.checkpointed(checkpoint); byte[] snapshot = DeploymentEntryCodec.encode(updated);
        try (var update = db.prepareStatement("UPDATE deployment_entry_intent SET snapshot = ?, snapshot_digest = ?, entry_started = 1, entry_completed = ? WHERE tenant_id = ? AND process_id = ?")) {
            update.setBytes(1, snapshot); update.setString(2, digest(snapshot)); update.setInt(3, checkpoint.completedEventId() == null ? 0 : 1);
            update.setString(4, batch.key().tenantId()); update.setString(5, batch.key().processInstanceId().toString()); update.executeUpdate();
        }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int bindScope(PreparedStatement statement, int at, BusinessScope scope) throws SQLException {
        statement.setString(at++, scope.tenantId()); statement.setString(at++, scope.deploymentId()); statement.setString(at++, scope.graphVersion()); return at;
    }
}
