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
 * operator authority makes the refusal conditional on the configuration being supportable — and every
 * remaining refusal names which part of the configuration is missing. A refusal that cannot say why
 * is a refusal an operator resolves by guessing.</p>
 *
 * <h2>Fail-closed, and no default that opens</h2>
 * <p>Every branch here refuses on absence. There is no fallback registration directory, no assumed
 * replica count and no implicit single-process acknowledgement: an operator who has not said where
 * the durable authority lives has not configured one, and an embed enabled without one would serve a
 * registry that nothing can write to and that forgets every revocation on restart.</p>
 */
public final class EmbedStartupCheck {

    /**
     * Where the durable registration authority lives.
     *
     * <p>Deliberately required rather than defaulted. The credential store defaults its directory
     * because a missing credential store degrades to «no author-entered credentials», which is a
     * usable state; a missing embed registration authority degrades to «the browser boundary is
     * enabled and authorizes nothing», which is a state that looks configured and is not.</p>
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
        String enabled = environment.get("RAVENROOT_EMBED_ENABLED");
        if (enabled == null || "false".equals(enabled)) return null;
        if (!"true".equals(enabled)) {
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "RAVENROOT_EMBED_ENABLED must be true or false");
        }
        String directory = environment.get(DIRECTORY_VARIABLE);
        if (directory == null || directory.isBlank()) {
            // The same code the relevant contract used, deliberately: an operator who enabled the embed against a
            // build that had no authority at all and one who enabled it without configuring where the
            // authority lives are in the same position, and the detail now says what to do about it.
            return new Refusal("EMBED_OPERATOR_AUTHORITY_UNAVAILABLE",
                    "packaged embed requires a durable operator provision-revoke authority; set "
                            + DIRECTORY_VARIABLE);
        }
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
                    "the embed registration authority is single-host; " + ReplicaCount.VARIABLE
                            + " is " + replicas + " and no shared adapter is available");
        }
        if (!"true".equals(environment.get("RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED"))) {
            return new Refusal("EMBED_SINGLE_PROCESS_NOT_ACKNOWLEDGED",
                    "set RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED=true to accept that embed "
                            + "sessions and registrations are single-host");
        }
        String viewerOrigin = environment.get("RAVENROOT_EMBED_VIEWER_ORIGIN");
        if (viewerOrigin == null || viewerOrigin.isBlank()) {
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "RAVENROOT_EMBED_VIEWER_ORIGIN is required");
        }
        try {
            new EmbedViewerOrigin(viewerOrigin.trim());
        } catch (IllegalArgumentException invalid) {
            // The operator's own value is not echoed: it reaches stderr and, from there, a log
            // aggregator, and the useful part is which setting is wrong rather than what it said.
            return new Refusal("EMBED_CONFIGURATION_INVALID",
                    "RAVENROOT_EMBED_VIEWER_ORIGIN must be an https origin with no path");
        }
        return null;
    }
}
