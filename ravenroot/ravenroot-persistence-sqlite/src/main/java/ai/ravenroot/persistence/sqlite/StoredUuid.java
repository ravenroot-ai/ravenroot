package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/** Defensive decoder for UUID text crossing the SQLite persistence boundary. */
final class StoredUuid {

    /**
     * Diagnostic placeholder used only when the corrupt column is the process identity itself.
     *
     * <p>It is never returned as row data, used in a query, or substituted into an aggregate. The
     * public corruption type requires an {@link ExecutionKey}, so a tenant-wide scan that cannot
     * decode the row's real key needs a non-secret value with which to classify the failure.</p>
     */
    private static final UUID UNKNOWN_INSTANCE = new UUID(0L, 0L);

    private StoredUuid() {
    }

    static UUID required(ResultSet rows, String table, String column, ExecutionKey key)
            throws SQLException {
        return required(rows.getString(column), table, column, key);
    }

    static UUID required(ResultSet rows, int columnIndex, String table, String column,
                         String tenantId) throws SQLException {
        return required(rows.getString(columnIndex), table, column, diagnosticKey(tenantId));
    }

    static UUID required(ResultSet rows, String table, String column, String tenantId)
            throws SQLException {
        return required(rows.getString(column), table, column, diagnosticKey(tenantId));
    }

    static UUID optional(ResultSet rows, String table, String column, ExecutionKey key)
            throws SQLException {
        return optional(rows.getString(column), table, column, key);
    }

    static UUID required(String stored, String table, String column, ExecutionKey key) {
        return decode(stored, table, column, key, false);
    }

    static UUID optional(String stored, String table, String column, ExecutionKey key) {
        return decode(stored, table, column, key, true);
    }

    static UUID requiredMatching(ResultSet rows, String table, String column, ExecutionKey key,
                                 UUID expected) throws SQLException {
        return requiredMatching(rows.getString(column), table, column, key, expected);
    }

    static UUID requiredMatching(String stored, String table, String column, ExecutionKey key,
                                 UUID expected) {
        UUID decoded = required(stored, table, column, key);
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

    private static UUID decode(String stored, String table, String column, ExecutionKey key,
                               boolean nullable) {
        if (stored == null) {
            if (nullable) {
                return null;
            }
            throw corrupted(key, table, column, "is missing");
        }
        try {
            UUID decoded = UUID.fromString(stored);
            if (!decoded.toString().equalsIgnoreCase(stored)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return decoded;
        } catch (IllegalArgumentException malformed) {
            // The raw parser exception may include the stored text. It is intentionally discarded:
            // callers receive only the typed failure and the fixed table/column diagnosis.
            throw corrupted(key, table, column, "is not a canonical UUID");
        }
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
