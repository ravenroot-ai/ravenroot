package ai.ravenroot.server.security;

/** Deliberately carries no token or claim details suitable for an HTTP response. */
public final class AuthenticationException extends Exception {
    public AuthenticationException(String internalReason) {
        super(internalReason);
    }

    public AuthenticationException(String internalReason, Throwable cause) {
        super(internalReason, cause);
    }
}
