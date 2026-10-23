package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.application.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Normalized history metadata. Every mutation runs in the owning execution-store transaction. */
final class BusinessHistoryStorage {
    private BusinessHistoryStorage() { }
    private static final String SCOPE = "tenant_id = ? AND deployment_id = ? AND graph_version = ?";

    static BusinessHistory admit(Connection db, CorrelatedTraversal proposed, CorrelationMapping.Mode mode,
                                 BusinessHistoryPolicy policy, Instant now) throws SQLException {
        Objects.requireNonNull(mode, "mode");
        var scope = proposed.scope();
        String id = policy.selection().requireId(proposed.context().applicationCorrelationId(), "applicationCorrelationId");
        var prior = traversal(db, scope.tenantId(), proposed.processInstanceId(), proposed.traversalId());
        if (prior.isPresent()) {
            if (!sameAdmission(prior.orElseThrow(), proposed)) throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return history(db, scope, id).orElseThrow();
        }
        try (var insertScope = db.prepareStatement("INSERT INTO business_scope (tenant_id, deployment_id, graph_version) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            bindScope(insertScope, 1, scope); insertScope.executeUpdate();
        }
        try (var lockScope = db.prepareStatement("UPDATE business_scope SET graph_version = graph_version WHERE " + SCOPE)) {
            bindScope(lockScope, 1, scope); lockScope.executeUpdate();
        }
        expire(db, scope, now);
        var existing = history(db, scope, id);
        if (existing.isEmpty()) {
            if (mode == CorrelationMapping.Mode.REQUIRE_EXISTING) throw new BusinessHistoryException(UNKNOWN_HISTORY);
            try (var count = db.prepareStatement("SELECT COUNT(*) FROM business_history WHERE " + SCOPE)) {
                bindScope(count, 1, scope);
                try (var rows = count.executeQuery()) {
                    rows.next(); if (rows.getLong(1) >= policy.maxHistoriesPerScope()) throw new BusinessHistoryException(RESOURCE_LIMIT);
                }
            }
            try (var insert = db.prepareStatement("INSERT INTO business_history (tenant_id, deployment_id, graph_version, "
                    + "application_id, public_reference, lifecycle, revision, running_count, completed_count, failed_count, "
                    + "cancelled_count, incomplete, created_ms, updated_ms, expires_ms) "
                    + "VALUES (?, ?, ?, ?, ?, 'OPEN', 1, 0, 0, 0, 0, 0, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                int at = bindScope(insert, 1, scope);
                insert.setString(at++, id); insert.setString(at++, proposed.context().publicReference().orElse(null));
                insert.setLong(at++, now.toEpochMilli()); insert.setLong(at++, now.toEpochMilli());
                insert.setLong(at, now.plus(policy.historyRetention()).toEpochMilli()); insert.executeUpdate();
            }
            existing = history(db, scope, id);
        }
        BusinessHistory group = existing.orElseThrow();
        if (group.lifecycle() == BusinessHistory.Lifecycle.EXPIRED) throw new BusinessHistoryException(EXPIRED_HISTORY);
        boolean causalContinuation = readyContinuation(db, proposed);
        if (group.lifecycle() != BusinessHistory.Lifecycle.OPEN || group.closureSignal() != null
                && !causalContinuation) throw new BusinessHistoryException(CLOSED_HISTORY);
        if (group.publicReference().isPresent() && proposed.context().publicReference().isPresent()
                && !group.publicReference().equals(proposed.context().publicReference())) throw new BusinessHistoryException(CONTEXT_CONFLICT);
        int inserted;
        try (var insert = db.prepareStatement("INSERT INTO correlated_traversal (tenant_id, process_id, traversal_id, "
                + "deployment_id, graph_version, application_id, contribution_id, contribution_kind, round_id, "
                + "public_reference, entry_node_id, cause, status, termination_reason, started_ms, updated_ms, ended_ms, derived_process_id, derived_traversal_id, derived_invocation_id, derived_attempt_id, derivation_reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', NULL, ?, ?, NULL, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, scope.tenantId()); insert.setString(2, proposed.processInstanceId().toString());
            insert.setString(3, proposed.traversalId().toString()); insert.setString(4, scope.deploymentId());
            insert.setString(5, scope.graphVersion()); insert.setString(6, id);
            insert.setString(7, proposed.context().contributionId().orElse(null));
            insert.setString(8, proposed.context().contributionKind().orElse(null));
            insert.setString(9, proposed.context().roundId());
            insert.setString(10, proposed.context().publicReference().orElse(null));
            insert.setString(11, proposed.entryNodeId()); insert.setString(12, proposed.cause().name());
            insert.setLong(13, now.toEpochMilli()); insert.setLong(14, now.toEpochMilli());
            var lineage = proposed.derivation();
            insert.setString(15, lineage == null ? null : lineage.processInstanceId().toString());
            insert.setString(16, lineage == null ? null : lineage.traversalId().toString());
            insert.setString(17, lineage == null ? null : lineage.invocationId().toString());
            insert.setString(18, lineage == null ? null : lineage.attemptId().toString());
            insert.setString(19, lineage == null ? null : lineage.auditReason());
            inserted = insert.executeUpdate();
        }
        if (inserted != 0) {
            try (var update = db.prepareStatement("UPDATE business_history SET running_count = running_count + 1, "
                    + "revision = revision + 1, updated_ms = ?, public_reference = COALESCE(public_reference, ?) WHERE "
                    + SCOPE + " AND application_id = ? AND lifecycle = 'OPEN'"
                    + (causalContinuation ? "" : " AND requested_closure IS NULL"))) {
                update.setLong(1, now.toEpochMilli()); update.setString(2, proposed.context().publicReference().orElse(null));
                int at = bindScope(update, 3, scope); update.setString(at, id);
                if (update.executeUpdate() == 0) throw new BusinessHistoryException(CLOSED_HISTORY);
            }
        } else if (!sameAdmission(traversal(db, scope.tenantId(), proposed.processInstanceId(),
                proposed.traversalId()).orElseThrow(), proposed)) throw new BusinessHistoryException(CONTEXT_CONFLICT);
        return history(db, scope, id).orElseThrow();
    }

    private static boolean readyContinuation(Connection db, CorrelatedTraversal proposed) throws SQLException {
        if (proposed.cause() != CorrelatedTraversal.Cause.JOIN_CONTINUATION) return false;
        try (var statement = db.prepareStatement("SELECT 1 FROM correlated_business_join WHERE " + SCOPE
                + " AND application_id = ? AND node_id = ? AND round_id = ? AND phase = 'READY' AND continuation_process_id = ? AND continuation_traversal_id = ?")) {
            int at = bindScope(statement, 1, proposed.scope()); statement.setString(at++, proposed.context().applicationCorrelationId());
            statement.setString(at++, proposed.entryNodeId()); statement.setString(at++, proposed.context().roundId());
            statement.setString(at++, proposed.processInstanceId().toString()); statement.setString(at, proposed.traversalId().toString());
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    static Optional<BusinessHistory> history(Connection db, BusinessScope scope, String id) throws SQLException {
        try (var statement = db.prepareStatement("SELECT * FROM business_history WHERE " + SCOPE + " AND application_id = ?")) {
            int at = bindScope(statement, 1, scope); statement.setString(at, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(readHistory(rows, scope)) : Optional.empty(); }
        }
    }

    static BusinessHistoryPage histories(Connection db, BusinessScope scope, BusinessHistoryQuery query,
                                           BusinessHistoryPolicy policy, Instant now) throws SQLException {
        requirePage(query.limit(), policy);
        expire(db, scope, now);
        String filter = historyFilter(query);
        var values = new ArrayList<Object>();
        var sql = new StringBuilder("SELECT * FROM business_history WHERE " + SCOPE);
        query.applicationCorrelationId().ifPresent(id -> { sql.append(" AND application_id = ?"); values.add(id); });
        query.publicReference().ifPresent(reference -> { sql.append(" AND public_reference = ?"); values.add(reference); });
        if (!query.lifecycles().isEmpty()) {
            sql.append(" AND lifecycle IN (");
            var states = query.lifecycles().stream().map(Enum::name).sorted().toList();
            sql.append(String.join(",", Collections.nCopies(states.size(), "?"))).append(")"); values.addAll(states);
        }
        if (query.cursor().isPresent()) {
            String after = BusinessCursor.decode(scope, filter, query.cursor().orElseThrow(), 1).getFirst();
            sql.append(" AND application_id > ?"); values.add(after);
        }
        sql.append(" ORDER BY application_id LIMIT ?"); values.add(query.limit() + 1);
        var items = new ArrayList<BusinessHistory>();
        try (var statement = db.prepareStatement(sql.toString())) {
            int at = bindScope(statement, 1, scope);
            for (Object value : values) statement.setObject(at++, value);
            try (var rows = statement.executeQuery()) { while (rows.next()) items.add(readHistory(rows, scope)); }
        }
        boolean more = items.size() > query.limit();
        if (more) items.removeLast();
        return new BusinessHistoryPage(items, more ? Optional.of(BusinessCursor.encode(scope, filter,
                List.of(items.getLast().applicationCorrelationId()))) : Optional.empty());
    }

    static Optional<CorrelatedTraversal> traversal(Connection db, String tenant, UUID process, UUID traversal) throws SQLException {
        try (var statement = db.prepareStatement("SELECT * FROM correlated_traversal WHERE tenant_id = ? AND process_id = ? AND traversal_id = ?")) {
            statement.setString(1, tenant); statement.setString(2, process.toString()); statement.setString(3, traversal.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(readTraversal(rows)) : Optional.empty(); }
        }
    }

    static CorrelatedTraversalPage traversals(Connection db, BusinessScope scope, String id, Optional<String> cursor,
                                               int limit, BusinessHistoryPolicy policy) throws SQLException {
        requirePage(limit, policy);
        String filter = "traversals:" + id;
        List<String> after = cursor.isPresent() ? BusinessCursor.decode(scope, filter, cursor.orElseThrow(), 3) : List.of();
        var sql = new StringBuilder("SELECT * FROM correlated_traversal WHERE " + SCOPE + " AND application_id = ?");
        if (!after.isEmpty()) sql.append(" AND (started_ms > ? OR (started_ms = ? AND process_id > ?) "
                + "OR (started_ms = ? AND process_id = ? AND traversal_id > ?))");
        sql.append(" ORDER BY started_ms, process_id, traversal_id LIMIT ?");
        var items = new ArrayList<CorrelatedTraversal>();
        try (var statement = db.prepareStatement(sql.toString())) {
            int at = bindScope(statement, 1, scope); statement.setString(at++, id);
            if (!after.isEmpty()) {
                final long time;
                try { time = Long.parseLong(after.getFirst()); } catch (NumberFormatException invalid) { throw new BusinessHistoryException(INVALID_REQUEST); }
                statement.setLong(at++, time); statement.setLong(at++, time); statement.setString(at++, after.get(1));
                statement.setLong(at++, time); statement.setString(at++, after.get(1)); statement.setString(at++, after.get(2));
            }
            statement.setInt(at, limit + 1);
            try (var rows = statement.executeQuery()) { while (rows.next()) items.add(readTraversal(rows)); }
        }
        boolean more = items.size() > limit;
        if (more) items.removeLast();
        Optional<String> next = Optional.empty();
        if (more) { var last = items.getLast(); next = Optional.of(BusinessCursor.encode(scope, filter,
                List.of(Long.toString(last.startedAt().toEpochMilli()), last.processInstanceId().toString(), last.traversalId().toString()))); }
        return new CorrelatedTraversalPage(items, next);
    }

    static void settle(Connection db, String tenant, UUID process, UUID traversal, TraversalStatus status,
                         ExecutionTerminationReason reason, Instant now) throws SQLException {
        if (!status.terminal()) throw new BusinessHistoryException(INVALID_REQUEST);
        var found = traversal(db, tenant, process, traversal);
        if (found.isEmpty()) return; // Historical/standalone executions honestly remain uncorrelated.
        var prior = found.orElseThrow();
        if (prior.status().terminal()) {
            if (prior.status() != status || prior.terminationReason() != reason) throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return;
        }
        int changed;
        try (var update = db.prepareStatement("UPDATE correlated_traversal SET status = ?, termination_reason = ?, "
                + "updated_ms = ?, ended_ms = ? WHERE tenant_id = ? AND process_id = ? AND traversal_id = ? "
                + "AND status NOT IN ('COMPLETED', 'FAILED')")) {
            update.setString(1, status.name()); update.setString(2, reason == null ? null : reason.name());
            update.setLong(3, now.toEpochMilli()); update.setLong(4, now.toEpochMilli());
            update.setString(5, tenant); update.setString(6, process.toString()); update.setString(7, traversal.toString());
            changed = update.executeUpdate();
        }
        if (changed == 0) {
            var settled = traversal(db, tenant, process, traversal).orElseThrow();
            if (settled.status() != status || settled.terminationReason() != reason)
                throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return;
        }
        String count = reason == ExecutionTerminationReason.CANCELLED ? "cancelled_count"
                : status == TraversalStatus.COMPLETED ? "completed_count" : "failed_count";
        try (var update = db.prepareStatement("UPDATE business_history SET running_count = running_count - 1, "
                + count + " = " + count + " + 1, revision = revision + 1, updated_ms = ? WHERE "
                + SCOPE + " AND application_id = ?")) {
            update.setLong(1, now.toEpochMilli()); int at = bindScope(update, 2, prior.scope());
            update.setString(at, prior.context().applicationCorrelationId()); update.executeUpdate();
        }
        finishRequestedClosure(db, prior.scope(), prior.context().applicationCorrelationId(), now);
    }

    static void writeClosureSignals(Connection db, ai.ravenroot.api.persistence.ExecutionBatch batch,
            ai.ravenroot.api.application.ProcessInstance folded, Instant now) throws SQLException {
        for (var proposed : batch.businessClosureSignals()) {
            var relation = traversal(db, batch.key().tenantId(), batch.key().processInstanceId(), proposed.traversalId()).orElse(null);
            proposed.requireAuthority(batch, folded, relation);
            try (var lock = db.prepareStatement("UPDATE business_history SET revision = revision WHERE " + SCOPE + " AND application_id = ?")) {
                int at = bindScope(lock, 1, proposed.scope()); lock.setString(at, proposed.applicationCorrelationId());
                if (lock.executeUpdate() == 0) throw new BusinessHistoryException(UNKNOWN_HISTORY);
            }
            var current = history(db, proposed.scope(), proposed.applicationCorrelationId()).orElseThrow();
            if (current.closureSignal() != null) {
                if (!current.closureSignal().sameRequest(proposed)) throw new BusinessHistoryException(CONTEXT_CONFLICT);
                continue;
            }
            if (current.lifecycle() != BusinessHistory.Lifecycle.OPEN || !now.isBefore(current.expiresAt())) throw new BusinessHistoryException(CLOSED_HISTORY);
            try (var update = db.prepareStatement("UPDATE business_history SET requested_closure = ?, closure_reason = ?, closure_process_id = ?, "
                    + "closure_traversal_id = ?, closure_invocation_id = ?, closure_attempt_id = ?, closure_requested_ms = ?, revision = revision + 1, updated_ms = ? "
                    + "WHERE " + SCOPE + " AND application_id = ? AND lifecycle = 'OPEN' AND requested_closure IS NULL")) {
                update.setString(1, proposed.requested().name()); update.setString(2, proposed.auditReason());
                update.setString(3, proposed.processInstanceId().toString()); update.setString(4, proposed.traversalId().toString());
                update.setString(5, proposed.invocationId().toString()); update.setString(6, proposed.attemptId().toString());
                update.setLong(7, now.toEpochMilli()); update.setLong(8, now.toEpochMilli()); int at = bindScope(update, 9, proposed.scope());
                update.setString(at, proposed.applicationCorrelationId());
                if (update.executeUpdate() != 1) throw new BusinessHistoryException(REVISION_CONFLICT);
            }
            finishRequestedClosure(db, proposed.scope(), proposed.applicationCorrelationId(), now);
        }
    }

    static void finishRequestedClosure(Connection db, BusinessScope scope, String id, Instant now) throws SQLException {
        var group = history(db, scope, id).orElse(null);
        if (group == null || group.lifecycle() != BusinessHistory.Lifecycle.OPEN || group.closureSignal() == null || group.runningTraversals() != 0) return;
        boolean missing;
        try (var count = db.prepareStatement("SELECT SUM(CASE WHEN phase = 'READY' THEN 1 ELSE 0 END), SUM(CASE WHEN phase = 'OPEN' THEN 1 ELSE 0 END) "
                + "FROM correlated_business_join WHERE " + SCOPE + " AND application_id = ?")) {
            int at = bindScope(count, 1, scope); count.setString(at, id);
            try (var rows = count.executeQuery()) { rows.next(); if (rows.getLong(1) != 0) return; missing = rows.getLong(2) != 0; }
        }
        var requested = group.closureSignal().requested();
        var outcome = requested == BusinessHistory.Lifecycle.COMPLETED && (missing || group.failedTraversals() > 0 || group.cancelledTraversals() > 0)
                ? BusinessHistory.Lifecycle.FAILED : requested;
        try (var update = db.prepareStatement("UPDATE business_history SET lifecycle = ?, incomplete = ?, revision = revision + 1, updated_ms = ? "
                + "WHERE " + SCOPE + " AND application_id = ? AND lifecycle = 'OPEN' AND running_count = 0")) {
            update.setString(1, outcome.name()); update.setInt(2, missing || group.incompleteHistory() ? 1 : 0); update.setLong(3, now.toEpochMilli());
            int at = bindScope(update, 4, scope); update.setString(at, id); update.executeUpdate();
        }
        // Scalar terminal state overlays the retained snapshot in load(); received/missing member
        // metadata and original contribution payload availability are preserved.
        try (var update = db.prepareStatement("UPDATE correlated_business_join SET phase = ?, revision = revision + 1, updated_ms = ? "
                + "WHERE " + SCOPE + " AND application_id = ? AND phase = 'OPEN'")) {
            update.setString(1, outcome == BusinessHistory.Lifecycle.CANCELLED ? "CANCELLED" : "FAILED"); update.setLong(2, now.toEpochMilli());
            int at = bindScope(update, 3, scope); update.setString(at, id); update.executeUpdate();
        }
    }

    static BusinessHistory close(Connection db, BusinessScope scope, String id, long revision,
                                   BusinessHistory.Lifecycle closure, Instant now) throws SQLException {
        if (closure == null || closure == BusinessHistory.Lifecycle.OPEN || closure == BusinessHistory.Lifecycle.EXPIRED)
            throw new BusinessHistoryException(INVALID_REQUEST);
        expire(db, scope, now);
        try (var update = db.prepareStatement("UPDATE business_history SET lifecycle = ?, revision = revision + 1, updated_ms = ? "
                + "WHERE " + SCOPE + " AND application_id = ? AND revision = ? AND lifecycle = 'OPEN' AND requested_closure IS NULL "
                + (closure == BusinessHistory.Lifecycle.COMPLETED ? "AND running_count = 0 AND failed_count = 0 AND cancelled_count = 0" : ""))) {
            update.setString(1, closure.name()); update.setLong(2, now.toEpochMilli()); int at = bindScope(update, 3, scope);
            update.setString(at++, id); update.setLong(at, revision);
            if (update.executeUpdate() == 0) throw new BusinessHistoryException(REVISION_CONFLICT);
        }
        CorrelatedJoinStorage.cancelPending(db, scope, id, now);
        return history(db, scope, id).orElseThrow();
    }

    static void expire(Connection db, BusinessScope scope, Instant now) throws SQLException {
        try (var update = db.prepareStatement("UPDATE business_history SET lifecycle = 'EXPIRED', incomplete = 1, "
                + "revision = revision + 1, updated_ms = ? WHERE " + SCOPE + " AND lifecycle = 'OPEN' AND expires_ms <= ?")) {
            update.setLong(1, now.toEpochMilli()); int at = bindScope(update, 2, scope); update.setLong(at, now.toEpochMilli());
            update.executeUpdate();
        }
        // Payload retention is independent from compact key tombstones. No late event can
        // recreate the generation after dedicated execution data has been reclaimed.
        try (var update = db.prepareStatement("UPDATE correlated_business_join SET snapshot = NULL, retained_bytes = 0, "
                + "phase = CASE WHEN phase IN ('OPEN','READY') THEN 'EXPIRED' ELSE phase END, revision = revision + 1, updated_ms = ? "
                + "WHERE " + SCOPE + " AND snapshot IS NOT NULL AND application_id IN (SELECT application_id FROM business_history WHERE "
                + SCOPE + " AND expires_ms <= ?)")) {
            update.setLong(1, now.toEpochMilli()); int at = bindScope(update, 2, scope);
            at = bindScope(update, at, scope); update.setLong(at, now.toEpochMilli()); update.executeUpdate();
        }
    }

    private static BusinessHistory readHistory(ResultSet rows, BusinessScope scope) throws SQLException {
        return new BusinessHistory(scope, rows.getString("application_id"), Optional.ofNullable(rows.getString("public_reference")),
                BusinessHistory.Lifecycle.valueOf(rows.getString("lifecycle")), rows.getLong("revision"),
                rows.getLong("running_count"), rows.getLong("completed_count"), rows.getLong("failed_count"),
                rows.getLong("cancelled_count"), rows.getInt("incomplete") != 0,
                Instant.ofEpochMilli(rows.getLong("created_ms")), Instant.ofEpochMilli(rows.getLong("updated_ms")),
                Instant.ofEpochMilli(rows.getLong("expires_ms")), rows.getString("requested_closure") == null ? null
                        : new BusinessClosureSignal(scope, rows.getString("application_id"), BusinessHistory.Lifecycle.valueOf(rows.getString("requested_closure")),
                                rows.getString("closure_reason"), StoredUuid.required(rows.getString("closure_process_id"), "business_history", "closure_process_id", scope.tenantId()), StoredUuid.required(rows.getString("closure_traversal_id"), "business_history", "closure_traversal_id", scope.tenantId()),
                                StoredUuid.required(rows.getString("closure_invocation_id"), "business_history", "closure_invocation_id", scope.tenantId()), StoredUuid.required(rows.getString("closure_attempt_id"), "business_history", "closure_attempt_id", scope.tenantId()),
                                Instant.ofEpochMilli(rows.getLong("closure_requested_ms"))));
    }
    private static CorrelatedTraversal readTraversal(ResultSet rows) throws SQLException {
        String reason = rows.getString("termination_reason"); long end = rows.getLong("ended_ms");
        Instant ended = rows.wasNull() ? null : Instant.ofEpochMilli(end);
        return new CorrelatedTraversal(new BusinessScope(rows.getString("tenant_id"), rows.getString("deployment_id"), rows.getString("graph_version")),
                new ApplicationCorrelationContext(rows.getString("application_id"), Optional.ofNullable(rows.getString("contribution_id")),
                        Optional.ofNullable(rows.getString("contribution_kind")), rows.getString("round_id"), Optional.ofNullable(rows.getString("public_reference"))),
                StoredUuid.required(rows.getString("process_id"), "correlated_traversal", "process_id", rows.getString("tenant_id")), StoredUuid.required(rows.getString("traversal_id"), "correlated_traversal", "traversal_id", rows.getString("tenant_id")), rows.getString("entry_node_id"),
                CorrelatedTraversal.Cause.valueOf(rows.getString("cause")), TraversalStatus.valueOf(rows.getString("status")),
                reason == null ? null : ExecutionTerminationReason.valueOf(reason), Instant.ofEpochMilli(rows.getLong("started_ms")),
                Instant.ofEpochMilli(rows.getLong("updated_ms")), ended, rows.getString("derived_process_id") == null ? null
                        : new CorrelationDerivation(StoredUuid.required(rows.getString("derived_process_id"), "correlated_traversal", "derived_process_id", rows.getString("tenant_id")), StoredUuid.required(rows.getString("derived_traversal_id"), "correlated_traversal", "derived_traversal_id", rows.getString("tenant_id")),
                                StoredUuid.required(rows.getString("derived_invocation_id"), "correlated_traversal", "derived_invocation_id", rows.getString("tenant_id")), StoredUuid.required(rows.getString("derived_attempt_id"), "correlated_traversal", "derived_attempt_id", rows.getString("tenant_id")), rows.getString("derivation_reason")));
    }
    private static int bindScope(PreparedStatement statement, int at, BusinessScope scope) throws SQLException {
        statement.setString(at++, scope.tenantId()); statement.setString(at++, scope.deploymentId());
        statement.setString(at++, scope.graphVersion()); return at;
    }
    private static boolean sameAdmission(CorrelatedTraversal left, CorrelatedTraversal right) {
        return left.scope().equals(right.scope()) && left.context().equals(right.context())
                && left.processInstanceId().equals(right.processInstanceId()) && left.traversalId().equals(right.traversalId())
                && left.entryNodeId().equals(right.entryNodeId()) && left.cause() == right.cause() && Objects.equals(left.derivation(), right.derivation());
    }
    private static void requirePage(int limit, BusinessHistoryPolicy policy) {
        if (limit < 1 || limit > policy.maxPageSize()) throw new BusinessHistoryException(INVALID_REQUEST);
    }
    private static String historyFilter(BusinessHistoryQuery query) {
        return "histories:" + query.applicationCorrelationId().orElse("") + '\0' + query.publicReference().orElse("")
                + '\0' + query.lifecycles().stream().map(Enum::name).sorted().toList() + '\0' + query.limit();
    }
}
