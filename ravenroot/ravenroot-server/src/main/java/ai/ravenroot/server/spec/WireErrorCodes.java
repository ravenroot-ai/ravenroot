package ai.ravenroot.server.spec;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.server.ratelimit.ActiveExecutionRegistry;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The complete wire error-code vocabulary: {@link ErrorCode} <b>plus</b> the rate limiter's own string
 * constants (API-05). A list built from {@link ErrorCode} alone is incomplete — {@code RavenrootServer}
 * answers a throttled or shape-rejected request via {@code ErrorEnvelope#ofServerCode}, which puts the
 * rate limiter's own token, not an {@link ErrorCode} name, on the wire in the {@code code} field
 * ({@code RavenrootServer.refuse}).
 *
 * <p>Two of these are already named public constants ({@link ActiveExecutionRegistry#TENANT_LIMIT_CODE},
 * {@link ActiveExecutionRegistry#GLOBAL_LIMIT_CODE}) and are referenced directly. The other six are
 * {@code RateLimiter}'s own inline string literals (request-shape rejection and token-bucket capacity
 * exhaustion) — not exposed as named constants there, and this class does not add any to avoid touching
 * rate-limiting logic for a documentation concern. They are copied here instead, each cited to its exact
 * source line, and {@code WireErrorCodesLiveVerificationTest} exercises {@code RateLimiter} directly to
 * catch the copies going stale rather than trusting the citation alone.</p>
 */
public final class WireErrorCodes {
    /** {@code ActiveExecutionRegistry.EXPIRED_CODE} and {@code DISPLACED_CODE} are audit-only outcomes,
     * never returned to an HTTP caller (see their own call sites) -- deliberately excluded here. */
    private WireErrorCodes() {
    }

    // RateLimiter.java:135 -- oversized single header value.
    public static final String HEADER_VALUE_TOO_LARGE = "HEADER_VALUE_TOO_LARGE";
    // RateLimiter.java:139 -- too many request headers.
    public static final String TOO_MANY_HEADERS = "TOO_MANY_HEADERS";
    // RateLimiter.java:142 -- total header bytes over budget.
    public static final String HEADERS_TOO_LARGE = "HEADERS_TOO_LARGE";
    // RateLimiter.java:148 -- raw query string over budget.
    public static final String QUERY_TOO_LARGE = "QUERY_TOO_LARGE";
    // RateLimiter.java:153 -- too many query parameters.
    public static final String TOO_MANY_QUERY_PARAMETERS = "TOO_MANY_QUERY_PARAMETERS";
    // RateLimiter.java:271 -- token-bucket capacity exhausted. The verification test does not
    // trigger this path live, so the declaration is pinned to source inspection.
    public static final String LIMITER_CAPACITY_EXHAUSTED = "LIMITER_CAPACITY_EXHAUSTED";
    // EmbedBrowserHttpHandler -- deliberately sanitized, feature-specific failures.
    public static final String EMBED_REQUEST_INVALID = "EMBED_REQUEST_INVALID";
    public static final String EMBED_METHOD_NOT_ALLOWED = "EMBED_METHOD_NOT_ALLOWED";
    public static final String EMBED_SESSION_UNAVAILABLE = "EMBED_SESSION_UNAVAILABLE";
    public static final String EMBED_TEMPORARILY_UNAVAILABLE = "EMBED_TEMPORARILY_UNAVAILABLE";
    public static final String EMBED_DATA_TOO_LARGE = "EMBED_DATA_TOO_LARGE";
    public static final String EMBED_REQUEST_TOO_LARGE = "EMBED_REQUEST_TOO_LARGE";

    /** Every code an HTTP caller can actually observe: {@link ErrorCode} names plus the constants above. */
    public static Set<String> all() {
        var codes = new LinkedHashSet<String>();
        Arrays.stream(ErrorCode.values()).map(ErrorCode::code).forEach(codes::add);
        codes.add(ActiveExecutionRegistry.TENANT_LIMIT_CODE);
        codes.add(ActiveExecutionRegistry.GLOBAL_LIMIT_CODE);
        codes.add(HEADER_VALUE_TOO_LARGE);
        codes.add(TOO_MANY_HEADERS);
        codes.add(HEADERS_TOO_LARGE);
        codes.add(QUERY_TOO_LARGE);
        codes.add(TOO_MANY_QUERY_PARAMETERS);
        codes.add(LIMITER_CAPACITY_EXHAUSTED);
        codes.add(EMBED_REQUEST_INVALID);
        codes.add(EMBED_METHOD_NOT_ALLOWED);
        codes.add(EMBED_SESSION_UNAVAILABLE);
        codes.add(EMBED_TEMPORARILY_UNAVAILABLE);
        codes.add(EMBED_DATA_TOO_LARGE);
        codes.add(EMBED_REQUEST_TOO_LARGE);
        return Set.copyOf(codes);
    }
}
