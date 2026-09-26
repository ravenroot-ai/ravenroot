package ai.ravenroot.server.embed;

import ai.ravenroot.server.ReplicaCount;

import java.util.Map;
import java.util.Objects;

/**
 * The packaged process's decision about whether the embed browser may be enabled at all.
 *
 * <h2>What replaced the unconditional refusal</h2>
 * <p>The packaged {@code Main} previously refused {@code RAVENROOT_EMBED_ENABLED=true} outright,
 * with a fixed diagnostic, because no operator authority existed to provision against. A durable
 * operator authority or an explicit dynamic read-only policy makes the refusal conditional on the
 * configuration being supportable — and every remaining refusal names which part is missing.</p>
 *
 * <h2>Fail-closed, and no default that opens</h2>
 * <p>Every branch here refuses on absence. There is no fallback registration directory, no assumed
 * replica count and no implicit single-process acknowledgement. Dynamic authority is accepted only
 * when an operator explicitly selects an origin mode; its grants are intentionally process-local.</p>
 */
public final class EmbedStartupCheck {

    /**
     * Where the durable registration authority lives.
     *
     * <p>Deliberately has no default. It is optional only when an explicit dynamic origin policy
     * provides the authority for short-lived, process-local grants.</p>
     */
    public static final String DIRECTORY_VARIABLE = "RAVENROOT_EMBED_REGISTRATION_DIR";

    /** A refusal an operator can act on: a stable code and a detail that names no secret. */
    public record Refusal(String code, String detail) {
        public Refusal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }

        /** The exact line the packaged process prints on stderr before exiting. */
        public String diagnostic() {
            return "{\"event\":\"startup_refused\",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}";
        }
    }

    private EmbedStartupCheck() {
    }

    /**
     * @return the reason to refuse startup, or {@code null} when the embed is disabled or supportable
     */
    public static Refusal evaluate(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        final boolean enabled;
        try {
            enabled = EmbedBrowserConfiguration.enabledFromEnvironment(environment);
        } catch (IllegalArgumentException invalid) {
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "RAVENROOT_EMBED_ENABLED must be true or false");
        }
        if (!enabled) return null;
        int replicas;
        try {
            replicas = ReplicaCount.fromEnvironment(environment);
        } catch (IllegalArgumentException malformed) {
            return new Refusal("EMBED_CONFIGURATION_INVALID", ReplicaCount.VARIABLE
                    + " must be a positive integer");
        }
        if (replicas != 1) {
            // Not a temporary limitation of this build: SQLite is a local file, so two replicas are
            // two authorities. Sticky routing would make a revocation visible on one of them.
            return new Refusal("EMBED_MULTI_REPLICA_UNSUPPORTED",
                    "embed authorities and sessions are process-local; " + ReplicaCount.VARIABLE
                            + " is " + replicas + " and no shared adapter is available");
        }
        if (!"true".equals(environment.get("RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED"))) {
            return new Refusal("EMBED_SINGLE_PROCESS_NOT_ACKNOWLEDGED",
                    "set RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED=true to accept that embed "
                            + "sessions, dynamic grants, and registrations are single-host");
        }
        String viewerOrigin = environment.get("RAVENROOT_EMBED_VIEWER_ORIGIN");
        if (viewerOrigin == null || viewerOrigin.isBlank()) {
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "RAVENROOT_EMBED_VIEWER_ORIGIN is required");
        }
        try {
            var viewer = new EmbedViewerOrigin(viewerOrigin.trim());
            var dynamic = DynamicEmbedAuthorizationPolicy.fromEnvironment(environment, viewer);
            String directory = environment.get(DIRECTORY_VARIABLE);
            if ((directory == null || directory.isBlank()) && !dynamic.enabled()) {
                return new Refusal("EMBED_OPERATOR_AUTHORITY_UNAVAILABLE",
                        "packaged embed requires either a durable registration authority or an explicit "
                                + "dynamic origin policy; set " + DIRECTORY_VARIABLE + " or "
                                + DynamicEmbedAuthorizationPolicy.MODE_VARIABLE);
            }
        } catch (IllegalArgumentException invalid) {
            // The operator's own value is not echoed: it reaches stderr and, from there, a log
            // aggregator, and the useful part is which setting is wrong rather than what it said.
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "embed viewer or dynamic origin policy configuration is invalid");
        }
        return null;
    }
}
