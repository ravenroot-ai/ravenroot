package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded, single-process authority for server-minted, digest-at-rest, one-use launch tickets.
 *
 * <p>A ticket carries the captured {@link EmbedRegistrationAggregate}, not a lookup key. That is
 * what lets {@link #consume} answer «is this still the revision the session was created
 * against?» with one comparison instead of a second resolution -- and it is why a ticket cannot
 * be redeemed into a registration that changed underneath it.</p>
 */
public final class EmbedLaunchTicketAuthority {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(1);
    public static final int DEFAULT_CAPACITY = 4_096;

    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final Supplier<String> tokens;

    public EmbedLaunchTicketAuthority(Clock clock, Duration ttl) {
        this(clock, ttl, DEFAULT_CAPACITY, secureTokens());
    }

    public EmbedLaunchTicketAuthority(Clock clock, Duration ttl, int capacity) {
        this(clock, ttl, capacity, secureTokens());
    }

    EmbedLaunchTicketAuthority(Clock clock, Duration ttl, int capacity, Supplier<String> tokens) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = boundedTtl(ttl, "ticket");
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("invalid ticket capacity");
        this.capacity = capacity;
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Returns the raw ticket once; only its digest and the captured registration are retained. */
    public synchronized IssuedTicket issue(EmbedRegistrationAggregate registration) {
        Objects.requireNonNull(registration, "registration");
        Instant now = clock.instant();
        cleanup(now);
        if (tickets.size() >= capacity) throw new CapacityExceededException();
        for (int attempt = 0; attempt < 8; attempt++) {
            String ticket = requireOpaque(tokens.get());
            Instant expiresAt = now.plus(ttl);
            if (tickets.putIfAbsent(digest(ticket), new Entry(expiresAt, registration)) == null) {
                return new IssuedTicket(ticket, expiresAt);
            }
        }
        throw new IllegalStateException("secure ticket source repeatedly collided");
    }

    /**
     * Atomic removal is the consume CAS: exactly one concurrent request receives the bound
     * registration, and it is the exact aggregate revision the ticket was issued against.
     * A revocation or a re-provision between issue and consume makes the ticket dead here.
     */
    public Resolution consume(String ticket, EmbedRegistrationAuthority registrations) {
        Objects.requireNonNull(registrations, "registrations");
        if (ticket == null || ticket.isBlank()) return Resolution.Unavailable.INSTANCE;
        Entry removed;
        try {
            removed = tickets.remove(digest(ticket));
        } catch (IllegalArgumentException invalid) {
            return Resolution.Unavailable.INSTANCE;
        }
        if (removed == null || !clock.instant().isBefore(removed.expiresAt())
                || !registrations.isCurrent(removed.registration())) {
            return Resolution.Unavailable.INSTANCE;
        }
        return new Resolution.Available(removed.registration());
    }

    synchronized int retainedEntries() {
        cleanup(clock.instant());
        return tickets.size();
    }

    private void cleanup(Instant now) {
        tickets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private static Supplier<String> secureTokens() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] value = new byte[32];
            random.nextBytes(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        };
    }

    static Duration boundedTtl(Duration value, String name) {
        Objects.requireNonNull(value, name + "Ttl");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(name + " TTL must be positive and at most five minutes");
        }
        return value;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requireOpaque(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String requireOpaque(String value) {
        if (value == null || value.length() < 32 || value.length() > 256 || value.isBlank()) {
            throw new IllegalArgumentException("opaque value has an invalid length");
        }
        return value;
    }

    public record IssuedTicket(String value, Instant expiresAt) {
        public IssuedTicket {
            requireOpaque(value);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public sealed interface Resolution {
        record Available(EmbedRegistrationAggregate registration) implements Resolution {
            public Available { Objects.requireNonNull(registration, "registration"); }
        }
        enum Unavailable implements Resolution { INSTANCE }
    }

    private record Entry(Instant expiresAt, EmbedRegistrationAggregate registration) { }

    public static final class CapacityExceededException extends RuntimeException {
        private CapacityExceededException() { super("embed ticket capacity exhausted"); }
    }
}
