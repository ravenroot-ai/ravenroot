package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseLeaseRevalidationIntegrationTest {
    private static final Duration REVALIDATION_INTERVAL = Duration.ofSeconds(1);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Listeners a server holds for its whole lifetime, independent of any request: the structured
     * execution audit log and the per-tenant active-execution accounting.
     */
    // AuditTrailExecutionSink (or its narrower-constructor no-op default) is a third
    // lifetime subscription, alongside the audit logger and the per-tenant active-execution accounting.
    private static final int SERVER_LIFETIME_SUBSCRIPTIONS = 3;

    @Test
    void revalidatesAnIdleStreamWithoutEventsAndClosesItForEverySecurityIdentityDrift() throws Exception {
        Instant expiry = Instant.now().plusSeconds(30);
        AuthenticatedPrincipal initial = principal("alice", AuthenticatedPrincipal.Type.USER, "issuer-a", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.observe"), expiry);
        List<AuthenticatedPrincipal> changedIdentities = List.of(
                principal("mallory", initial.type(), initial.issuer(), initial.tenantId(), initial.roles(), initial.scopes(), expiry),
                principal(initial.subject(), initial.type(), initial.issuer(), "tenant-b", initial.roles(), initial.scopes(), expiry),
                principal(initial.subject(), initial.type(), initial.issuer(), initial.tenantId(), Set.of(Role.OPERATOR), initial.scopes(), expiry),
                principal(initial.subject(), initial.type(), initial.issuer(), initial.tenantId(), initial.roles(), Set.of("ravenroot.read"), expiry),
                principal(initial.subject(), initial.type(), "issuer-b", initial.tenantId(), initial.roles(), initial.scopes(), expiry),
                principal(initial.subject(), AuthenticatedPrincipal.Type.WORKLOAD, initial.issuer(), initial.tenantId(), initial.roles(), initial.scopes(), expiry));

        for (AuthenticatedPrincipal changed : changedIdentities) {
            assertIdleLeaseEnds(initial, changed, allowAllAuthorization());
        }
    }

    @Test
    void closesAnIdleStreamAndRemovesItsSubscriptionWhenTheCurrentPdpRevokesAccess() throws Exception {
        Instant expiry = Instant.now().plusSeconds(30);
        AuthenticatedPrincipal stable = principal("alice", AuthenticatedPrincipal.Type.USER, "issuer-a", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.observe"), expiry);
        var executionReadChecks = new AtomicInteger();
        AuthorizationService currentPdp = (context, action, resource) -> {
            if (action == AuthorizationAction.EXECUTION_READ && executionReadChecks.incrementAndGet() >= 3) {
                return new AuthorizationDecision(false, "policy revoked the SSE lease");
            }
            return new AuthorizationDecision(true, "policy allowed");
        };

        assertIdleLeaseEnds(stable, stable, currentPdp);
        assertEquals(3, executionReadChecks.get(),
                "the production PDP must be consulted for subscribe, replay and timed lease revalidation");
    }

    private static void assertIdleLeaseEnds(AuthenticatedPrincipal initial, AuthenticatedPrincipal revalidated,
                                            AuthorizationService authorization) throws Exception {
        var authenticator = new RecordingAuthenticator(initial, revalidated);
        try (var engine = new PekkoExecutionEngine("sse-lease-revalidation-test")) {
            var application = new CountingApplication(new DefaultRavenrootApplication(engine, new ExecutionMonitor()));
            try (var server = new RavenrootServer(application,
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false, authenticator,
                     security(), Clock.systemUTC(), authorization);
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/v1/events"))
                            .header("Authorization", "Bearer test-credential").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());

            var endOfStream = readers.submit(() -> consume(response.body()));
            assertTrue(authenticator.revalidated.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "an idle stream must revalidate on its timer instead of waiting for an execution event");
            // The client observing end-of-stream and the server finishing its OWN teardown are two
            // different events on two different threads, both downstream of the same thrown
            // AuthenticationException in `streamExecutionEvents`: the server closes the response
            // OutputStream (what the client sees as EOF) in the inner try-with-resources, THEN — in the
            // outer `finally`, strictly after — closes the subscription and updates these counters.
            // Nothing joins the client's read thread to the server's handler thread between those two
            // events, so `endOfStream.get()` returning is not evidence the counters below are already
            // correct: it only proves the socket closed. Constructed proof this gap is real, not
            // theorized: injecting a delay into the subscription's own close() (server side only, after
            // the OutputStream — and therefore the client-visible EOF — already closed) made the
            // counter assertions below fail deterministically, every run, with `endOfStream.get()`
            // already having returned. Waiting for the server's own "subscription closed" signal removes
            // the false inference instead of budgeting around it — plumbing (which signal the test
            // blocks on), not evidence (the counts themselves are still the real teardown's own output).
            endOfStream.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(application.subscriptionClosed.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the server must finish tearing down its own SSE subscription, not merely close the "
                            + "client-visible stream");
            assertEquals(1, authenticator.authenticateCalls.get());
            assertEquals(1, authenticator.revalidateCalls.get());
            // A server holds exactly three lifetime listeners — the audit logger, the per-tenant
            // active-execution accounting that /v1/executions admission is decided from, and the
            // decisional-event audit sink or its no-op default — and all three are
            // created once per server rather than once per request. The property this pins is unchanged:
            // one live SSE request adds exactly one subscription and removes exactly that one.
            assertEquals(SERVER_LIFETIME_SUBSCRIPTIONS + 1, application.createdSubscriptions(),
                    "the server must subscribe exactly once for the live SSE request, in addition to its "
                            + "lifetime listeners");
            assertEquals(1, application.closedSubscriptions(),
                    "the ended SSE lease must close its own listener without closing the server's lifetime listeners");
            assertEquals(SERVER_LIFETIME_SUBSCRIPTIONS, application.activeSubscriptions(),
                    "the revoked SSE listener must be removed while the server's lifetime listeners remain");
            }
        }
    }

    /**
     * One SSE connection produces one correlation id (SEC-07).
     *
     * <p>The stream is authorized three times — subscribe, replay and one timed revalidation — and all
     * three must appear in the audit under the same {@code requestId}. Before SEC-07 the revalidation
     * built its context from the refreshed principal alone, which minted a fresh random id, so a
     * long-lived connection scattered its audit records across unrelated ids and nothing tied a later
     * revocation back to the lease that had been granted.</p>
     */
    @Test
    void oneSseConnectionAuditsUnderOneRequestId() throws Exception {
        Instant expiry = Instant.now().plusSeconds(30);
        AuthenticatedPrincipal stable = principal("alice", AuthenticatedPrincipal.Type.USER, "issuer-a", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.observe"), expiry);
        var observedRequestIds = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var executionReadChecks = new AtomicInteger();
        AuthorizationService capturing = (context, action, resource) -> {
            if (action == AuthorizationAction.EXECUTION_READ) {
                observedRequestIds.add(context.requestId());
                if (executionReadChecks.incrementAndGet() >= 3) {
                    return new AuthorizationDecision(false, "policy revoked the SSE lease");
                }
            }
            return new AuthorizationDecision(true, "policy allowed");
        };

        assertIdleLeaseEnds(stable, stable, capturing);

        assertEquals(3, observedRequestIds.size(),
                "subscribe, replay and the timed revalidation must each be authorized");
        assertEquals(1, Set.copyOf(observedRequestIds).size(),
                "every authorization decision for one SSE connection must share one correlation id, "
                        + "including the one taken after the credential was revalidated: " + observedRequestIds);
        assertTrue(observedRequestIds.getFirst() != null && !observedRequestIds.getFirst().isBlank());
    }

    private static long consume(InputStream input) throws Exception {
        try (input) {
            return input.readAllBytes().length;
        }
    }

    private static HttpSecurityConfiguration security() {
        return new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:65535")),
                new SecurityHeadersPolicy(false), REVALIDATION_INTERVAL);
    }

    private static AuthorizationService allowAllAuthorization() {
        return (context, action, resource) -> new AuthorizationDecision(true, "test policy allowed");
    }

    private static AuthenticatedPrincipal principal(String subject, AuthenticatedPrincipal.Type type, String issuer,
                                                    String tenant, Set<Role> roles, Set<String> scopes, Instant expiry) {
        return new AuthenticatedPrincipal(subject, type, issuer, tenant, roles, scopes, expiry);
    }

    private static final class RecordingAuthenticator implements RequestAuthenticator {
        private final AuthenticatedPrincipal initial;
        private final AuthenticatedPrincipal revalidatedPrincipal;
        private final CountDownLatch revalidated = new CountDownLatch(1);
        private final AtomicInteger authenticateCalls = new AtomicInteger();
        private final AtomicInteger revalidateCalls = new AtomicInteger();

        private RecordingAuthenticator(AuthenticatedPrincipal initial, AuthenticatedPrincipal revalidatedPrincipal) {
            this.initial = initial;
            this.revalidatedPrincipal = revalidatedPrincipal;
        }

        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) {
            authenticateCalls.incrementAndGet();
            return initial;
        }

        @Override
        public AuthenticatedPrincipal revalidate(Headers headers) {
            revalidateCalls.incrementAndGet();
            revalidated.countDown();
            return revalidatedPrincipal;
        }
    }

    /** Delegates every use case to the production application while recording live listener lifecycle. */
    private static final class CountingApplication implements RavenrootApplication {
        private final RavenrootApplication delegate;
        private final AtomicInteger activeSubscriptions = new AtomicInteger();
        private final AtomicInteger createdSubscriptions = new AtomicInteger();
        private final AtomicInteger closedSubscriptions = new AtomicInteger();
        // Counts down once a subscription this application created has actually finished closing —
        // counters updated, delegate released — as opposed to once the CLIENT has merely observed its
        // HTTP stream end. The two are not the same event (see the wait in `assertIdleLeaseEnds`): only
        // one non-lifetime subscription closes during the window that method awaits this for, so one
        // shared latch identifies it without needing to be told which subscription to watch for.
        private final CountDownLatch subscriptionClosed = new CountDownLatch(1);

        private CountingApplication(RavenrootApplication delegate) {
            this.delegate = delegate;
        }

        int activeSubscriptions() {
            return activeSubscriptions.get();
        }

        int createdSubscriptions() {
            return createdSubscriptions.get();
        }

        int closedSubscriptions() {
            return closedSubscriptions.get();
        }

        @Override public ApplicationStatus status() { return delegate.status(); }
        @Override public RuntimeSnapshot runtimeSnapshot() { return delegate.runtimeSnapshot(); }
        @Override public List<NodeTypeDescriptor> nodeTypes() { return delegate.nodeTypes(); }
        @Override public List<GeneratedArtifact> programArtifacts() { return delegate.programArtifacts(); }
        @Override public GeneratedArtifact createProgramArtifact(String language, String source, Map<String, String> metadata) {
            return delegate.createProgramArtifact(language, source, metadata);
        }
        @Override public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            return delegate.validateProgramArtifact(id);
        }
        @Override public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            return delegate.testProgramArtifact(id, payload);
        }
        @Override public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            return delegate.approveProgramArtifact(id, trustedEvidence);
        }
        @Override public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            return delegate.activateProgramArtifact(id, trustedEvidence);
        }
        @Override public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            return delegate.retireProgramArtifact(id, trustedEvidence);
        }
        @Override public GraphSummary inspectGraphMl(InputStream graphMl) { return delegate.inspectGraphMl(graphMl); }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload) {
            return delegate.startGraphMl(security, executionId, graphMl, payload);
        }
        @Override public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return delegate.executionEventsAfter(sequence);
        }

        @Override public boolean durableEventJournalAvailable() {
            return delegate.durableEventJournalAvailable();
        }

        @Override
        public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(String tenantId,
                                                                                            long afterOffset,
                                                                                            int limit) {
            return delegate.durableEventsAfter(tenantId, afterOffset, limit);
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            AutoCloseable subscription = delegate.subscribeToExecutionEvents(listener);
            activeSubscriptions.incrementAndGet();
            createdSubscriptions.incrementAndGet();
            var removed = new AtomicBoolean();
            return () -> {
                if (removed.compareAndSet(false, true)) {
                    try {
                        subscription.close();
                    } finally {
                        activeSubscriptions.decrementAndGet();
                        closedSubscriptions.incrementAndGet();
                        // Last, so a waiter released by this latch always observes the counters above
                        // already updated — the ordering the test relies on instead of guessing at.
                        subscriptionClosed.countDown();
                    }
                }
            };
        }

        @Override public void close() { delegate.close(); }
    }
}
