package ai.ravenroot.server;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Bounded process-local opaque cursor authority for deployment observation. */
public final class DeploymentObservationCursorStore {
    static final int DEFAULT_CAPACITY = 16_384;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final int capacity;
    private final Duration ttl;

    public DeploymentObservationCursorStore(Clock clock) {
        this(clock, DEFAULT_CAPACITY, DEFAULT_TTL);
    }

    public DeploymentObservationCursorStore(Clock clock, int capacity, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (capacity < 1) throw new IllegalArgumentException("cursor capacity must be positive");
        this.capacity = capacity;
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("cursor ttl must be positive");
    }

    public synchronized String issue(Binding binding, long sequence) {
        cleanup();
        while (cursors.size() >= capacity) cursors.remove(cursors.keySet().iterator().next());
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] value = new byte[32];
            random.nextBytes(value);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
            if (!cursors.containsKey(token)) {
                cursors.put(token, new Cursor(binding, sequence, clock.instant().plus(ttl)));
                return token;
            }
        }
        throw new IllegalStateException("opaque cursor source repeatedly collided");
    }

    public synchronized Cursor resolve(String token, Binding expected) {
        cleanup();
        Cursor cursor = token == null ? null : cursors.get(token);
        return cursor != null && cursor.binding().equals(expected) ? cursor : null;
    }

    private void cleanup() {
        Instant now = clock.instant();
        cursors.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    public record Binding(String authority, String deploymentId, String incarnationId, String graphVersion) {
        public Binding {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(deploymentId, "deploymentId");
            Objects.requireNonNull(incarnationId, "incarnationId");
            Objects.requireNonNull(graphVersion, "graphVersion");
        }
    }

    public record Cursor(Binding binding, long sequence, Instant expiresAt) { }
}
