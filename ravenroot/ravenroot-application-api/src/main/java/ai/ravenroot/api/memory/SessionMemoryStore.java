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
    int DEFAULT_MAX_VALUE_BYTES = 256 * 1024;
    int MAX_ID_LENGTH = 256;
    int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    enum Scope { SESSION, PROCESS, NODE }

    /** Exact tenant/session/process/node address. */
    record Key(String tenantId, String sessionId, Scope scope, UUID processInstanceId, String nodeId) {
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

        public static Key session(String tenantId, String sessionId) {
            return new Key(tenantId, sessionId, Scope.SESSION, null, null);
        }

        public static Key process(String tenantId, String sessionId, UUID processInstanceId) {
            return new Key(tenantId, sessionId, Scope.PROCESS, processInstanceId, null);
        }

        public static Key node(String tenantId, String sessionId, UUID processInstanceId, String nodeId) {
            return new Key(tenantId, sessionId, Scope.NODE, processInstanceId, nodeId);
        }
    }

    /** Immutable stored value; content is intentionally opaque to the persistence layer. */
    record Entry(Key key, long revision, byte[] value, String contentType, Instant createdAt,
                 Instant updatedAt, Instant expiresAt) {
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

    sealed interface Expectation permits Expectation.Any, Expectation.Absent, Expectation.Exactly {
        record Any() implements Expectation { }
        record Absent() implements Expectation { }
        record Exactly(long revision) implements Expectation {
            public Exactly { if (revision < 1) throw new IllegalArgumentException("revision must be positive"); }
        }
        static Expectation any() { return new Any(); }
        static Expectation absent() { return new Absent(); }
        static Expectation exactly(long revision) { return new Exactly(revision); }
    }

    record Write(Key key, byte[] value, String contentType, Instant expiresAt,
                 Expectation expectation, String idempotencyKey) {
        public Write {
            Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value").clone();
            contentType = text(contentType, "contentType", 128);
            Objects.requireNonNull(expiresAt, "expiresAt");
            expectation = Objects.requireNonNull(expectation, "expectation");
            idempotencyKey = text(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        @Override public byte[] value() { return value.clone(); }
    }

    sealed interface Failure permits Failure.NotFound, Failure.Conflict, Failure.IdempotencyConflict,
            Failure.TooLarge, Failure.InvalidRequest, Failure.Unavailable {
        record NotFound() implements Failure { }
        record Conflict(long currentRevision) implements Failure { }
        record IdempotencyConflict(String key) implements Failure { }
        record TooLarge(int maximumBytes, int actualBytes) implements Failure { }
        record InvalidRequest(String reason) implements Failure { }
        record Unavailable() implements Failure { }
    }

    final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Failure failure;
        public StoreException(Failure failure) {
            super(Objects.requireNonNull(failure, "failure").toString());
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    int maximumValueBytes();
    CompletionStage<Optional<Entry>> get(Key key);
    CompletionStage<Entry> put(Write write);
    CompletionStage<Boolean> delete(Key key, Expectation expectation);
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
