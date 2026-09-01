package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;

import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded, non-durable exchange and bearer store. A new process invalidates every browser session.
 *
 * <p>Every entry holds the {@link EmbedRegistrationAggregate} the session captured, and every phase
 * -- pending lookup, acknowledgement, activation and bearer resolution -- re-checks that it is still
 * the current revision before proceeding. Registrations are durable and these are not, which is the
 * intended asymmetry: a restart drops browser sessions, and drops nothing an operator decided.</p>
 */
public final class EmbedBrowserSessionAuthority {
    public static final Duration DEFAULT_EXCHANGE_TTL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_BEARER_TTL = Duration.ofMinutes(2);
    public static final int DEFAULT_CAPACITY = 4_096;

    private final ConcurrentHashMap<String, PendingExchange> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> acknowledgementIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveSession> active = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration exchangeTtl;
    private final Duration bearerTtl;
    private final int capacity;
    private final Supplier<String> opaqueValues;

    public EmbedBrowserSessionAuthority(Clock clock, Duration exchangeTtl, Duration bearerTtl, int capacity) {
        this(clock, exchangeTtl, bearerTtl, capacity, secureValues());
    }

    EmbedBrowserSessionAuthority(Clock clock, Duration exchangeTtl, Duration bearerTtl, int capacity,
                                 Supplier<String> opaqueValues) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.exchangeTtl = EmbedLaunchTicketAuthority.boundedTtl(exchangeTtl, "exchange");
        this.bearerTtl = EmbedLaunchTicketAuthority.boundedTtl(bearerTtl, "bearer");
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("invalid session capacity");
        this.capacity = capacity;
        this.opaqueValues = Objects.requireNonNull(opaqueValues, "opaqueValues");
    }

    public synchronized Bootstrap begin(EmbedRegistrationAggregate registration) {
        Objects.requireNonNull(registration, "registration");
        Instant now = clock.instant();
        cleanup(now);
        if (pending.size() + active.size() >= capacity) throw new CapacityExceededException();
        for (int attempt = 0; attempt < 8; attempt++) {
            String exchangeId = opaqueValues.get();
            String challenge = opaqueValues.get();
            String channelId = opaqueValues.get();
            String acknowledgementId = opaqueValues.get();
            Instant expiresAt = now.plus(exchangeTtl);
            String exchangeDigest = EmbedLaunchTicketAuthority.digest(exchangeId);
            String acknowledgementDigest = EmbedLaunchTicketAuthority.digest(acknowledgementId);
            var state = new PendingExchange(registration, challenge, channelId,
                    acknowledgementDigest, null, expiresAt);
            if (pending.putIfAbsent(exchangeDigest, state) == null) {
                if (acknowledgementIndex.putIfAbsent(acknowledgementDigest, exchangeDigest) == null) {
                    return new Bootstrap(exchangeId, challenge, channelId, acknowledgementId, expiresAt);
                }
                pending.remove(exchangeDigest, state);
            }
        }
        throw new IllegalStateException("secure exchange source repeatedly collided");
    }

    public PendingExchange pending(String exchangeId, EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(registrations, "registrations");
        if (exchangeId == null || exchangeId.isBlank()) return null;
        PendingExchange state;
        try {
            state = pending.get(EmbedLaunchTicketAuthority.digest(exchangeId));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return state != null && clock.instant().isBefore(state.expiresAt())
                && registrations.isCurrent(state.registration()) ? state : null;
    }

    public synchronized boolean acknowledge(String acknowledgementId, String channelId,
                                             String correlationId, EmbedRegistrationAggregate registration,
                                             EmbedRegistrationAuthority registrations, Runnable allowedAudit) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(registrations, "registrations");
        Objects.requireNonNull(allowedAudit, "allowedAudit");
        if (acknowledgementId == null || acknowledgementId.isBlank()
                || channelId == null || channelId.isBlank()
                || correlationId == null || correlationId.isBlank() || correlationId.length() > 256) return false;
        Instant now = clock.instant();
        cleanup(now);
        String acknowledgementDigest;
        try {
            acknowledgementDigest = EmbedLaunchTicketAuthority.digest(acknowledgementId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        String exchangeDigest = acknowledgementIndex.get(acknowledgementDigest);
        PendingExchange state = exchangeDigest == null ? null : pending.get(exchangeDigest);
        if (state == null || state.ackCorrelationId() != null
                || !state.acknowledgementDigest().equals(acknowledgementDigest)
                || !state.channelId().equals(channelId) || !state.registration().equals(registration)
                || !now.isBefore(state.expiresAt()) || !registrations.isCurrent(state.registration())) return false;
        var acknowledged = state.acknowledged(correlationId);
        if (!pending.replace(exchangeDigest, state, acknowledged)) return false;
        try {
            allowedAudit.run();
            if (!registrations.isCurrent(state.registration())) {
                pending.replace(exchangeDigest, acknowledged, state);
                return false;
            }
        } catch (RuntimeException auditFailure) {
            pending.replace(exchangeDigest, acknowledged, state);
            throw auditFailure;
        }
        acknowledgementIndex.remove(acknowledgementDigest, exchangeDigest);
        return true;
    }

    public synchronized PendingExchange acknowledged(String exchangeId, EmbedRegistrationAuthority registrations) {
        PendingExchange state = pending(exchangeId, registrations);
        return state != null && state.ackCorrelationId() != null ? state : null;
    }

    /** Atomically consumes the exact pending state and issues a bearer bound to {@code key}. */
    public synchronized IssuedBearer activate(String exchangeId, PendingExchange expected, ECPublicKey key,
                                               EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registrations, "registrations");
        Instant now = clock.instant();
        cleanup(now);
        String exchangeDigest;
        try {
            exchangeDigest = EmbedLaunchTicketAuthority.digest(exchangeId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (expected.ackCorrelationId() == null || !pending.remove(exchangeDigest, expected)
                || !now.isBefore(expected.expiresAt())
                || !registrations.isCurrent(expected.registration())) return null;
        if (pending.size() + active.size() >= capacity) throw new CapacityExceededException();
        for (int attempt = 0; attempt < 8; attempt++) {
            String bearer = opaqueValues.get();
            String challenge = opaqueValues.get();
            Instant expiresAt = now.plus(bearerTtl);
            var session = new ActiveSession(expected.registration(), challenge, key, expiresAt);
            if (active.putIfAbsent(EmbedLaunchTicketAuthority.digest(bearer), session) == null) {
                return new IssuedBearer(bearer, challenge, expiresAt);
            }
        }
        throw new IllegalStateException("secure bearer source repeatedly collided");
    }

    public ActiveSession resolve(String bearer, EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(registrations, "registrations");
        if (bearer == null || bearer.isBlank()) return null;
        String digest;
        try {
            digest = EmbedLaunchTicketAuthority.digest(bearer);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        ActiveSession state = active.get(digest);
        if (state == null) return null;
        if (!clock.instant().isBefore(state.expiresAt()) || !registrations.isCurrent(state.registration())) {
            active.remove(digest, state);
            return null;
        }
        return state;
    }

    synchronized int retainedEntries() {
        cleanup(clock.instant());
        return pending.size() + active.size();
    }

    private void cleanup(Instant now) {
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        acknowledgementIndex.entrySet().removeIf(entry -> !pending.containsKey(entry.getValue()));
        active.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private static Supplier<String> secureValues() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] value = new byte[32];
            random.nextBytes(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        };
    }

    public record Bootstrap(String exchangeId, String challenge, String channelId,
                            String acknowledgementId, Instant expiresAt) { }
    public record PendingExchange(EmbedRegistrationAggregate registration, String challenge, String channelId,
                                  String acknowledgementDigest, String ackCorrelationId,
                                  Instant expiresAt) {
        private PendingExchange acknowledged(String correlationId) {
            return new PendingExchange(registration, challenge, channelId, acknowledgementDigest,
                    correlationId, expiresAt);
        }
    }
    public record IssuedBearer(String bearer, String challenge, Instant expiresAt) { }
    public record ActiveSession(EmbedRegistrationAggregate registration, String challenge, ECPublicKey key,
                                Instant expiresAt) { }

    public static final class CapacityExceededException extends RuntimeException {
        private CapacityExceededException() { super("embed session capacity exhausted"); }
    }
}
