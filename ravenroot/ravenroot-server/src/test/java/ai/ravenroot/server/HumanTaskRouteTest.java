package ai.ravenroot.server;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanTaskRouteTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.payload+json";

    @TempDir
    Path directory;

    @Test
    void tenantInboxAndGenerationFencedResolutionExposeNoResponseContent() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("human-task-route.db"), clock);
             var engine = new PekkoExecutionEngine("human-task-route-test")) {
            Fixture fixture = request(store, clock);
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    new TenantApproverAuthenticator())) {
                var sweeps = new java.util.concurrent.atomic.AtomicInteger();
                server.installHumanTasks(fixture.service(), ignored -> sweeps.incrementAndGet());
                server.start();

                HttpResponse<String> otherInbox = get(server, "other");
                assertEquals(200, otherInbox.statusCode(), otherInbox.body());
                assertTrue(otherInbox.body().contains("\"items\":[]"), otherInbox.body());

                HttpResponse<String> inbox = get(server, "tenant-a");
                assertEquals(200, inbox.statusCode(), inbox.body());
                assertTrue(inbox.body().contains(fixture.taskId().toString()), inbox.body());
                assertTrue(inbox.body().contains("Approve release"), inbox.body());
                assertFalse(inbox.body().contains("not copied"), inbox.body());
                assertTrue(get(server, "tenant-a", "?status=RESOLVED&includeTerminal=true")
                        .body().contains("\"items\":[]"));
                assertEquals(400, get(server, "tenant-a", "?status=not-a-status").statusCode());

                assertEquals(400, post(server, fixture, "tenant-a", null, CONTENT_TYPE).statusCode(),
                        "the generation fence is mandatory");
                assertEquals(404, post(server, fixture, "other", "1", CONTENT_TYPE).statusCode(),
                        "cross-tenant identifiers reveal no task existence");
                assertEquals(400, post(server, fixture, "tenant-a", "1", "application/json").statusCode(),
                        "the response media type is matched exactly");

                HttpResponse<String> resolved = post(server, fixture, "tenant-a", "1", CONTENT_TYPE);
                assertEquals(200, resolved.statusCode(), resolved.body());
                assertTrue(resolved.body().contains("\"outcome\":\"resolved\""), resolved.body());
                assertTrue(resolved.body().contains("\"generation\":2"), resolved.body());
                assertFalse(resolved.body().contains("approved"), resolved.body());
                assertFalse(resolved.body().contains("response"), resolved.body());
                assertEquals(1, sweeps.get());
                assertTrue(get(server, "tenant-a", "?status=resolved&includeTerminal=true")
                        .body().contains(fixture.taskId().toString()));

                HttpResponse<String> retry = post(server, fixture, "tenant-a", "1", CONTENT_TYPE);
                assertEquals(200, retry.statusCode(), retry.body());
                assertTrue(retry.body().contains("already_applied"), retry.body());
                assertEquals(2, sweeps.get(), "an idempotent retry may wake the same durable continuation");
            }
        }
    }

    private static Fixture request(ExecutionStore store, Clock clock) {
        var key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "review", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        var traversal = new Traversal(traversalId, "review", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join().revision();
        var service = new HumanTaskService(store, clock);
        var requester = SecurityContext.of(new RequestContext("requester-request", "requester",
                PrincipalType.USER, "urn:ravenroot:test", key.tenantId(), Set.of(), Set.of()));
        var message = new NodeMessage(requester, key.processInstanceId(), traversalId, invocationId,
                attemptId, "review", Map.of("private", "not copied"), Map.of());
        var definition = new HumanTaskDefinition(new HumanTaskMetadata("Approve release", "Bounded facts only."),
                new HumanTaskResponseSchema(CONTENT_TYPE, "release.decision", "1", PayloadKind.MAP, 4096),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), Optional.empty(), Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"));
        HumanTaskResult result;
        try (var recorder = ExecutionRecorder.open(store, key, "route-fixture", Duration.ofSeconds(30),
                revision); var ignored = service.bindLive(key, recorder)) {
            result = service.suspend(message, definition);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        assertEquals(HumanTaskResult.Code.CREATED, result.code());
        return new Fixture(service, result.task().request().taskId());
    }

    private static HttpResponse<String> get(RavenrootServer server, String tenant) throws Exception {
        return get(server, tenant, "");
    }

    private static HttpResponse<String> get(RavenrootServer server, String tenant, String query)
            throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/human-tasks" + query))
                        .header("X-Test-Tenant", tenant).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(RavenrootServer server, Fixture fixture, String tenant,
                                              String generation, String contentType) throws Exception {
        String query = generation == null ? "" : "?generation=" + generation;
        String body = PayloadEnvelope.of("release.decision", "1",
                PayloadValue.map(Map.of("decision", PayloadValue.of("approved")))).toJson();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.port() + "/v1/human-tasks/" + fixture.taskId() + "/resolve" + query))
                        .header("X-Test-Tenant", tenant)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Fixture(HumanTaskService service, UUID taskId) { }

    private static final class TenantApproverAuthenticator implements RequestAuthenticator {
        @Override public AuthenticatedPrincipal authenticate(Headers headers) {
            return new AuthenticatedPrincipal("approver", AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", headers.getFirst("X-Test-Tenant"), Set.of(Role.APPROVER),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
