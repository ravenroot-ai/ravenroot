package ai.ravenroot.server.spec;

import ai.ravenroot.server.ratelimit.RateLimitAuditSink;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API-05: {@link WireErrorCodes}' five request-shape string constants are copies of
 * {@code RateLimiter}'s own inline literals (cited by line in {@link WireErrorCodes}, not extracted to
 * named constants there to avoid touching rate-limiting logic for a documentation concern). A citation
 * is not verification -- this class calls the real {@link RateLimiter#checkRequestShape} with inputs
 * crafted to trip each condition and asserts the live decision's code equals the declared constant, so
 * a copy going stale reds here immediately.
 *
 * <p>{@link WireErrorCodes#LIMITER_CAPACITY_EXHAUSTED} is not exercised here (token-bucket exhaustion,
 * a different method) and is named here as an explicit residual rather than silently skipped.</p>
 */
class WireErrorCodesLiveVerificationTest {
    private final RateLimiter limiter = new RateLimiter(RateLimitConfiguration.DEFAULTS,
            TrustedProxyConfiguration.direct(), RateLimitAuditSink.discarding());

    @Test
    void headerValueTooLarge() {
        var headers = new Headers();
        headers.put("X-Big", java.util.List.of("x".repeat(RateLimitConfiguration.DEFAULTS.maxHeaderValueBytes() + 1)));
        assertEquals(WireErrorCodes.HEADER_VALUE_TOO_LARGE, limiter.checkRequestShape(headers, null).code());
    }

    @Test
    void tooManyHeaders() {
        var headers = new Headers();
        for (int i = 0; i < RateLimitConfiguration.DEFAULTS.maxHeaderCount() + 1; i++) {
            headers.add("X-H" + i, "v");
        }
        assertEquals(WireErrorCodes.TOO_MANY_HEADERS, limiter.checkRequestShape(headers, null).code());
    }

    @Test
    void headersTooLarge() {
        var headers = new Headers();
        // Individually under maxHeaderValueBytes, but the running total crosses maxHeaderBytes first.
        int perHeader = Math.max(1, RateLimitConfiguration.DEFAULTS.maxHeaderValueBytes() / 2);
        String value = "x".repeat(perHeader);
        int needed = RateLimitConfiguration.DEFAULTS.maxHeaderBytes() / perHeader + 2;
        for (int i = 0; i < needed && i < RateLimitConfiguration.DEFAULTS.maxHeaderCount() - 1; i++) {
            headers.add("X-H" + i, value);
        }
        assertEquals(WireErrorCodes.HEADERS_TOO_LARGE, limiter.checkRequestShape(headers, null).code());
    }

    @Test
    void queryTooLarge() {
        String query = "a=" + "x".repeat(RateLimitConfiguration.DEFAULTS.maxQueryBytes() + 1);
        assertEquals(WireErrorCodes.QUERY_TOO_LARGE, limiter.checkRequestShape(new Headers(), query).code());
    }

    @Test
    void tooManyQueryParameters() {
        StringBuilder query = new StringBuilder("a=1");
        for (int i = 0; i < RateLimitConfiguration.DEFAULTS.maxQueryParameters() + 1; i++) {
            query.append("&a=1");
        }
        assertEquals(WireErrorCodes.TOO_MANY_QUERY_PARAMETERS,
                limiter.checkRequestShape(new Headers(), query.toString()).code());
    }
}
