package ai.ravenroot.core.manifest;

import java.util.Objects;

/** Typed refusal raised before admission when a complete manifest cannot be resolved. */
public final class ExecutionManifestResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Closed reasons that never carry provider-controlled text. */
    public enum Reason {
        CAPACITY_PROFILE_UNAVAILABLE("capacity-profile-unavailable");

        private final String token;

        Reason(String token) {
            this.token = token;
        }

        /** Stable bounded token suitable for diagnostics. */
        public String token() {
            return token;
        }
    }

    private final Reason reason;

    ExecutionManifestResolutionException(Reason reason) {
        super("execution manifest cannot be resolved: " + Objects.requireNonNull(reason, "reason").token());
        this.reason = reason;
    }

    /** The closed reason admission was refused. */
    public Reason reason() {
        return reason;
    }
}
