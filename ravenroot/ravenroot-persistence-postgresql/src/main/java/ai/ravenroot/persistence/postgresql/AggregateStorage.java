package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptCompletion;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeCommand;
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
 * Reads and writes the process aggregate as normalized rows.
 *
 * <h2>Reconstruction is revalidation</h2>
 * <p>{@link #read} builds the aggregate through {@link ProcessInstance}'s canonical constructor and
 * those of every type below it, which is where identity uniqueness, the cross-traversal parent rule
 * and acyclicity are enforced. That is not defensive duplication: for a store that folds state off a
 * database it is the only place
 * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} can be detected at all. Rows
 * edited out of band, a half-migrated schema or a genuine bug in {@link #write} all surface here as a
 * rejected construction rather than as an illegal aggregate escaping into the runtime. The caller maps
 * the rejection; this class only propagates it.</p>
 *
 * <h2>Why the whole aggregate is rewritten on every batch</h2>
 * <p>{@link #write} deletes the instance's traversal rows — cascading to invocations, causal parent
 * edges and attempts — and reinserts the folded state. The alternative is to diff the old and new
 * aggregates and emit row-level deltas, which reintroduces exactly the hazard the transition model was
 * designed to remove: a delta writer that is subtly wrong produces a row set that reconstructs into a
 * <em>different legal</em> aggregate, and nothing detects it, whereas a full rewrite is wrong only in
 * ways reconstruction catches.</p>
 *
 * <p>The cost is proportional to aggregate size per write. That is a worse trade here than it is for a
 * single-host store, because these rows travel over a network, and it is nonetheless the trade taken:
 * the failure a delta writer produces is silent and permanent, and the one this produces is a slower
 * write. What makes it safe under concurrency is that every caller holds the {@code process_instance}
 * row lock for the length of the transaction, so no second writer can be rewriting the same
 * instance's rows at the same time.</p>
 *
 * <h2>Every statement here runs inside the caller's transaction</h2>
 * <p>Nothing in this class opens a connection or commits. It is handed the connection the enclosing
 * transaction is running on, which is what makes the aggregate write atomic with the instance row,
 * the journal entry and the idempotency record beside it.</p>
 */
final class AggregateStorage {

    private AggregateStorage() {
    }

    /**
     * Rebuilds the aggregate, with the instance's own status and termination reason supplied by the
     * caller because they live on the {@code process_instance} row this class does not read.
     *
     * @param terminationReason why the instance reached a terminal {@code status}, or {@code null}
     *                          when nothing distinguishes it. A cancelled run is stored as
     *                          {@code FAILED} plus {@code CANCELLED}, so dropping this on the way in
     *                          would reconstruct every cancellation as a fault.
     */
    static ProcessInstance read(Connection connection, ExecutionKey key, ProcessInstanceStatus status,
                                ExecutionTerminationReason terminationReason) throws SQLException {
        Map<UUID, Set<UUID>> parents = readParents(connection, key);
        Map<UUID, List<NodeAttempt>> attempts = readAttempts(connection, key);
        Map<UUID, List<NodeInvocation>> invocations = readInvocations(connection, key, parents, attempts);

        var traversals = new LinkedHashMap<UUID, Traversal>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT traversal_id, ingress_node_id, status, termination_reason FROM traversal "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY position")) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID traversalId = StoredUuid.required(rows, "traversal", "traversal_id", key);
                    var ordered = new LinkedHashMap<UUID, NodeInvocation>();
                    for (NodeInvocation invocation : invocations.getOrDefault(traversalId, List.of())) {
                        ordered.put(invocation.invocationId(), invocation);
                    }
                    traversals.put(traversalId, new Traversal(traversalId, rows.getString("ingress_node_id"),
                            TraversalStatus.valueOf(rows.getString("status")), ordered,
                            terminationReasonOf(rows.getString("termination_reason"))));
                }
            }
        }

        // Referential checks the foreign keys cannot make. A key constrains a child to have a parent;
        // it says nothing about a fold that assembled the children into the wrong shape, which is
        // precisely what a half-applied migration or a bug in write() produces.
        Set<UUID> traversalIds = traversals.keySet();
        for (UUID traversalId : invocations.keySet()) {
            StoredUuid.requireKnown(traversalIds, traversalId, "invocation", "traversal_id", key);
        }
        Set<UUID> invocationIds = invocations.values().stream()
                .flatMap(List::stream).map(NodeInvocation::invocationId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (UUID invocationId : parents.keySet()) {
            StoredUuid.requireKnown(invocationIds, invocationId, "invocation_parent", "invocation_id", key);
        }
        for (Set<UUID> parentIds : parents.values()) {
            for (UUID parentId : parentIds) {
                StoredUuid.requireKnown(invocationIds, parentId, "invocation_parent",
                        "parent_invocation_id", key);
            }
        }
        for (UUID invocationId : attempts.keySet()) {
            StoredUuid.requireKnown(invocationIds, invocationId, "attempt", "invocation_id", key);
        }
        return new ProcessInstance(key.processInstanceId(), status, traversals, terminationReason);
    }

    private static Map<UUID, List<NodeInvocation>> readInvocations(
            Connection connection, ExecutionKey key, Map<UUID, Set<UUID>> parents,
            Map<UUID, List<NodeAttempt>> attempts) throws SQLException {
        var byTraversal = new LinkedHashMap<UUID, List<NodeInvocation>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT traversal_id, invocation_id, node_id, status, node_command FROM invocation "
                        + "WHERE tenant_id = ? AND process_instance_id = ? "
                        + "ORDER BY traversal_id, position")) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID traversalId = StoredUuid.required(rows, "invocation", "traversal_id", key);
                    UUID invocationId = StoredUuid.required(rows, "invocation", "invocation_id", key);
                    byTraversal.computeIfAbsent(traversalId, ignored -> new ArrayList<>())
                            .add(new NodeInvocation(invocationId, rows.getString("node_id"),
                                    parents.getOrDefault(invocationId, Set.of()),
                                    NodeInvocationStatus.valueOf(rows.getString("status")),
                                    attempts.getOrDefault(invocationId, List.of()),
                                    NodeCommand.parse(rows.getString("node_command"))));
                }
            }
        }
        return byTraversal;
    }

    private static Map<UUID, Set<UUID>> readParents(Connection connection, ExecutionKey key)
            throws SQLException {
        var parents = new LinkedHashMap<UUID, Set<UUID>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_id, parent_invocation_id FROM invocation_parent "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY parent_invocation_id")) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    parents.computeIfAbsent(
                                    StoredUuid.required(rows, "invocation_parent", "invocation_id", key),
                                    ignored -> new LinkedHashSet<>())
                            .add(StoredUuid.required(rows, "invocation_parent", "parent_invocation_id", key));
                }
            }
        }
        return parents;
    }

    private static Map<UUID, List<NodeAttempt>> readAttempts(Connection connection, ExecutionKey key)
            throws SQLException {
        var attempts = new LinkedHashMap<UUID, List<NodeAttempt>>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_id, attempt_id, ordinal, status, completion, park_cause, "
                        + "withheld_through_delivery FROM attempt "
                        + "WHERE tenant_id = ? AND process_instance_id = ? "
                        + "ORDER BY invocation_id, ordinal")) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String completion = rows.getString("completion");
                    attempts.computeIfAbsent(StoredUuid.required(rows, "attempt", "invocation_id", key),
                                    ignored -> new ArrayList<>())
                            .add(new NodeAttempt(StoredUuid.required(rows, "attempt", "attempt_id", key),
                                    rows.getInt("ordinal"),
                                    NodeAttemptStatus.valueOf(rows.getString("status")),
                                    completion == null ? null : NodeAttemptCompletion.valueOf(completion),
                                    rows.getString("park_cause"),
                                    rows.getInt("withheld_through_delivery")));
                }
            }
        }
        return attempts;
    }

    /**
     * Reads a stored termination reason, keeping absence and unreadability apart.
     *
     * <p>NULL is a legal, meaningful value — nothing distinguishes this termination — so it must map
     * to {@code null} rather than reach {@code valueOf} at all. A name this build does not know is the
     * opposite case: it was written by a newer binary and reading it as "no reason" would report a
     * cancelled run as an ordinary failure, silently. The {@link IllegalArgumentException} raised here
     * is caught by the caller that owns the aggregate and classified as {@code Corrupted}, on the same
     * path an unknown status name already takes.</p>
     */
    private static ExecutionTerminationReason terminationReasonOf(String name) {
        return name == null ? null : ExecutionTerminationReason.valueOf(name);
    }

    static void write(Connection connection, ExecutionKey key, ProcessInstance state) throws SQLException {
        try (PreparedStatement clear = connection.prepareStatement(
                "DELETE FROM traversal WHERE tenant_id = ? AND process_instance_id = ?")) {
            bindKey(clear, key);
            clear.executeUpdate();
        }

        try (PreparedStatement traversal = connection.prepareStatement(
                     "INSERT INTO traversal (tenant_id, process_instance_id, traversal_id, position, "
                             + "ingress_node_id, status, termination_reason) VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement invocation = connection.prepareStatement(
                     "INSERT INTO invocation (tenant_id, process_instance_id, traversal_id, invocation_id, "
                             + "position, node_id, status, node_command) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement parent = connection.prepareStatement(
                     "INSERT INTO invocation_parent (tenant_id, process_instance_id, invocation_id, "
                             + "parent_invocation_id) VALUES (?, ?, ?, ?)");
             PreparedStatement attempt = connection.prepareStatement(
                     "INSERT INTO attempt (tenant_id, process_instance_id, invocation_id, attempt_id, "
                             + "ordinal, status, completion, park_cause, withheld_through_delivery) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            int traversalPosition = 0;
            for (Traversal current : state.traversals().values()) {
                bindKey(traversal, key);
                StoredUuid.bind(traversal, 3, current.traversalId());
                traversal.setInt(4, traversalPosition++);
                traversal.setString(5, current.ingressNodeId());
                traversal.setString(6, current.status().name());
                // Stored by name, and NULL when unstated: absence is a value here, not a gap to be
                // filled in on the way out.
                traversal.setString(7, current.terminationReason() == null
                        ? null : current.terminationReason().name());
                traversal.addBatch();

                int invocationPosition = 0;
                for (NodeInvocation held : current.invocations().values()) {
                    bindKey(invocation, key);
                    StoredUuid.bind(invocation, 3, current.traversalId());
                    StoredUuid.bind(invocation, 4, held.invocationId());
                    invocation.setInt(5, invocationPosition++);
                    invocation.setString(6, held.nodeId());
                    invocation.setString(7, held.status().name());
                    invocation.setString(8, held.command().name());
                    invocation.addBatch();
                }
            }
            // Batched rather than executed one by one, because every one of these statements is a
            // network round trip here and a single-host store's per-statement cost is not this store's
            // cost. The order between the two batches is still the order they are executed in.
            traversal.executeBatch();
            invocation.executeBatch();

            // Causal parent edges are inserted only once every invocation row exists, because a parent
            // may legally live in another traversal: the re-entry ingress invocation is exactly the
            // case the aggregate permits, so an edge written during the traversal loop could point at
            // a row that has not been inserted yet and the foreign key would reject a legal state.
            boolean anyParent = false;
            boolean anyAttempt = false;
            for (Traversal current : state.traversals().values()) {
                for (NodeInvocation held : current.invocations().values()) {
                    for (UUID parentId : held.parentInvocationIds()) {
                        bindKey(parent, key);
                        StoredUuid.bind(parent, 3, held.invocationId());
                        StoredUuid.bind(parent, 4, parentId);
                        parent.addBatch();
                        anyParent = true;
                    }
                    for (NodeAttempt made : held.attempts()) {
                        bindKey(attempt, key);
                        StoredUuid.bind(attempt, 3, held.invocationId());
                        StoredUuid.bind(attempt, 4, made.attemptId());
                        attempt.setInt(5, made.ordinal());
                        attempt.setString(6, made.status().name());
                        attempt.setString(7, made.completion() == null ? null : made.completion().name());
                        attempt.setString(8, made.parkCause());
                        attempt.setInt(9, made.withheldThroughDelivery());
                        attempt.addBatch();
                        anyAttempt = true;
                    }
                }
            }
            if (anyParent) {
                parent.executeBatch();
            }
            if (anyAttempt) {
                attempt.executeBatch();
            }
        }
    }

    private static void bindKey(PreparedStatement statement, ExecutionKey key) throws SQLException {
        statement.setString(1, key.tenantId());
        StoredUuid.bind(statement, 2, key.processInstanceId());
    }
}
