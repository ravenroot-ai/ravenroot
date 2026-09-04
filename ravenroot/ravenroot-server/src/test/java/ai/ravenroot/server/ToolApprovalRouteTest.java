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
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.approval.ToolApprovalResult;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalRouteTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] ARGUMENTS = "{\"secret\":\"never-return\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECKPOINT = "private-checkpoint-never-return"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void authenticatedDecisionIsTenantScopedAndNeverReturnsStoredContent() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new InMemoryExecutionStore(clock);
             var engine = new PekkoExecutionEngine("tool-approval-route-test")) {
            Fixture fixture = request(store, clock);
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    new TenantApproverAuthenticator())) {
                server.installToolApprovals(fixture.service());
                server.start();

                HttpResponse<String> otherTenant = post(server, fixture, "other", "approve");
                HttpResponse<String> unknown = post(server,
                        new Fixture(fixture.service(), UUID.randomUUID(), UUID.randomUUID()),
                        "other", "approve");
                assertEquals(404, otherTenant.statusCode(), otherTenant.body());
                assertEquals(404, unknown.statusCode(), unknown.body());
                assertTrue(otherTenant.body().contains("UNKNOWN_RESOURCE"), otherTenant.body());
                assertTrue(unknown.body().contains("UNKNOWN_RESOURCE"), unknown.body());

                HttpResponse<String> approved = post(server, fixture, "tenant-a", "approve");
                assertEquals(200, approved.statusCode(), approved.body());
                assertTrue(approved.body().contains("\"outcome\":\"approved\""), approved.body());
                assertTrue(approved.body().contains(fixture.approvalId().toString()), approved.body());
                assertFalse(approved.body().contains("never-return"), approved.body());
                assertFalse(approved.body().contains("canonicalArguments"), approved.body());
                assertFalse(approved.body().contains("checkpoint"), approved.body());
            }
        }
    }

    private static Fixture request(InMemoryExecutionStore store, Clock clock) {
        var key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "agent", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join().revision();
        var service = new ToolApprovalService(store, clock);
        var requester = SecurityContext.of(new RequestContext("requester-request", "requester",
                PrincipalType.USER, "urn:ravenroot:test", key.tenantId(), Set.of(Role.OPERATOR), Set.of()));
        var message = new NodeMessage(requester, key.processInstanceId(), traversalId, invocationId,
                attemptId, "agent", Map.of(), Map.of());
        try (var recorder = ExecutionRecorder.open(store, key, "route-fixture", Duration.ofSeconds(30),
                revision); var ignored = service.bindLive(key, recorder)) {
            assertEquals(ToolApprovalResult.Code.CREATED,
                    service.suspend(message, approvalId, UUID.randomUUID(), "filesystem.read",
                            ARGUMENTS, ToolApprovalRegistration.digest(ARGUMENTS),
                            new ToolApprovalSettings("policy-v1", Duration.ofMinutes(5),
                                    HandlerAuthorization.ofRoles(Role.APPROVER.name()), false),
                            1, CHECKPOINT).code());
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        return new Fixture(service, key.processInstanceId(), approvalId);
    }

    private static HttpResponse<String> post(RavenrootServer server, Fixture fixture, String tenant,
                                             String decision) throws Exception {
        String path = "/v1/executions/" + fixture.processId() + "/tool-approvals/"
                + fixture.approvalId() + "/" + decision;
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("X-Test-Tenant", tenant)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Fixture(ToolApprovalService service, UUID processId, UUID approvalId) {
    }

    private static final class TenantApproverAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) {
            String tenant = headers.getFirst("X-Test-Tenant");
            return new AuthenticatedPrincipal("approver", AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", tenant, Set.of(Role.APPROVER),
                    Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
