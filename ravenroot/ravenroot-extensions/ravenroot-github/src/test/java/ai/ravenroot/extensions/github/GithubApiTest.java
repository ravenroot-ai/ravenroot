package ai.ravenroot.extensions.github;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GithubApiTest {
    @Test void recognizesPrimaryAndSecondaryRateLimits() {
        assertTrue(response(403, Map.of("x-ratelimit-remaining", List.of("0"))).rateLimited());
        assertTrue(response(403, Map.of("retry-after", List.of("2"))).rateLimited());
        assertTrue(response(429, Map.of()).rateLimited());
        assertFalse(response(403, Map.of()).rateLimited());
    }

    @Test void retryTimeIsClampedForPastAndExcessiveProviderHints() {
        long before = System.currentTimeMillis();
        long past = response(403, Map.of("x-ratelimit-reset", List.of("1"))).retryAfterEpochMs();
        assertTrue(past >= before + 200 && past <= System.currentTimeMillis() + 1_000);
        long excessive = response(429, Map.of("retry-after", List.of("999999"))).retryAfterEpochMs();
        assertTrue(excessive >= before + 299_000 && excessive <= System.currentTimeMillis() + 300_500);
    }

    private static GithubApi.Response response(int status, Map<String, List<String>> headers) {
        return new GithubApi.Response(status, headers, "{}".getBytes(StandardCharsets.UTF_8));
    }
}
