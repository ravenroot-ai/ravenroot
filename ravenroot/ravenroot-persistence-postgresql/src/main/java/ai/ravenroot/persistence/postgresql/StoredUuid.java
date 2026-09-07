package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/**
 * Decoder for identifiers crossing the PostgreSQL persistence boundary.
 *
 * <h2>Native {@code uuid}, and what that does and does not remove</h2>
 * <p>Identifiers are stored in PostgreSQL's own {@code uuid} type rather than as text. The column
 * therefore cannot hold a malformed or non-canonical value at all — the database rejects it on the
 * way in, wherever it came from — so the whole class of "stored text is not a UUID" corruption the
 * text-based adapter has to decode defensively simply does not arise, and it is half the width of the
 * index besides.</p>
 *
 * <p>What native typing does <em>not</em> remove is referential corruption: a well-formed identifier
 * naming a row that is not there, or naming a different aggregate than the one being read. Foreign
 * keys cover most of that within one transaction, and this class covers what a fold has to check for
 * itself — that every identifier reconstructed into an aggregate belongs to it. Those checks are kept
 * because reconstruction is where a half-migrated schema, a row edited outside this adapter, or a bug
 * in the writer becomes visible, and the port's contract is that such a row surfaces as
 * {@link ExecutionStoreFailure.Corrupted} rather than as an illegal aggregate reaching the runtime.</p>
 */
final class StoredUuid {

    /**
     * Diagnostic placeholder used only when the unreadable column is the process identity itself.
     *
     * <p>It is never returned as row data, used in a query, or substituted into an aggregate. The
     * public corruption type requires an {@link ExecutionKey}, so a tenant-wide scan that cannot read
     * the row's real key needs a non-secret value with which to classify the failure.</p>
     */
    private static final UUID UNKNOWN_INSTANCE = new UUID(0L, 0L);

    private StoredUuid() {
    }

    static void bind(PreparedStatement statement, int index, UUID value) throws SQLException {
        statement.setObject(index, value);
    }

    static UUID required(ResultSet rows, String table, String column, ExecutionKey key)
            throws SQLException {
        return present(rows.getObject(column, UUID.class), table, column, key);
    }

    static UUID required(ResultSet rows, String table, String column, String tenantId)
            throws SQLException {
        return present(rows.getObject(column, UUID.class), table, column, diagnosticKey(tenantId));
    }

    static UUID optional(ResultSet rows, String table, String column) throws SQLException {
        return rows.getObject(column, UUID.class);
    }

    static UUID requiredMatching(ResultSet rows, String table, String column, ExecutionKey key,
                                 UUID expected) throws SQLException {
        UUID decoded = required(rows, table, column, key);
        if (!decoded.equals(expected)) {
            throw corrupted(key, table, column, "does not match the requested identity");
        }
        return decoded;
    }

    static void requireKnown(Set<UUID> known, UUID decoded, String table, String column,
                             ExecutionKey key) {
        if (!known.contains(decoded)) {
            throw corrupted(key, table, column, "does not reference stored state in this process");
        }
    }

    private static UUID present(UUID stored, String table, String column, ExecutionKey key) {
        if (stored == null) {
            throw corrupted(key, table, column, "is missing");
        }
        return stored;
    }

    private static ExecutionStoreException corrupted(ExecutionKey key, String table, String column,
                                                     String problem) {
        return new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                "stored " + table + "." + column + " " + problem));
    }

    private static ExecutionKey diagnosticKey(String tenantId) {
        return new ExecutionKey(tenantId, UNKNOWN_INSTANCE);
    }
}
