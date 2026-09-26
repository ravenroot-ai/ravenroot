package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.DeploymentViewerView;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;

import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded, non-durable exchange and bearer store. A new process invalidates every browser session.
 *
 * <p>Every entry holds the common legacy-registration or dynamic-grant authorization captured by
 * the session. Pending lookup, acknowledgement, activation and bearer resolution all re-check its
 * currency. A restart drops browser sessions and process-local dynamic grants; durable legacy
 * registrations remain operator-controlled.</p>
 */
public final class EmbedBrowserSessionAuthority {
    public static final Duration DEFAULT_EXCHANGE_TTL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_BEARER_TTL = Duration.ofMinutes(2);
    public static final int DEFAULT_CAPACITY = 4_096;

    private final ConcurrentHashMap<String, PendingExchange> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> acknowledgementIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveSession> active = new ConcurrentHashMap<>();
    private final Map<PendingExchange, EmbedSessionAuthorization> pendingAuthorizations = new IdentityHashMap<>();
    private final Map<ActiveSession, EmbedSessionAuthorization> activeAuthorizations = new IdentityHashMap<>();
    private final Map<ActiveSession, DeploymentViewerView> deploymentBindings = new IdentityHashMap<>();
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
        return begin(new EmbedSessionAuthorization.Registered(registration));
    }

    public synchronized Bootstrap begin(EmbedSessionAuthorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        Instant now = clock.instant();
        cleanup(now);
        if (pending.size() + active.size() >= capacity) throw new CapacityExceededException();
        for (int attempt = 0; attempt < 8; attempt++) {
            String exchangeId = opaqueValues.get();
            String challenge = opaqueValues.get();
            String channelId = opaqueValues.get();
            String acknowledgementId = opaqueValues.get();
            Instant expiresAt = authorization.expiresAt()
                    .map(limit -> EmbedLaunchTicketAuthority.earlier(now.plus(exchangeTtl), limit))
                    .orElseGet(() -> now.plus(exchangeTtl));
            if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("authorization is expired");
            String exchangeDigest = EmbedLaunchTicketAuthority.digest(exchangeId);
            String acknowledgementDigest = EmbedLaunchTicketAuthority.digest(acknowledgementId);
            var state = new PendingExchange(registration(authorization), challenge, channelId,
                    acknowledgementDigest, null, expiresAt);
            if (pending.putIfAbsent(exchangeDigest, state) == null) {
                if (acknowledgementIndex.putIfAbsent(acknowledgementDigest, exchangeDigest) == null) {
                    pendingAuthorizations.put(state, authorization);
                    return new Bootstrap(exchangeId, challenge, channelId, acknowledgementId, expiresAt);
                }
                pending.remove(exchangeDigest, state);
            }
        }
        throw new IllegalStateException("secure exchange source repeatedly collided");
    }

    public PendingExchange pending(String exchangeId, EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(registrations, "registrations");
        return pending(exchangeId, currency(registrations));
    }

    public synchronized PendingExchange pending(String exchangeId, EmbedSessionAuthorizationCurrency currency) {
        Objects.requireNonNull(currency, "currency");
        if (exchangeId == null || exchangeId.isBlank()) return null;
        PendingExchange state;
        try {
            state = pending.get(EmbedLaunchTicketAuthority.digest(exchangeId));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        EmbedSessionAuthorization authorization = authorization(state);
        return state != null && authorization != null && clock.instant().isBefore(state.expiresAt())
                && currency.isCurrent(authorization) ? state : null;
    }

    public synchronized boolean acknowledge(String acknowledgementId, String channelId,
                                             String correlationId, EmbedRegistrationAggregate registration,
                                             EmbedRegistrationAuthority registrations, Runnable allowedAudit) {
        return acknowledge(acknowledgementId, channelId, correlationId,
                new EmbedSessionAuthorization.Registered(registration), currency(registrations), allowedAudit);
    }

    public synchronized boolean acknowledge(String acknowledgementId, String channelId,
                                             String correlationId, EmbedSessionAuthorization authorization,
                                             EmbedSessionAuthorizationCurrency currency, Runnable allowedAudit) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(currency, "currency");
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
        EmbedSessionAuthorization captured = authorization(state);
        if (state == null || state.ackCorrelationId() != null
                || !state.acknowledgementDigest().equals(acknowledgementDigest)
                || !state.channelId().equals(channelId) || !authorization.equals(captured)
                || !now.isBefore(state.expiresAt()) || !currency.isCurrent(captured)) return false;
        var acknowledged = state.acknowledged(correlationId);
        if (!pending.replace(exchangeDigest, state, acknowledged)) return false;
        pendingAuthorizations.remove(state);
        pendingAuthorizations.put(acknowledged, captured);
        try {
            allowedAudit.run();
            if (!currency.isCurrent(captured)) {
                pending.replace(exchangeDigest, acknowledged, state);
                pendingAuthorizations.remove(acknowledged);
                pendingAuthorizations.put(state, captured);
                return false;
            }
        } catch (RuntimeException auditFailure) {
            pending.replace(exchangeDigest, acknowledged, state);
            pendingAuthorizations.remove(acknowledged);
            pendingAuthorizations.put(state, captured);
            throw auditFailure;
        }
        acknowledgementIndex.remove(acknowledgementDigest, exchangeDigest);
        return true;
    }

    public synchronized PendingExchange acknowledged(String exchangeId, EmbedRegistrationAuthority registrations) {
        return acknowledged(exchangeId, currency(registrations));
    }

    public synchronized PendingExchange acknowledged(String exchangeId, EmbedSessionAuthorizationCurrency currency) {
        PendingExchange state = pending(exchangeId, currency);
        return state != null && state.ackCorrelationId() != null ? state : null;
    }

    /** Atomically consumes the exact pending state and issues a bearer bound to {@code key}. */
    public synchronized IssuedBearer activate(String exchangeId, PendingExchange expected, ECPublicKey key,
                                               EmbedRegistrationAuthority registrations) {
        return activate(exchangeId, expected, key, currency(registrations));
    }

    public synchronized IssuedBearer activate(String exchangeId, PendingExchange expected, ECPublicKey key,
                                               EmbedSessionAuthorizationCurrency currency) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(currency, "currency");
        Instant now = clock.instant();
        cleanup(now);
        String exchangeDigest;
        try {
            exchangeDigest = EmbedLaunchTicketAuthority.digest(exchangeId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        EmbedSessionAuthorization authorization = authorization(expected);
        if (expected.ackCorrelationId() == null || !now.isBefore(expected.expiresAt())
                || authorization == null || !currency.isCurrent(authorization)
                || !pending.remove(exchangeDigest, expected)) return null;
        pendingAuthorizations.remove(expected);
        if (pending.size() + active.size() >= capacity) throw new CapacityExceededException();
        for (int attempt = 0; attempt < 8; attempt++) {
            String bearer = opaqueValues.get();
            String challenge = opaqueValues.get();
            Instant expiresAt = authorization.expiresAt()
                    .map(limit -> EmbedLaunchTicketAuthority.earlier(now.plus(bearerTtl), limit))
                    .orElseGet(() -> now.plus(bearerTtl));
            if (!expiresAt.isAfter(now)) return null;
            var session = new ActiveSession(registration(authorization), challenge, key, expiresAt);
            if (active.putIfAbsent(EmbedLaunchTicketAuthority.digest(bearer), session) == null) {
                activeAuthorizations.put(session, authorization);
                authorization.pinnedDeployment()
                        .ifPresent(view -> deploymentBindings.put(session, view));
                return new IssuedBearer(bearer, challenge, expiresAt);
            }
        }
        throw new IllegalStateException("secure bearer source repeatedly collided");
    }

    public synchronized ActiveSession resolve(String bearer, EmbedRegistrationAuthority registrations) {
        return resolve(bearer, currency(registrations));
    }

    public synchronized ActiveSession resolve(String bearer, EmbedSessionAuthorizationCurrency currency) {
        Objects.requireNonNull(currency, "currency");
        if (bearer == null || bearer.isBlank()) return null;
        String digest;
        try {
            digest = EmbedLaunchTicketAuthority.digest(bearer);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        ActiveSession state = active.get(digest);
        EmbedSessionAuthorization authorization = authorization(state);
        if (state == null) return null;
        if (authorization == null || !clock.instant().isBefore(state.expiresAt())
                || !currency.isCurrent(authorization)) {
            active.remove(digest, state);
            activeAuthorizations.remove(state);
            deploymentBindings.remove(state);
            return null;
        }
        return state;
    }

    public synchronized EmbedSessionAuthorization authorization(PendingExchange exchange) {
        return exchange == null ? null : pendingAuthorizations.get(exchange);
    }

    public synchronized EmbedSessionAuthorization authorization(ActiveSession session) {
        return session == null ? null : activeAuthorizations.get(session);
    }

    /** First resolution pins the immutable incarnation to the active session identity. */
    public synchronized boolean bind(ActiveSession session, DeploymentViewerView candidate) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(candidate, "candidate");
        if (active.values().stream().noneMatch(current -> current == session)) return false;
        DeploymentViewerView bound = deploymentBindings.get(session);
        if (bound == null) {
            deploymentBindings.put(session, candidate);
            bound = candidate;
        }
        return bound.source().equals(candidate.source())
                && bound.canonicalDigest().equals(candidate.canonicalDigest());
    }

    /** Returns the immutable deployment view pinned to this exact active session identity. */
    public synchronized DeploymentViewerView resolveBinding(ActiveSession session) {
        Objects.requireNonNull(session, "session");
        if (active.values().stream().noneMatch(current -> current == session)) {
            deploymentBindings.remove(session);
            return null;
        }
        return deploymentBindings.get(session);
    }

    synchronized int retainedEntries() {
        cleanup(clock.instant());
        return pending.size() + active.size();
    }

    private void cleanup(Instant now) {
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        acknowledgementIndex.entrySet().removeIf(entry -> !pending.containsKey(entry.getValue()));
        active.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        pendingAuthorizations.keySet().removeIf(exchange ->
                pending.values().stream().noneMatch(current -> current == exchange));
        activeAuthorizations.keySet().removeIf(session ->
                active.values().stream().noneMatch(current -> current == session));
        deploymentBindings.keySet().removeIf(binding ->
                active.values().stream().noneMatch(session -> session == binding));
    }

    private static Supplier<String> secureValues() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] value = new byte[32];
            random.nextBytes(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        };
    }

    private static EmbedSessionAuthorizationCurrency currency(EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(registrations, "registrations");
        return authorization -> authorization instanceof EmbedSessionAuthorization.Registered registered
                && registrations.isCurrent(registered.registration());
    }

    private static EmbedRegistrationAggregate registration(EmbedSessionAuthorization authorization) {
        return authorization instanceof EmbedSessionAuthorization.Registered registered
                ? registered.registration() : null;
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
