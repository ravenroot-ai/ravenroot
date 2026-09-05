package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.ratelimit.ActiveExecutionRegistry;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission control for {@code POST /v1/executions}, measured over real HTTP against executions the
 * test decides when — or whether — to terminate.
 *
 * <h2>Why the application under the server is a stub here</h2>
 *
 * <p>The property under test is what happens while executions are <em>running</em>, and the two cases
 * that matter most are an execution that outlives many submissions and an execution that never
 * terminates at all. Neither is reachable through a real engine without a timing assumption. The stub
 * publishes exactly the events the engine publishes — {@code EXECUTION_STARTED} on submission, a
 * terminal event only when the test asks for one — so the server, the limiter and the accounting are
 * all the production code, and only the duration of the work is under test control.</p>
 */
class ActiveExecutionAdmissionHttpTest {
    private final AtomicLong nanos = new AtomicLong();

    /**
     * Cross-tenant fairness.
     *
     * <p>With a ceiling of 4 the derived reserve is 1 slot and one tenant may hold 3. The noisy tenant
     * must therefore stop at 3 and the quiet tenant's first submission must be accepted. Against a
     * global-only ceiling both assertions fail: the noisy tenant takes all 4 and the quiet tenant is
     * refused {@code ACTIVE_EXECUTION_CEILING_REACHED} for capacity it never used.</p>
     */
    @Test
    void aTenantHoldingExecutionsDoesNotStarveAnotherTenant() throws Exception {
        try (var fixture = fixture(limits(builder -> builder.activeExecutions(4)))) {
            var client = HttpClient.newHttpClient();

            int accepted = 0;
            var refusals = new ArrayList<HttpResponse<String>>();
            for (int index = 0; index < 10; index++) {
                var response = fixture.submit(client, "tenant-noisy");
                if (response.statusCode() == 202) {
                    accepted++;
                } else {
                    refusals.add(response);
                }
            }

            assertEquals(3, accepted, "one tenant took more than the ceiling minus its reserved headroom, "
                    + "so it can still decide another tenant's availability");
            assertEquals(3, fixture.registry().activeFor("tenant-noisy"));
            assertEquals(429, refusals.getFirst().statusCode());
            assertTrue(refusals.getFirst().body().contains(ActiveExecutionRegistry.TENANT_LIMIT_CODE),
                    refusals.getFirst().body());
            assertEquals("1", refusals.getFirst().headers().firstValue("Retry-After").orElseThrow());

            var quiet = fixture.submit(client, "tenant-quiet");

            assertEquals(202, quiet.statusCode(),
                    "a tenant that holds no executions was refused because another tenant holds some; "
                            + "that is the cross-tenant denial the per-tenant cap exists to remove");
        }
    }

    /** The global ceiling is still the backstop, with the code and the 429 contract it always had. */
    @Test
    void theGlobalCeilingStillRefusesWhenSeveralTenantsFillIt() throws Exception {
        try (var fixture = fixture(limits(builder -> builder.activeExecutions(4)))) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 3; index++) {
                assertEquals(202, fixture.submit(client, "tenant-a").statusCode());
            }
            assertEquals(202, fixture.submit(client, "tenant-b").statusCode());
            assertEquals(4, fixture.registry().total());

            var third = fixture.submit(client, "tenant-c");

            assertEquals(429, third.statusCode());
            assertTrue(third.body().contains(ActiveExecutionRegistry.GLOBAL_LIMIT_CODE), third.body());
            assertTrue(third.headers().firstValue("Retry-After").isPresent());
        }
    }

    /**
     * An execution with no terminal event must not close submission for the whole process.
     *
     * <p>This is the failure mode the shipped global counter had in its worst form:
     * {@code ExecutionMonitor.activeExecutions} only ever moves on a terminal event, so one execution
     * that never publishes one refused every tenant until the process restarted. Here the entries age
     * out, the capacity returns, and the reclamation is a record on the audit stream rather than a
     * number that quietly stopped matching reality.</p>
     */
    @Test
    void executionsThatNeverTerminateDoNotCloseSubmissionPermanently() throws Exception {
        var audit = new RecordingAudit();
        var limits = limits(builder -> builder.activeExecutions(4)
                .executionMaxAge(Duration.ofSeconds(60)));
        try (var fixture = fixture(limits, audit)) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 3; index++) {
                assertEquals(202, fixture.submit(client, "tenant-a").statusCode());
            }
            assertEquals(202, fixture.submit(client, "tenant-b").statusCode());
            // Nothing ever terminates: every tenant is now refused, including tenants that hold nothing.
            assertEquals(429, fixture.submit(client, "tenant-c").statusCode());
            assertEquals(429, fixture.submit(client, "tenant-d").statusCode());

            nanos.addAndGet(Duration.ofSeconds(61).toNanos());

            assertEquals(202, fixture.submit(client, "tenant-c").statusCode(),
                    "stuck executions closed submission permanently for a tenant that never had any");
            assertEquals(4, fixture.registry().expiredEntries(),
                    "the four stuck entries should have been reclaimed exactly once each");
            assertTrue(audit.codes().contains(ActiveExecutionRegistry.EXPIRED_CODE),
                    "the reclamation was silent; a stuck counter must be observable, not merely survivable");
            var reclaimed = audit.events().stream()
                    .filter(event -> event.code().equals(ActiveExecutionRegistry.EXPIRED_CODE))
                    .findFirst().orElseThrow();
            assertEquals(0, reclaimed.status(), "a reclamation refused nothing and must not claim a status");
            assertTrue(List.of("tenant-a", "tenant-b").contains(reclaimed.tenantId()), reclaimed.tenantId());
        }
    }

    /** A terminated execution gives its slot back to its own tenant, on every terminal event type. */
    @Test
    void aTerminatedExecutionReturnsItsSlotToItsTenant() throws Exception {
        try (var fixture = fixture(limits(builder -> builder.activeExecutions(4)))) {
            var client = HttpClient.newHttpClient();

            var held = new ArrayList<UUID>();
            for (int index = 0; index < 3; index++) {
                held.add(fixture.executionId(fixture.submit(client, "tenant-a")));
            }
            assertEquals(429, fixture.submit(client, "tenant-a").statusCode());

            fixture.application().terminate(held.get(0), ExecutionEventType.EXECUTION_COMPLETED);
            assertEquals(2, fixture.registry().activeFor("tenant-a"));
            assertEquals(202, fixture.submit(client, "tenant-a").statusCode());

            fixture.application().terminate(held.get(1), ExecutionEventType.EXECUTION_FAILED);
            assertEquals(2, fixture.registry().activeFor("tenant-a"));
            assertEquals(202, fixture.submit(client, "tenant-a").statusCode());
        }
    }

    /**
     * Regression probe: a cancelled execution must release its admission slot exactly like a
     * completed or a failed one. {@code ActiveExecutionRegistry} previously matched only
     * {@code EXECUTION_COMPLETED}/{@code EXECUTION_FAILED} and treated {@code EXECUTION_CANCELLED} as
     * an unknown, no-op event type — a cancelled execution's entry never left the registry, so
     * cancelling enough executions permanently exhausted a tenant's admission capacity.
     */
    @Test
    void aCancelledExecutionReturnsItsSlotToItsTenant() throws Exception {
        try (var fixture = fixture(limits(builder -> builder.activeExecutions(4)))) {
            var client = HttpClient.newHttpClient();

            var held = new ArrayList<UUID>();
            for (int index = 0; index < 3; index++) {
                held.add(fixture.executionId(fixture.submit(client, "tenant-a")));
            }
            assertEquals(429, fixture.submit(client, "tenant-a").statusCode());

            fixture.application().terminate(held.get(0), ExecutionEventType.EXECUTION_CANCELLED);

            assertEquals(2, fixture.registry().activeFor("tenant-a"),
                    "a cancelled execution left its admission slot held forever");
            assertEquals(202, fixture.submit(client, "tenant-a").statusCode());
        }
    }

    /**
     * Regression probe for a single tenant operating on its own.
     *
     * <p>A per-tenant cap chosen as a fraction of the ceiling would quietly cut the concurrency of
     * every single-tenant deployment, which is the common case. The cap is a reserve subtracted from
     * the ceiling instead, so a lone tenant keeps all but the reserve. The numbers asserted here are
     * the ones that ship.</p>
     */
    @Test
    void aLoneTenantStillReachesTheCeilingMinusTheReservedHeadroom() throws Exception {
        var defaults = RateLimitConfiguration.DEFAULTS;
        assertEquals(64, defaults.globalActiveExecutions());
        assertEquals(8, defaults.reservedExecutionHeadroom());
        assertEquals(56, defaults.tenantActiveExecutions());

        try (var fixture = fixture(limits(builder -> builder.activeExecutions(64)))) {
            var client = HttpClient.newHttpClient();

            int accepted = 0;
            for (int index = 0; index < 64; index++) {
                if (fixture.submit(client, "tenant-solo").statusCode() == 202) {
                    accepted++;
                }
            }

            assertEquals(56, accepted, "a single-tenant deployment lost concurrency it previously had");
        }
    }

    /**
     * A submission the application refuses must not consume admission capacity.
     *
     * <p>Accounting driven by {@code EXECUTION_STARTED} has this property by construction — a
     * submission that never starts an execution never produces an entry — but "by construction" is
     * exactly the kind of claim that stops being true after an edit, so it is pinned.</p>
     */
    @Test
    void aRefusedSubmissionConsumesNoAdmissionCapacity() throws Exception {
        try (var fixture = fixture(limits(builder -> builder.activeExecutions(4)))) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 50; index++) {
                assertEquals(409, fixture.submit(client, "tenant-a", HoldingApplication.REJECT).statusCode());
            }

            assertEquals(0, fixture.registry().activeFor("tenant-a"));
            assertEquals(0, fixture.registry().total());
            assertEquals(202, fixture.submit(client, "tenant-a").statusCode());
        }
    }

    /** The registry cannot grow without bound even when nothing it counts ever terminates. */
    @Test
    void theRegistryIsBoundedWhenExecutionsArriveWithoutPassingAdmission() {
        var audit = new RecordingAudit();
        var limits = limits(builder -> builder.activeExecutions(4));
        try (var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(), audit, nanos::get)) {
            var registry = limiter.activeExecutions();
            int ceiling = limits.maxTrackedExecutions();

            for (int index = 0; index < ceiling * 2; index++) {
                registry.observe(startedEvent(UUID.randomUUID(), "tenant-" + index, index + 1));
            }

            assertEquals(ceiling, registry.total(), "the active-execution registry grew past its ceiling");
            assertEquals(ceiling, registry.displacedEntries());
            assertTrue(audit.codes().contains(ActiveExecutionRegistry.DISPLACED_CODE),
                    "entries were displaced without saying so");
        }
    }

    private static ExecutionEvent startedEvent(UUID executionId, String tenantId, long sequence) {
        return new ExecutionEvent(sequence, Instant.EPOCH, tenantId, "request-" + sequence, "stub", "v1",
                executionId, ExecutionEventType.EXECUTION_STARTED, null, 0, false, "started");
    }

    private Fixture fixture(RateLimitConfiguration limits) {
        return fixture(limits, new RecordingAudit());
    }

    private Fixture fixture(RateLimitConfiguration limits, RateLimitAuditSink audit) {
        var application = new HoldingApplication();
        var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(), audit, nanos::get);
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var server = new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                tenantAuthenticator(), httpSecurity(), Clock.systemUTC(),
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter);
        server.start();
        return new Fixture(server, application, limiter);
    }

    private record Fixture(RavenrootServer server, HoldingApplication application, RateLimiter limiter)
            implements AutoCloseable {
        ActiveExecutionRegistry registry() {
            return limiter.activeExecutions();
        }

        HttpResponse<String> submit(HttpClient client, String tenant) throws Exception {
            return submit(client, tenant, "<graphml/>");
        }

        HttpResponse<String> submit(HttpClient client, String tenant, String body) throws Exception {
            return client.send(HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + server.port() + "/v1/executions"))
                            .header("Authorization", "Bearer " + tenant + ":alice")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        UUID executionId(HttpResponse<String> accepted) {
            assertEquals(202, accepted.statusCode(), accepted.body());
            String marker = "\"executionId\":\"";
            int start = accepted.body().indexOf(marker) + marker.length();
            return UUID.fromString(accepted.body().substring(start, accepted.body().indexOf('"', start)));
        }

        @Override
        public void close() {
            server.close();
        }
    }

    /**
     * An application whose executions run until the test says otherwise.
     *
     * <p>It publishes the same {@code EXECUTION_STARTED} the engine publishes, with the tenant taken
     * from the security context the reference monitor derived, and it publishes a terminal event only
     * on request.</p>
     */
    private static final class HoldingApplication implements RavenrootApplication {
        /** Request body that makes a submission fail after admission but before any execution starts. */
        static final String REJECT = "reject-this-submission";

        private final List<Consumer<ExecutionEvent>> listeners = new CopyOnWriteArrayList<>();
        private final List<ExecutionEvent> history = new CopyOnWriteArrayList<>();
        private final Map<UUID, String> tenants = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger();

        void terminate(UUID executionId, ExecutionEventType type) {
            String tenant = tenants.remove(executionId);
            if (tenant == null) {
                throw new IllegalStateException("no such running execution: " + executionId);
            }
            running.decrementAndGet();
            publish(new ExecutionEvent(sequence.incrementAndGet(), Instant.now(), tenant,
                    UUID.randomUUID().toString(), "stub", "v1", executionId, type, null, 0, false, "done"));
        }

        @Override
        public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId,
                                                InputStream graphMl, Object payload) {
            byte[] body;
            try {
                body = graphMl.readAllBytes();
            } catch (java.io.IOException unreadable) {
                throw new IllegalStateException("unreadable submission", unreadable);
            }
            if (REJECT.equals(new String(body, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalStateException("submission refused before any execution started");
            }
            tenants.put(executionId, security.tenantId());
            running.incrementAndGet();
            publish(new ExecutionEvent(sequence.incrementAndGet(), Instant.now(), security.tenantId(),
                    security.requestId(), "stub", "v1", executionId,
                    ExecutionEventType.EXECUTION_STARTED, null, 0, false, "execution accepted"));
            return new ExecutionSubmission(executionId, executionId, "v1");
        }

        @Override
        public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId,
                                                InputStream graphMl, Object payload,
                                                ExecutionPolicy policy) {
            assertEquals(ExecutionPolicy.TEST_PASSTHROUGH, policy,
                    "the public inline endpoint must select the non-operational policy");
            return startGraphMl(security, executionId, graphMl, payload);
        }

        private void publish(ExecutionEvent event) {
            history.add(event);
            listeners.forEach(listener -> listener.accept(event));
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public List<ExecutionEvent> executionEventsAfter(long sequenceNumber) {
            return history.stream().filter(event -> event.sequence() > sequenceNumber).toList();
        }

        @Override
        public boolean durableEventJournalAvailable() {
            // This fixture is about admission accounting, not durable replay; no store is composed,
            // matching the pre-API-03 embedder shape this method exists to let a caller detect.
            return false;
        }

        @Override
        public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(String tenantId,
                                                                                            long afterOffset,
                                                                                            int limit) {
            throw new IllegalStateException("no durable journal configured");
        }

        @Override
        public RuntimeSnapshot runtimeSnapshot() {
            return new RuntimeSnapshot(running.get(), Map.of());
        }

        @Override
        public ApplicationStatus status() {
            return new ApplicationStatus("RUNNING", "stub", Set.of());
        }

        @Override
        public List<NodeTypeDescriptor> nodeTypes() {
            return List.of();
        }

        @Override
        public List<GeneratedArtifact> programArtifacts() {
            return List.of();
        }

        @Override
        public GeneratedArtifact createProgramArtifact(String language, String source,
                                                       Map<String, String> metadata) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public GraphSummary inspectGraphMl(InputStream graphMl) {
            throw new UnsupportedOperationException("not exercised by admission tests");
        }

        @Override
        public void close() {
            listeners.clear();
            history.clear();
            tenants.clear();
        }
    }

    private static RequestAuthenticator tenantAuthenticator() {
        return headers -> {
            String value = headers.getFirst("Authorization");
            String token = value == null ? "tenant-a:alice" : value.replaceFirst("^Bearer ", "");
            String[] parts = token.split(":", 2);
            String tenant = parts[0].isBlank() ? "tenant-a" : parts[0];
            String subject = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "alice";
            return new AuthenticatedPrincipal(subject, AuthenticatedPrincipal.Type.USER,
                    "https://issuer.example", tenant, Set.of(Role.PLATFORM_ADMIN),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    Instant.now().plus(Duration.ofHours(1)));
        };
    }

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }

    private static final class RecordingAudit implements RateLimitAuditSink {
        private final List<RateLimitAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(RateLimitAuditEvent event) {
            events.add(event);
        }

        List<RateLimitAuditEvent> events() {
            return events;
        }

        List<String> codes() {
            return events.stream().map(RateLimitAuditEvent::code).toList();
        }
    }

    private static RateLimitConfiguration limits(java.util.function.UnaryOperator<Limits> customise) {
        return customise.apply(new Limits()).build();
    }

    /** Every budget except the one under test is raised out of the way. */
    private static final class Limits {
        private int globalActiveExecutions = RateLimitConfiguration.DEFAULTS.globalActiveExecutions();
        private Duration executionMaxAge = RateLimitConfiguration.DEFAULTS.executionMaxAge();

        Limits activeExecutions(int global) {
            this.globalActiveExecutions = global;
            return this;
        }

        Limits executionMaxAge(Duration maxAge) {
            this.executionMaxAge = maxAge;
            return this;
        }

        RateLimitConfiguration build() {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return new RateLimitConfiguration(
                    100_000, 100_000,
                    100_000, 100_000,
                    100_000, 100_000,
                    100_000, 100_000,
                    defaults.tenantConcurrentSubmissions(), globalActiveExecutions,
                    defaults.tenantConcurrentStreams(), defaults.principalConcurrentStreams(),
                    defaults.streamQueueCapacity(),
                    defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                    defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                    defaults.maxTrackedClients(), defaults.maxTrackedTenants(),
                    defaults.maxTrackedPrincipals(), defaults.idleEntryTtl(), executionMaxAge);
        }
    }
}
