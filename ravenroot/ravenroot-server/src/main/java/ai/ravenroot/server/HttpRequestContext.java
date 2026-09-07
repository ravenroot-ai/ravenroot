package ai.ravenroot.server;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable server-owned identity and network context for exactly one HTTP request. */
public final class HttpRequestContext {
    /** Checked handler used while adapting the JDK's exchange-only callback. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Handles one exchange with the server-owned context minted for that request.
         * @param exchange current JDK exchange
         * @param context immutable request context
         * @throws IOException when the HTTP response cannot be completed
         */
        void handle(HttpExchange exchange, HttpRequestContext context) throws IOException;
    }

    private final String requestId;
    private final String clientAddress;
    private final boolean forwarded;
    private final AuthenticatedPrincipal principal;

    private HttpRequestContext(String requestId, String clientAddress, boolean forwarded,
                               AuthenticatedPrincipal principal) {
        this.requestId = requireText(requestId, "requestId");
        this.clientAddress = requireText(clientAddress, "clientAddress");
        this.forwarded = forwarded;
        this.principal = principal;
    }

    static HttpRequestContext create() {
        return create(UUID.randomUUID().toString());
    }

    static HttpRequestContext create(String requestId) {
        return new HttpRequestContext(requestId, RateLimitAuditEvent.UNKNOWN, false, null);
    }

    HttpRequestContext withClient(String clientAddress, boolean forwarded) {
        return new HttpRequestContext(requestId, clientAddress, forwarded, principal);
    }

    HttpRequestContext withPrincipal(AuthenticatedPrincipal principal) {
        return new HttpRequestContext(requestId, clientAddress, forwarded,
                Objects.requireNonNull(principal, "principal"));
    }

    /** @return the server-minted correlation identifier for this request */
    public String requestId() {
        return requestId;
    }

    /** @return the resolved client address, or the server's unknown marker before resolution */
    public String clientAddress() {
        return clientAddress;
    }

    /** @return whether the client address came from an accepted forwarded chain */
    public boolean forwarded() {
        return forwarded;
    }

    /** @return the authenticated principal after authentication, otherwise empty */
    public Optional<AuthenticatedPrincipal> principal() {
        return Optional.ofNullable(principal);
    }

    /** @return the authenticated principal
     * @throws IllegalStateException before authentication has established one
     */
    public AuthenticatedPrincipal requirePrincipal() {
        return principal().orElseThrow(
                () -> new IllegalStateException("Authenticated principal is unavailable"));
    }

    /** @return an immutable application context for the authenticated principal */
    public RequestContext applicationContext() {
        return applicationContext(requirePrincipal());
    }

    /**
     * Rebuilds application identity after same-request credential refresh without minting a new id.
     * @param currentPrincipal freshly revalidated principal for the same security identity
     * @return a distinct immutable application context with the original request id
     */
    RequestContext applicationContext(AuthenticatedPrincipal currentPrincipal) {
        AuthenticatedPrincipal current = Objects.requireNonNull(currentPrincipal, "currentPrincipal");
        return new RequestContext(requestId, current.subject(),
                current.type() == AuthenticatedPrincipal.Type.USER
                        ? PrincipalType.USER : PrincipalType.WORKLOAD,
                current.issuer(), current.tenantId(), current.roles(), current.scopes());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
