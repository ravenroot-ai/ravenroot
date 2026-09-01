package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.AuthorizedEmbedGraphProjection;
import ai.ravenroot.api.embed.AuthorizedEmbedSessionCreation;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Typed, closed-by-default composition for the distinct-origin browser surface. */
public record EmbedBrowserConfiguration(boolean enabled, EmbedViewerOrigin viewerOrigin,
                                        AuthorizedEmbedSessionCreation sessionCreation,
                                        EmbedRegistrationAuthority registrations,
                                        AuthorizedEmbedGraphProjection projections,
                                        EmbedSecurityAuditSink audit,
                                        Clock clock, Duration ticketTtl, Duration exchangeTtl,
                                        Duration bearerTtl, Duration proofTtl,
                                        int ticketCapacity, int sessionCapacity, int replayCapacity,
                                        int replicaCount, boolean singleProcessAcknowledged) {

    /**
     * The variable every Ravenroot deployment actually sets.
     *
     * <p>This guard must read {@code RAVENROOT_REPLICAS}, the variable the deployment sets:
     * {@code compose.yaml}, {@code deploy/kubernetes/ravenroot.yaml}, the Helm deployment template
     * and the OCR container smoke script all set {@code RAVENROOT_REPLICAS}. The multi-replica
     * refusal below therefore always read its own default of one and was inert in production — a
     * three-replica deployment would have enabled the embed believing itself single-process. The
     * name is a constant here, and {@link ai.ravenroot.server.ReplicaCount} is the single parser, so
     * this guard and the rest of the server can no longer disagree about what a replica count is.</p>
     */
    public static final String REPLICAS_VARIABLE = ai.ravenroot.server.ReplicaCount.VARIABLE;

    public EmbedBrowserConfiguration {
        if (enabled) {
            Objects.requireNonNull(viewerOrigin, "viewerOrigin");
            Objects.requireNonNull(sessionCreation, "sessionCreation");
            Objects.requireNonNull(registrations, "registrations");
            Objects.requireNonNull(projections, "projections");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(clock, "clock");
            EmbedLaunchTicketAuthority.boundedTtl(ticketTtl, "ticket");
            EmbedLaunchTicketAuthority.boundedTtl(exchangeTtl, "exchange");
            EmbedLaunchTicketAuthority.boundedTtl(bearerTtl, "bearer");
            EmbedLaunchTicketAuthority.boundedTtl(proofTtl, "proof");
            if (ticketCapacity < 1 || sessionCapacity < 1 || replayCapacity < 1) {
                throw new IllegalArgumentException("embed capacities must be positive");
            }
            // SQLite is a local file, so the durable registration authority is single-host. Until a
            // shared adapter exists, more than one replica means two authorities that cannot see each
            // other's revocations, and the answer is to refuse rather than to route stickily.
            if (replicaCount != 1 || !singleProcessAcknowledged) {
                throw new IllegalArgumentException(
                        "embed browser requires an acknowledged single-process deployment; "
                                + REPLICAS_VARIABLE + " must be 1 and "
                                + "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED must be true");
            }
        }
    }

    public static EmbedBrowserConfiguration disabled() {
        return new EmbedBrowserConfiguration(false, null, null, null, null, null, null,
                null, null, null, null, 0, 0, 0, 0, false);
    }

    public static EmbedBrowserConfiguration fromEnvironment(
            Map<String, String> environment, AuthorizedEmbedSessionCreation sessionCreation,
            EmbedRegistrationAuthority registrations, AuthorizedEmbedGraphProjection projections,
            EmbedSecurityAuditSink audit, Clock clock) {
        Objects.requireNonNull(environment, "environment");
        if (!strictBoolean(environment, "RAVENROOT_EMBED_ENABLED", false)) return disabled();
        return new EmbedBrowserConfiguration(true,
                new EmbedViewerOrigin(required(environment, "RAVENROOT_EMBED_VIEWER_ORIGIN")),
                sessionCreation, registrations, projections, audit, clock,
                seconds(environment, "RAVENROOT_EMBED_TICKET_TTL_SECONDS", 60),
                seconds(environment, "RAVENROOT_EMBED_EXCHANGE_TTL_SECONDS", 60),
                seconds(environment, "RAVENROOT_EMBED_BEARER_TTL_SECONDS", 120),
                seconds(environment, "RAVENROOT_EMBED_PROOF_TTL_SECONDS", 60),
                integer(environment, "RAVENROOT_EMBED_TICKET_CAPACITY", 4_096),
                integer(environment, "RAVENROOT_EMBED_SESSION_CAPACITY", 4_096),
                integer(environment, "RAVENROOT_EMBED_REPLAY_CAPACITY", 16_384),
                ai.ravenroot.server.ReplicaCount.fromEnvironment(environment),
                strictBoolean(environment, "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", false));
    }

    public boolean active() { return enabled; }

    private static Duration seconds(Map<String, String> environment, String name, int fallback) {
        return Duration.ofSeconds(integer(environment, name, fallback));
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        try {
            return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static boolean strictBoolean(Map<String, String> environment, String name, boolean fallback) {
        String value = environment.get(name);
        if (value == null) return fallback;
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
