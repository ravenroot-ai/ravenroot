package ai.ravenroot.api.security;

/** Deliberately contains only a stable policy reason and no request payload. */
public final class AuthorizationDeniedException extends SecurityException {
/**
 * Signals an authorization refusal without retaining request payload.
 * @param reason stable, safe-to-disclose policy reason
 */
    public AuthorizationDeniedException(String reason) {
        super(reason);
    }
}
