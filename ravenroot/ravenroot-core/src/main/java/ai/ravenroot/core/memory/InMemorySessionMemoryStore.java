package ai.ravenroot.core.memory;

import ai.ravenroot.api.memory.SessionMemoryStore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic reference implementation of the replaceable session-memory SPI. */
public final class InMemorySessionMemoryStore implements SessionMemoryStore {
    private final Clock clock;
    private final int maximumValueBytes;
    private final Map<Key, Entry> entries = new HashMap<>();
    private final Map<LedgerKey, Ledger> ledger = new HashMap<>();

    public InMemorySessionMemoryStore(Clock clock) {
        this(clock, DEFAULT_MAX_VALUE_BYTES);
    }

    public InMemorySessionMemoryStore(Clock clock, int maximumValueBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumValueBytes < 1) throw new IllegalArgumentException("maximumValueBytes must be positive");
        this.maximumValueBytes = maximumValueBytes;
    }

    @Override public int maximumValueBytes() { return maximumValueBytes; }

    @Override
    public synchronized CompletionStage<Optional<Entry>> get(Key key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry != null && !entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(key);
            entry = null;
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(entry));
    }

    @Override
    public synchronized CompletionStage<Entry> put(Write write) {
        Objects.requireNonNull(write, "write");
        if (write.value().length > maximumValueBytes) {
            return failed(new Failure.TooLarge(maximumValueBytes, write.value().length));
        }
        Instant now = clock.instant();
        if (!write.expiresAt().isAfter(now)) {
            return failed(new Failure.InvalidRequest("expiresAt must be in the future"));
        }
        LedgerKey ledgerKey = new LedgerKey(write.key().tenantId(), write.idempotencyKey());
        String digest = digest(write);
        Ledger prior = ledger.get(ledgerKey);
        if (prior != null) {
            return prior.digest.equals(digest) ? CompletableFuture.completedFuture(prior.entry)
                    : failed(new Failure.IdempotencyConflict(write.idempotencyKey()));
        }
        Entry current = entries.get(write.key());
        if (current != null && !current.expiresAt().isAfter(now)) {
            entries.remove(write.key());
            current = null;
        }
        if (!matches(write.expectation(), current)) {
            return failed(new Failure.Conflict(current == null ? 0 : current.revision()));
        }
        Entry next = new Entry(write.key(), current == null ? 1 : current.revision() + 1,
                write.value(), write.contentType(), current == null ? now : current.createdAt(),
                now, write.expiresAt());
        entries.put(write.key(), next);
        ledger.put(ledgerKey, new Ledger(digest, next));
        return CompletableFuture.completedFuture(next);
    }

    @Override
    public synchronized CompletionStage<Boolean> delete(Key key, Expectation expectation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectation, "expectation");
        Entry current = entries.get(key);
        if (current == null || !current.expiresAt().isAfter(clock.instant())) {
            entries.remove(key);
            if (expectation instanceof Expectation.Absent || expectation instanceof Expectation.Any) {
                return CompletableFuture.completedFuture(false);
            }
            return failed(new Failure.NotFound());
        }
        if (!matches(expectation, current)) {
            return failed(new Failure.Conflict(current.revision()));
        }
        entries.remove(key);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Long> purgeExpired(String tenantId, Instant through, int limit) {
        if (tenantId == null || tenantId.isBlank() || through == null || limit < 1) {
            return failed(new Failure.InvalidRequest("tenant, time, and positive limit are required"));
        }
        var keys = entries.entrySet().stream()
                .filter(entry -> entry.getKey().tenantId().equals(tenantId)
                        && !entry.getValue().expiresAt().isAfter(through))
                .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .limit(limit).map(Map.Entry::getKey).toList();
        keys.forEach(entries::remove);
        return CompletableFuture.completedFuture((long) keys.size());
    }

    private static boolean matches(Expectation expectation, Entry current) {
        return switch (expectation) {
            case Expectation.Any ignored -> true;
            case Expectation.Absent ignored -> current == null;
            case Expectation.Exactly exactly -> current != null && current.revision() == exactly.revision();
        };
    }

    private static String digest(Write write) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(write.key().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(write.value());
            digest.update((byte) 0);
            digest.update(write.contentType().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(write.expiresAt().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(write.expectation().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static <T> CompletionStage<T> failed(Failure failure) {
        return CompletableFuture.failedFuture(new StoreException(failure));
    }

    private record LedgerKey(String tenantId, String idempotencyKey) { }
    private record Ledger(String digest, Entry entry) { }
}
