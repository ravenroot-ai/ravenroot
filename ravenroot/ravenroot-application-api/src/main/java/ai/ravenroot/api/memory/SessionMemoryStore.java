package ai.ravenroot.api.memory;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Replaceable tenant-scoped agent session memory, separate from workflow state and payloads.
 *
 * <p>Writes are explicit effects. A workflow replay may read the resulting value but must not call
 * {@link #put} again unless it presents the same idempotency key and body, in which case the store
 * returns the original write. Retention and deletion belong to this port rather than to execution
 * retention, so deleting a process cannot accidentally imply deleting a reusable session.</p>
 */
public interface SessionMemoryStore extends AutoCloseable {
    /** Default maximum opaque value size. */
    int DEFAULT_MAX_VALUE_BYTES = 256 * 1024;
    /** Maximum tenant, session, or node identity length. */
    int MAX_ID_LENGTH = 256;
    /** Maximum caller-provided idempotency-key length. */
    int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** The lifetime and address width of one memory value. */
    enum Scope {
        /** Shared by every process using the named session. */
        SESSION,
        /** Restricted to one process instance within the session. */
        PROCESS,
        /** Restricted to one node within one process instance. */
        NODE
    }

    /**
     * Exact tenant/session/process/node address.
     *
     * @param tenantId tenant that owns the memory.
     * @param sessionId caller-defined session identity.
     * @param scope address width of the value.
     * @param processInstanceId required for process and node scope; otherwise {@code null}.
     * @param nodeId required for node scope; otherwise {@code null}.
     */
    record Key(String tenantId, String sessionId, Scope scope, UUID processInstanceId, String nodeId) {
        /** Validates that the supplied address components match the selected scope. */
        public Key {
            tenantId = text(tenantId, "tenantId", MAX_ID_LENGTH);
            sessionId = text(sessionId, "sessionId", MAX_ID_LENGTH);
            scope = Objects.requireNonNull(scope, "scope");
            if (scope == Scope.SESSION && (processInstanceId != null || nodeId != null)
                    || scope == Scope.PROCESS && (processInstanceId == null || nodeId != null)
                    || scope == Scope.NODE && (processInstanceId == null || nodeId == null)) {
                throw new IllegalArgumentException("memory key does not match its scope");
            }
            if (nodeId != null) nodeId = text(nodeId, "nodeId", MAX_ID_LENGTH);
        }

        /**
         * Creates an address shared by the whole session.
         * @param tenantId tenant that owns the memory.
         * @param sessionId caller-defined session identity.
         * @return a session-scoped key for the tenant and session.
         */
        public static Key session(String tenantId, String sessionId) {
            return new Key(tenantId, sessionId, Scope.SESSION, null, null);
        }

        /**
         * Creates an address restricted to one process instance.
         * @param tenantId tenant that owns the memory.
         * @param sessionId caller-defined session identity.
         * @param processInstanceId process instance that owns the memory.
         * @return a process-scoped key for the tenant, session, and process instance.
         */
        public static Key process(String tenantId, String sessionId, UUID processInstanceId) {
            return new Key(tenantId, sessionId, Scope.PROCESS, processInstanceId, null);
        }

        /**
         * Creates an address restricted to one process node.
         * @param tenantId tenant that owns the memory.
         * @param sessionId caller-defined session identity.
         * @param processInstanceId process instance that owns the memory.
         * @param nodeId node that owns the memory.
         * @return a node-scoped key for the tenant, session, process instance, and node.
         */
        public static Key node(String tenantId, String sessionId, UUID processInstanceId, String nodeId) {
            return new Key(tenantId, sessionId, Scope.NODE, processInstanceId, nodeId);
        }
    }

    /**
     * Immutable stored value; content is intentionally opaque to the persistence layer.
     *
     * @param key exact address of the value.
     * @param revision positive compare-and-set revision.
     * @param value opaque value bytes.
     * @param contentType media type describing the bytes.
     * @param createdAt creation instant.
     * @param updatedAt most recent write instant.
     * @param expiresAt instant after which the value may be purged.
     */
    record Entry(Key key, long revision, byte[] value, String contentType, Instant createdAt,
                 Instant updatedAt, Instant expiresAt) {
        /** Validates revision and timestamp ordering and takes an immutable value snapshot. */
        public Entry {
            Objects.requireNonNull(key, "key");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            value = Objects.requireNonNull(value, "value").clone();
            contentType = text(contentType, "contentType", 128);
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (updatedAt.isBefore(createdAt) || !expiresAt.isAfter(updatedAt)) {
                throw new IllegalArgumentException("invalid memory timestamps");
            }
        }

        /**
         * Returns a defensive copy of the stored bytes.
         * @return opaque value bytes.
         */
        @Override public byte[] value() { return value.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Entry entry && key.equals(entry.key) && revision == entry.revision
                    && Arrays.equals(value, entry.value) && contentType.equals(entry.contentType)
                    && createdAt.equals(entry.createdAt) && updatedAt.equals(entry.updatedAt)
                    && expiresAt.equals(entry.expiresAt);
        }

        @Override public int hashCode() {
            return 31 * Objects.hash(key, revision, contentType, createdAt, updatedAt, expiresAt)
                    + Arrays.hashCode(value);
        }
    }

    /** Compare-and-set expectation applied to a write or deletion. */
    sealed interface Expectation permits Expectation.Any, Expectation.Absent, Expectation.Exactly {
        /** Accept any current state. */
        record Any() implements Expectation { }
        /** Require the key to be absent. */
        record Absent() implements Expectation { }
        /**
         * Requires one exact positive revision.
         * @param revision exact positive revision required.
         */
        record Exactly(long revision) implements Expectation {
            /** Validates the required revision. */
            public Exactly { if (revision < 1) throw new IllegalArgumentException("revision must be positive"); }
        }
        /**
         * Creates an unconstrained expectation.
         * @return an unconstrained expectation.
         */
        static Expectation any() { return new Any(); }
        /**
         * Creates an absent-value expectation.
         * @return an absent-value expectation.
         */
        static Expectation absent() { return new Absent(); }
        /**
         * Creates an exact-revision expectation.
         * @param revision exact positive revision required.
         * @return an exact-revision expectation.
         */
        static Expectation exactly(long revision) { return new Exactly(revision); }
    }

    /**
     * One idempotent memory write.
     *
     * @param key exact address to write.
     * @param value opaque value bytes.
     * @param contentType media type describing the bytes.
     * @param expiresAt instant after which the value may be purged.
     * @param expectation compare-and-set condition.
     * @param idempotencyKey caller-defined replay identity.
     */
    record Write(Key key, byte[] value, String contentType, Instant expiresAt,
                 Expectation expectation, String idempotencyKey) {
        /** Validates the write request and takes an immutable value snapshot. */
        public Write {
            Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value").clone();
            contentType = text(contentType, "contentType", 128);
            Objects.requireNonNull(expiresAt, "expiresAt");
            expectation = Objects.requireNonNull(expectation, "expectation");
            idempotencyKey = text(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        /**
         * Returns a defensive copy of the requested bytes.
         * @return opaque value bytes.
         */
        @Override public byte[] value() { return value.clone(); }
    }

    /** Closed failure vocabulary returned through {@link StoreException}. */
    sealed interface Failure permits Failure.NotFound, Failure.Conflict, Failure.IdempotencyConflict,
            Failure.TooLarge, Failure.InvalidRequest, Failure.Unavailable {
        /** The addressed value does not exist. */
        record NotFound() implements Failure { }
        /**
         * Reports an expectation conflict.
         * @param currentRevision revision that defeated the expectation.
         */
        record Conflict(long currentRevision) implements Failure { }
        /**
         * Reports reuse of an idempotency key with different content.
         * @param key idempotency key previously bound to different content.
         */
        record IdempotencyConflict(String key) implements Failure { }
        /**
         * The value exceeds the adapter's byte bound.
         * @param maximumBytes configured maximum.
         * @param actualBytes submitted size.
         */
        record TooLarge(int maximumBytes, int actualBytes) implements Failure { }
        /**
         * Reports a structurally invalid request.
         * @param reason bounded reason the request is invalid.
         */
        record InvalidRequest(String reason) implements Failure { }
        /** The durable memory authority is unavailable. */
        record Unavailable() implements Failure { }
    }

    /** Exception carrying one typed store failure. */
    final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** Typed failure exposed to the caller. */
        private final Failure failure;
        /**
         * Creates an exception for a typed store failure.
         * @param failure typed failure that caused the operation to end.
         */
        public StoreException(Failure failure) {
            super(Objects.requireNonNull(failure, "failure").toString());
            this.failure = failure;
        }
        /**
         * Returns the typed store failure.
         * @return the typed store failure.
         */
        public Failure failure() { return failure; }
    }

    /**
     * Reports the adapter's value-size bound.
     * @return maximum accepted value size in bytes.
     */
    int maximumValueBytes();
    /**
     * Reads one exact address.
     * @param key exact address to read.
     * @return the addressed value, or empty when it is absent.
     */
    CompletionStage<Optional<Entry>> get(Key key);
    /**
     * Applies one idempotent compare-and-set write.
     * @param write requested idempotent write.
     * @return the stored value after applying the idempotent write.
     */
    CompletionStage<Entry> put(Write write);
    /**
     * Deletes one exact address when its expectation holds.
     * @param key exact address to delete.
     * @param expectation compare-and-set condition.
     * @return whether a value satisfying the expectation was deleted.
     */
    CompletionStage<Boolean> delete(Key key, Expectation expectation);
    /**
     * Purges a bounded batch of expired values.
     * @param tenantId tenant whose expired values may be removed.
     * @param through inclusive expiry cutoff.
     * @param limit maximum rows to purge in this call.
     * @return number of expired values purged for the tenant.
     */
    CompletionStage<Long> purgeExpired(String tenantId, Instant through, int limit);

    @Override default void close() { }

    private static String text(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-blank, bounded, and control-free");
        }
        return value;
    }
}
