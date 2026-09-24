package ai.ravenroot.api.deployment;

import java.util.Objects;

/** Typed extension-to-core source-start refusal. Core publishes its code only when registered. */
public final class SourceStartException extends RuntimeException {
    private final String code;

    public SourceStartException(SourceStartFailureCode code) {
        this(code, null);
    }

    public SourceStartException(SourceStartFailureCode code, Throwable cause) {
        super("The inbound source refused startup", cause);
        this.code = SourceStartFailureCode.requireValid(Objects.requireNonNull(code, "code").code());
    }

    public String code() {
        return code;
    }
}
