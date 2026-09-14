package ai.ravenroot.core.memory;

import ai.ravenroot.api.memory.SessionMemoryStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Agent-facing policy boundary for redaction, retention, and bounded session-memory writes. */
public final class SessionMemoryService {
    @FunctionalInterface
    public interface Redactor {
        /** Returns content safe to retain; implementations must not mutate {@code value}. */
        byte[] redact(SessionMemoryStore.Key key, byte[] value, String contentType);
    }

    private final SessionMemoryStore store;
    private final Clock clock;
    private final Duration maximumRetention;
    private final Redactor redactor;

    public SessionMemoryService(SessionMemoryStore store, Clock clock, Duration maximumRetention,
                                Redactor redactor) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumRetention = Objects.requireNonNull(maximumRetention, "maximumRetention");
        if (maximumRetention.isZero() || maximumRetention.isNegative()) {
            throw new IllegalArgumentException("maximumRetention must be positive");
        }
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    public CompletionStage<SessionMemoryStore.Entry> put(SessionMemoryStore.Write write) {
        Objects.requireNonNull(write, "write");
        if (write.expiresAt().isAfter(clock.instant().plus(maximumRetention))) {
            return CompletableFuture.failedFuture(new SessionMemoryStore.StoreException(
                    new SessionMemoryStore.Failure.InvalidRequest("retention exceeds policy")));
        }
        byte[] redacted = Objects.requireNonNull(
                redactor.redact(write.key(), write.value(), write.contentType()), "redacted value");
        if (redacted.length > store.maximumValueBytes()) {
            return CompletableFuture.failedFuture(new SessionMemoryStore.StoreException(
                    new SessionMemoryStore.Failure.TooLarge(store.maximumValueBytes(), redacted.length)));
        }
        return store.put(new SessionMemoryStore.Write(write.key(), redacted, write.contentType(),
                write.expiresAt(), write.expectation(), write.idempotencyKey()));
    }
}
