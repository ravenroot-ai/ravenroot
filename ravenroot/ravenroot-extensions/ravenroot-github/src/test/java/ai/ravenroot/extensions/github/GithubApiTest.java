package ai.ravenroot.extensions.github;

import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GithubApiTest {
    @TempDir Path directory;

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

    @Test void graphqlFloatingNumbersMustBeFiniteExactAndInIeeeIntegerRange() {
        assertEquals(7L, GithubValues.number(7.0d, 0, 10));
        assertEquals(9_007_199_254_740_991L,
                GithubValues.number(9_007_199_254_740_991d, 0, 9_007_199_254_740_991L));
        assertThrows(GithubException.class, () -> GithubValues.number(7.5d, 0, 10));
        assertThrows(GithubException.class, () -> GithubValues.number(Double.NaN, 0, 10));
        assertThrows(GithubException.class, () -> GithubValues.number(Double.POSITIVE_INFINITY, 0, 10));
        assertThrows(GithubException.class, () -> GithubValues.number(9_007_199_254_740_992d,
                0, Long.MAX_VALUE));
    }

    @Test void failureBetweenPostPublicationChecksCancelsTheStartedCall() {
        GithubApi.CallControl[] holder = new GithubApi.CallControl[1];
        holder[0] = new GithubApi.CallControl(() -> holder[0].fail(
                new GithubException(GithubException.Code.CAS_LOST)));
        AtomicBoolean cancelled = new AtomicBoolean();
        OutboundCall<OutboundHttpResponse> call = cancellable(cancelled);
        GithubException failure = assertThrows(GithubException.class, () -> holder[0].attach(call));
        assertEquals(GithubException.Code.CAS_LOST, failure.code());
        assertTrue(cancelled.get());
    }

    @Test void cancellationBeforeFirstAttachCheckCancelsTheAlreadyStartedCall() {
        GithubApi.CallControl control = new GithubApi.CallControl();
        assertTrue(control.cancel());
        AtomicBoolean cancelled = new AtomicBoolean();
        GithubException failure = assertThrows(GithubException.class,
                () -> control.attach(cancellable(cancelled)));
        assertEquals(GithubException.Code.CANCELLED, failure.code());
        assertTrue(cancelled.get());
    }

    @Test void failureBeforeFirstAttachCheckCancelsTheAlreadyStartedCall() {
        GithubApi.CallControl control = new GithubApi.CallControl();
        control.fail(new GithubException(GithubException.Code.CAS_LOST));
        AtomicBoolean cancelled = new AtomicBoolean();
        GithubException failure = assertThrows(GithubException.class,
                () -> control.attach(cancellable(cancelled)));
        assertEquals(GithubException.Code.CAS_LOST, failure.code());
        assertTrue(cancelled.get());
    }

    @Test void managedOutputAuthoritySurvivesTheWireAdapterUntilFinalProjection() {
        var http = new GithubTestSupport.HttpHarness();
        http.replies.add(new OutboundHttpResponse(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8), 32));
        GithubProfile profile = GithubTestSupport.configuration(directory.resolve("output.db"))
                .profile(GithubTestSupport.TENANT, GithubTestSupport.PROFILE).orElseThrow();
        GithubApi api = new GithubApi(http, GithubTestSupport.message(Map.of()), profile,
                new GithubApi.CallControl(), () -> { });

        api.get("/repos/example/service");
        GithubException refused = assertThrows(GithubException.class,
                () -> api.requireOutput(NodeResult.continueWith(Map.of(
                        "version", "github.result.v1", "value", "expanded-output"))));

        assertEquals(GithubException.Code.RESPONSE_INVALID, refused.code());
    }

    private static OutboundCall<OutboundHttpResponse> cancellable(AtomicBoolean cancelled) {
        return new OutboundCall<>() {
            @Override public CompletableFuture<OutboundHttpResponse> completion() { return new CompletableFuture<>(); }
            @Override public boolean cancel() { cancelled.set(true); return true; }
        };
    }

    private static GithubApi.Response response(int status, Map<String, List<String>> headers) {
        return new GithubApi.Response(status, headers, "{}".getBytes(StandardCharsets.UTF_8));
    }
}
