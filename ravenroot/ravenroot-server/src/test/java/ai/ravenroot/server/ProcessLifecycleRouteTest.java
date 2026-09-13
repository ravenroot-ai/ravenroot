package ai.ravenroot.server;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.process.ProcessLifecycleService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLifecycleRouteTest {
    @TempDir Path directory;

    @Test
    void routeRequiresGenerationAndIdempotencyAndReturnsTypedStaleOutcome() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("process-route.db"), clock);
             var engine = new PekkoExecutionEngine("process-route")) {
            UUID processId = UUID.randomUUID();
            var key = new ExecutionKey("tenant-a", processId);
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(
                            new ProcessInstance(processId, ProcessInstanceStatus.ACCEPTED, Map.of()),
                            new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join();
            long generation = store.findProcessInstance(key).toCompletableFuture().join()
                    .orElseThrow().revision();
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, new OperatorAuth())) {
                server.installProcessLifecycle(new ProcessLifecycleService(store, application, null, clock));
                server.start();
                assertEquals(400, post(server, processId, null, null).statusCode());
                HttpResponse<String> applied = post(server, processId, generation, "pause-command");
                assertEquals(200, applied.statusCode(), applied.body());
                assertTrue(applied.body().contains("\"outcome\":\"APPLIED\""), applied.body());
                assertTrue(applied.body().contains("\"state\":\"PAUSED\""), applied.body());
                HttpResponse<String> replay = post(server, processId, generation, "pause-command");
                assertEquals(200, replay.statusCode(), replay.body());
                assertTrue(replay.body().contains("\"outcome\":\"REPLAYED\""), replay.body());
                HttpResponse<String> stale = post(server, processId, generation, "resume-command");
                assertEquals(409, stale.statusCode(), stale.body());
                assertTrue(stale.body().contains("\"outcome\":\"STALE_GENERATION\""), stale.body());
            }
        }
    }

    private static HttpResponse<String> post(RavenrootServer server, UUID processId, Long generation,
                                             String idempotencyKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + "/v1/processes/" + processId + "/pause"));
        if (generation != null) request.header("X-Ravenroot-Expected-Generation", generation.toString());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class OperatorAuth implements RequestAuthenticator {
        @Override public AuthenticatedPrincipal authenticate(Headers headers) {
            return new AuthenticatedPrincipal("operator", AuthenticatedPrincipal.Type.USER, "test",
                    "tenant-a", Set.of(Role.OPERATOR),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
