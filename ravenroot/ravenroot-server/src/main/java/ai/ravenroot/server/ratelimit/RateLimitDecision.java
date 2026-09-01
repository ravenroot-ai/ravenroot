package ai.ravenroot.server.ratelimit;

/**
 * The outcome of one limit check.
 *
 * <p>Every field is drawn from a closed set the server defines. Nothing derived from the request — no
 * header value, no query string, no address, no tenant name — ever reaches a response body, so a 429 is
 * bounded by construction rather than by a length check that someone has to remember to apply. That
 * also removes reflected-content and log-injection questions from the rejection path entirely.</p>
 *
 * @param status             HTTP status to send
 * @param code               stable machine-readable code, from a fixed vocabulary
 * @param scope              which limit fired, for the audit record
 * @param retryAfterSeconds  {@code Retry-After} in delta-seconds, or {@code 0} when the status carries
 *                           no retry advice
 */
public record RateLimitDecision(int status, String code, String scope, long retryAfterSeconds) {
    private static final RateLimitDecision ALLOWED = new RateLimitDecision(0, "", "", 0);

    public static RateLimitDecision allowed() {
        return ALLOWED;
    }

    /**
     * A throttled request.
     *
     * <p>{@code Retry-After} is expressed in delta-seconds rather than as an HTTP-date. A date requires
     * the client and the server to agree on the current time; a delta does not, so it stays correct
     * across clock skew and across a server whose own clock is wrong. It is also never zero — advising
     * an immediate retry is advising the caller to produce the load that was just refused.</p>
     */
    public static RateLimitDecision throttled(String code, String scope, long retryAfterSeconds) {
        return new RateLimitDecision(429, code, scope, Math.max(1L, retryAfterSeconds));
    }

    /** A malformed or oversized request, which retrying unchanged will not fix. */
    public static RateLimitDecision rejected(int status, String code, String scope) {
        return new RateLimitDecision(status, code, scope, 0);
    }

    public boolean isAllowed() {
        return status == 0;
    }
}
