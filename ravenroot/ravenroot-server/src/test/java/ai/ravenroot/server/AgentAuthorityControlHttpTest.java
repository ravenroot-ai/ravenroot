package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.application.ExecutionControlAuditEvent;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetPolicy;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService;
import ai.ravenroot.core.security.nodepackage.AgentBudgetTelemetry;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.RequestAuthenticator;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthorityControlHttpTest {
    @TempDir
    Path ui;

    @Test
    void controlRouteRequiresPlatformAdminAndReturnsOnlyBoundedState() throws Exception {
        try (var engine = new PekkoExecutionEngine("agent-authority-http");
             var store = new InMemoryExecutionStore()) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui, authenticator())) {
                var budgets = new AgentAuthorityBudgetService(store, Clock.systemUTC(), policy(),
                        AgentBudgetTelemetry.discarding());
                var controlAudit = new ArrayList<ExecutionControlAuditEvent>();
                server.installAgentAuthorityControl(budgets, controlAudit::add);
                server.start();

                assertEquals(401, post(server, "/v1/agent-authority/trip", null).statusCode());
                assertEquals(403, post(server, "/v1/agent-authority/trip", "operator").statusCode());
                assertEquals(403, post(server, "/v1/agent-authority/trip", "admin-no-scope").statusCode());

                var tripped = post(server, "/v1/agent-authority/trip", "admin");
                assertEquals(200, tripped.statusCode(), tripped.body());
                assertEquals("{\"state\":\"KILLED\",\"epoch\":1}", tripped.body());
                assertEquals(tripped.body(), post(server, "/v1/agent-authority/trip", "admin").body(),
                        "same-direction control is idempotent");
                var reset = post(server, "/v1/agent-authority/reset", "admin");
                assertEquals(200, reset.statusCode(), reset.body());
                assertEquals("{\"state\":\"ACTIVE\",\"epoch\":2}", reset.body());
                assertFalse(reset.body().contains("tenant"));
                assertFalse(reset.body().contains("principal"));
                assertEquals(List.of("agent-authority-trip", "agent-authority-trip",
                                "agent-authority-trip", "agent-authority-trip",
                                "agent-authority-reset", "agent-authority-reset"),
                        controlAudit.stream().map(ExecutionControlAuditEvent::action).toList());
                assertEquals(List.of(ExecutionControlAuditEvent.Disposition.ATTEMPT,
                                ExecutionControlAuditEvent.Disposition.SUCCEEDED,
                                ExecutionControlAuditEvent.Disposition.ATTEMPT,
                                ExecutionControlAuditEvent.Disposition.SUCCEEDED,
                                ExecutionControlAuditEvent.Disposition.ATTEMPT,
                                ExecutionControlAuditEvent.Disposition.SUCCEEDED),
                        controlAudit.stream().map(ExecutionControlAuditEvent::disposition).toList());
                assertTrue(controlAudit.stream().allMatch(event -> event.resourceType().equals("agent-authority")
                                && event.resourceId().equals("global")
                                && event.requestId() != null && !event.requestId().isBlank()
                                && Set.of("", "ACTIVE", "KILLED").contains(event.detail())),
                        "control audit must carry only correlation and bounded state, never budget or scope data");
            }
        }
    }

    @Test
    void routeWithoutDurableControlIsFailClosed() throws Exception {
        try (var engine = new PekkoExecutionEngine("agent-authority-http-absent")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui, authenticator())) {
                server.start();
                assertEquals(404, post(server, "/v1/agent-authority/trip", "admin").statusCode());
            }
        }
    }

    private static RequestAuthenticator authenticator() {
        return headers -> {
            String value = headers.getFirst("Authorization");
            if (value == null) throw new AuthenticationException("missing");
            String token = value.substring("Bearer ".length());
            return switch (token) {
                case "admin" -> principal(Role.PLATFORM_ADMIN,
                        Set.of(AuthorizationAction.AGENT_AUTHORITY_CONTROL.requiredScope()));
                case "admin-no-scope" -> principal(Role.PLATFORM_ADMIN, Set.of());
                default -> principal(Role.OPERATOR,
                        Set.of(AuthorizationAction.AGENT_AUTHORITY_CONTROL.requiredScope()));
            };
        };
    }

    private static AuthenticatedPrincipal principal(Role role, Set<String> scopes) {
        return new AuthenticatedPrincipal("operator", AuthenticatedPrincipal.Type.USER, "issuer",
                "audit-tenant", Set.of(role), scopes);
    }

    private static AgentAuthorityBudgetPolicy policy() {
        return new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), new AgentBudgetVector(10, 1_000, 1_000, 60_000, 10_000,
                10, 2, 2, 2), 100, 20, 1, 1, Set.of(), Set.of());
    }

    private static HttpResponse<String> post(RavenrootServer server, String path, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
