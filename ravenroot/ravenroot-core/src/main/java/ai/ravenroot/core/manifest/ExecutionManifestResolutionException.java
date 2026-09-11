package ai.ravenroot.core.manifest;

/** Refusal to restore operational values that an execution requires. */
public final class ExecutionManifestResolutionException extends IllegalStateException {
    public enum Reason { CAPACITY_PROFILE_UNAVAILABLE, LEGACY_OPERATIONAL_POLICY_UNAVAILABLE }

    private final Reason reason;

    public ExecutionManifestResolutionException(Reason reason) {
        super(switch (reason) {
            case CAPACITY_PROFILE_UNAVAILABLE ->
                    "a used node package has no resolved external-I/O capacity profile";
            case LEGACY_OPERATIONAL_POLICY_UNAVAILABLE ->
                    "the legacy manifest did not record required operational policy";
        });
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
