package ai.ravenroot.api.deployment;

import java.util.Objects;

/** Typed extension-to-core source-start refusal. Core publishes its code only when registered. */
public final class SourceStartException extends RuntimeException {
    /** Validated extension-owned failure code candidate. */
    private final String code;

    /**
     * Creates a typed source-start refusal without a privileged cause.
     * @param code extension-owned declared failure code
     */
    public SourceStartException(SourceStartFailureCode code) {
        this(code, null);
    }

    /**
     * Creates a typed source-start refusal retaining its cause for the privileged sink.
     * @param code extension-owned declared failure code
     * @param cause original trusted-server diagnostic cause, or {@code null}
     */
    public SourceStartException(SourceStartFailureCode code, Throwable cause) {
        super("The inbound source refused startup", cause);
        this.code = SourceStartFailureCode.requireValid(Objects.requireNonNull(code, "code").code());
    }

    /**
     * Returns the validated lower-kebab public code candidate.
     * @return validated source-start code
     */
    public String code() {
        return code;
    }
}
