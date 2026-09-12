package ai.ravenroot.server;

import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpExchange;

/**
 * Compatibility registry for direct exchange-only handlers composed outside {@link RavenrootServer}.
 * Call {@link #install(HttpExchange, AuthenticatedPrincipal)} once at that handler's authentication
 * boundary and {@link #clear(HttpExchange)} when the request finishes. Ravenroot's own route wrappers
 * pass an immutable {@link HttpRequestContext} explicitly and do not use this registry. The JDK
 * exchange attribute map belongs to an {@code HttpContext}, so this adapter deliberately does not use it.
 */
public final class AuthenticatedPrincipalAttribute {
    public static final String NAME = AuthenticatedPrincipal.class.getName();
    private static final java.util.Map<HttpExchange, String> REQUEST_IDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<HttpExchange, AuthenticatedPrincipal> PRINCIPALS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private AuthenticatedPrincipalAttribute() {
    }

    public static AuthenticatedPrincipal require(HttpExchange exchange) {
        return find(exchange).orElseThrow(() -> new IllegalStateException("Authenticated principal is unavailable"));
    }

    public static void install(HttpExchange exchange, AuthenticatedPrincipal principal) {
        PRINCIPALS.put(java.util.Objects.requireNonNull(exchange, "exchange"),
                java.util.Objects.requireNonNull(principal, "principal"));
    }

    public static java.util.Optional<AuthenticatedPrincipal> find(HttpExchange exchange) {
        return java.util.Optional.ofNullable(PRINCIPALS.get(java.util.Objects.requireNonNull(exchange, "exchange")));
    }

    public static void clear(HttpExchange exchange) {
        PRINCIPALS.remove(java.util.Objects.requireNonNull(exchange, "exchange"));
    }

    /**
     * The request context for this exchange, with a <strong>stable</strong> correlation id (SEC-07).
     *
     * <p>The id is minted once per exchange and cached, so every authorization decision, artifact
     * lifecycle record and execution event produced while serving one HTTP request shares one
     * {@code requestId}. The cache is keyed by the exchange object rather than an exchange attribute:
     * the JDK server backs those attributes with the shared {@code HttpContext}, so putting a request
     * id there reuses it across later requests on the same route. Weak keys release completed
     * exchanges without a lifecycle callback.</p>
     *
     * <p>Previously a fresh {@code UUID.randomUUID()} was minted on every call, which
     * made an audit trail unjoinable for any request that consulted the context more than once — most
     * visibly the SSE stream, where each credential revalidation produced an unrelated id and the
     * connection's records could not be tied back to the lease that authorized it.</p>
     *
     * <p>The id is always minted here and never taken from an inbound header. Honouring a client
     * supplied {@code X-Request-Id} would let a caller collide or poison another principal's audit
     * records, and the correlation key is exactly the field that must not be attacker-chosen.</p>
     */
    public static ai.ravenroot.api.security.RequestContext requestContext(HttpExchange exchange) {
        return requestContext(requestId(exchange), require(exchange));
    }

    /**
     * Rebuilds the context for a refreshed principal while preserving the exchange's correlation id.
     * A revalidated credential is the same request, so it must not become a new one in the audit log.
     */
    public static ai.ravenroot.api.security.RequestContext requestContext(HttpExchange exchange,
                                                                          AuthenticatedPrincipal principal) {
        return requestContext(requestId(exchange), principal);
    }

    /** Correlation id for exchanges that need to log it without building a full context. */
    public static String requestId(HttpExchange exchange) {
        java.util.Objects.requireNonNull(exchange, "exchange");
        synchronized (REQUEST_IDS) {
            return REQUEST_IDS.computeIfAbsent(exchange,
                    ignored -> java.util.UUID.randomUUID().toString());
        }
    }

    static ai.ravenroot.api.security.RequestContext requestContext(String requestId,
                                                                   AuthenticatedPrincipal principal) {
        return new ai.ravenroot.api.security.RequestContext(requestId,
                principal.subject(),
                principal.type() == AuthenticatedPrincipal.Type.USER
                        ? ai.ravenroot.api.security.PrincipalType.USER
                        : ai.ravenroot.api.security.PrincipalType.WORKLOAD,
                principal.issuer(), principal.tenantId(), principal.roles(), principal.scopes());
    }
}
