package ai.ravenroot.extensions.openapi.server;

/** Stable, content-free configuration failure. Request failures are mapped to bounded HTTP responses. */
public final class OpenApiServerException extends RuntimeException {
    public enum Code { CONFIGURATION_INVALID, PROFILE_UNKNOWN, LIFECYCLE_INVALID, ROUTE_UNAVAILABLE }

    private final Code code;

    OpenApiServerException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
