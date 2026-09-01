package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptCompletion;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the PERS-01 aggregate as normalized rows.
 *
 * <h2>Reconstruction is revalidation</h2>
 * <p>{@link #read} builds the aggregate through {@link ProcessInstance}'s canonical constructor and
 * those of every type below it, which is where identity uniqueness, the cross-traversal parent rule
 * and acyclicity are enforced. That is not defensive duplication: for a store that folds state off
 * disk it is the only place {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} can
 * be detected at all. Rows edited outside this adapter, a half-migrated schema or a genuine bug in
 * {@link #write} all surface here as a rejected construction rather than as an illegal aggregate
 * escaping into the runtime. The caller maps the rejection; this class only propagates it.</p>
 *
 * <h2>Why the whole aggregate is rewritten on every batch</h2>
 * <p>{@link #write} deletes the instance's traversal rows — cascading to invocations, causal parent
 * edges and attempts — and reinserts the folded state. The alternative is to diff the old and new
 * aggregates and emit row-level deltas, which reintroduces exactly the hazard
 * {@link ai.ravenroot.api.persistence.ExecutionTransition} was designed to remove: a delta writer that
 * is subtly wrong produces a row set that reconstructs into a <em>different legal</em> aggregate, and
 * nothing detects it, whereas a full rewrite is wrong only in ways reconstruction catches. The cost is
 * proportional to aggregate size per write, which is the correct trade for a local single-host store
 * and is stated here so that a future adapter facing large aggregates knows what it is changing.</p>
 */
final class AggregateStorage {

    private AggregateStorage() {
    }

    static ProcessInstance read(Connection connection, ExecutionKey key, ProcessInstanceStatus status)
            throws SQLException {
        String tenantId = key.tenantId();
        String instanceId = key.processInstanceId().toString();

        Map<UUID, Set<UUID>> parents = readParents(connection, tenantId, instanceId);
        Map<UUID, List<NodeAttempt>> attempts = readAttempts(connection, tenantId, instanceId);
        Map<UUID, List<NodeInvocation>> invocations =
                readInvocations(connection, tenantId, instanceId, parents, attempts);

        var traversals = new LinkedHashMap<UUID, Traversal>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT traversal_id, ingress_node_id, status FROM traversal "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY position")) {
            statement.setString(1, tenantId);
            statement.setString(2, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID traversalId = UUID.fromString(rows.getString("traversal_id"));
                    var ordered = new LinkedHashMap<UUID, NodeInvocation>();
                    for (NodeInvocation invocation : invocations.getOrDefault(traversalId, List.of())) {
                        ordered.put(invocation.invocationId(), invocation);
                    }
                    traversals.put(traversalId, new Traversal(traversalId, rows.getString("ingress_node_id"),
                            TraversalStatus.valueOf(rows.getString("status")), ordered));
                }
            }
        }
        return new ProcessInstance(key.processInstanceId(), status, traversals);
    }

    private static Map<UUID, List<NodeInvocation>> readInvocations(
            Connection connection, String tenantId, String instanceId,
            Map<UUID, Set<UUID>> parents, Map<UUID, List<NodeAttempt>> attempts) throws SQLException {
        var byTraversal = new LinkedHashMap<UUID, List<NodeInvocation>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT traversal_id, invocation_id, node_id, status, node_command FROM invocation "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY traversal_id, position")) {
            statement.setString(1, tenantId);
            statement.setString(2, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID traversalId = UUID.fromString(rows.getString("traversal_id"));
                    UUID invocationId = UUID.fromString(rows.getString("invocation_id"));
                    byTraversal.computeIfAbsent(traversalId, ignored -> new ArrayList<>())
                            .add(new NodeInvocation(invocationId, rows.getString("node_id"),
                                    parents.getOrDefault(invocationId, Set.of()),
                                    NodeInvocationStatus.valueOf(rows.getString("status")),
                                    attempts.getOrDefault(invocationId, List.of()),
                                    ai.ravenroot.api.execution.NodeCommand.parse(rows.getString("node_command"))));
                }
            }
        }
        return byTraversal;
    }

    private static Map<UUID, Set<UUID>> readParents(Connection connection, String tenantId, String instanceId)
            throws SQLException {
        var parents = new LinkedHashMap<UUID, Set<UUID>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_id, parent_invocation_id FROM invocation_parent "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY parent_invocation_id")) {
            statement.setString(1, tenantId);
            statement.setString(2, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    parents.computeIfAbsent(UUID.fromString(rows.getString("invocation_id")),
                                    ignored -> new LinkedHashSet<>())
                            .add(UUID.fromString(rows.getString("parent_invocation_id")));
                }
            }
        }
        return parents;
    }

    private static Map<UUID, List<NodeAttempt>> readAttempts(Connection connection, String tenantId,
                                                             String instanceId) throws SQLException {
        var attempts = new LinkedHashMap<UUID, List<NodeAttempt>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_id, attempt_id, ordinal, status, completion, park_cause FROM attempt "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY invocation_id, ordinal")) {
            statement.setString(1, tenantId);
            statement.setString(2, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String completion = rows.getString("completion");
                    attempts.computeIfAbsent(UUID.fromString(rows.getString("invocation_id")),
                                    ignored -> new ArrayList<>())
                            .add(new NodeAttempt(UUID.fromString(rows.getString("attempt_id")),
                                    rows.getInt("ordinal"),
                                    NodeAttemptStatus.valueOf(rows.getString("status")),
                                    completion == null ? null : NodeAttemptCompletion.valueOf(completion),
                                    rows.getString("park_cause")));
                }
            }
        }
        return attempts;
    }

    static void write(Connection connection, ExecutionKey key, ProcessInstance state) throws SQLException {
        String tenantId = key.tenantId();
        String instanceId = key.processInstanceId().toString();

        try (PreparedStatement clear = connection.prepareStatement(
                "DELETE FROM traversal WHERE tenant_id = ? AND process_instance_id = ?")) {
            clear.setString(1, tenantId);
            clear.setString(2, instanceId);
            clear.executeUpdate();
        }

        try (PreparedStatement traversal = connection.prepareStatement(
                     "INSERT INTO traversal (tenant_id, process_instance_id, traversal_id, position, "
                             + "ingress_node_id, status) VALUES (?, ?, ?, ?, ?, ?)");
             PreparedStatement invocation = connection.prepareStatement(
                     "INSERT INTO invocation (tenant_id, process_instance_id, traversal_id, invocation_id, "
                             + "position, node_id, status, node_command) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement parent = connection.prepareStatement(
                     "INSERT INTO invocation_parent (tenant_id, process_instance_id, invocation_id, "
                             + "parent_invocation_id) VALUES (?, ?, ?, ?)");
             PreparedStatement attempt = connection.prepareStatement(
                     "INSERT INTO attempt (tenant_id, process_instance_id, invocation_id, attempt_id, "
                             + "ordinal, status, completion, park_cause) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

            int traversalPosition = 0;
            for (Traversal current : state.traversals().values()) {
                traversal.setString(1, tenantId);
                traversal.setString(2, instanceId);
                traversal.setString(3, current.traversalId().toString());
                traversal.setInt(4, traversalPosition++);
                traversal.setString(5, current.ingressNodeId());
                traversal.setString(6, current.status().name());
                traversal.executeUpdate();

                int invocationPosition = 0;
                for (NodeInvocation held : current.invocations().values()) {
                    invocation.setString(1, tenantId);
                    invocation.setString(2, instanceId);
                    invocation.setString(3, current.traversalId().toString());
                    invocation.setString(4, held.invocationId().toString());
                    invocation.setInt(5, invocationPosition++);
                    invocation.setString(6, held.nodeId());
                    invocation.setString(7, held.status().name());
                    invocation.setString(8, held.command().name());
                    invocation.executeUpdate();
                }
            }

            // Causal parent edges are inserted only once every invocation row exists, because a parent
            // may legally live in another traversal: the re-entry ingress invocation is exactly the
            // case ProcessInstance permits, so an edge written during the traversal loop could point
            // at a row that has not been inserted yet and the foreign key would reject a legal state.
            for (Traversal current : state.traversals().values()) {
                for (NodeInvocation held : current.invocations().values()) {
                    for (UUID parentId : held.parentInvocationIds()) {
                        parent.setString(1, tenantId);
                        parent.setString(2, instanceId);
                        parent.setString(3, held.invocationId().toString());
                        parent.setString(4, parentId.toString());
                        parent.executeUpdate();
                    }
                    for (NodeAttempt held0 : held.attempts()) {
                        attempt.setString(1, tenantId);
                        attempt.setString(2, instanceId);
                        attempt.setString(3, held.invocationId().toString());
                        attempt.setString(4, held0.attemptId().toString());
                        attempt.setInt(5, held0.ordinal());
                        attempt.setString(6, held0.status().name());
                        attempt.setString(7, held0.completion() == null ? null : held0.completion().name());
                        attempt.setString(8, held0.parkCause());
                        attempt.executeUpdate();
                    }
                }
            }
        }
    }
}
