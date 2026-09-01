package ai.ravenroot.server;

import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ArtifactReservation;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramBuildNodePlan;
import ai.ravenroot.api.programming.ProgramBuildNodeSnapshot;
import ai.ravenroot.api.programming.ProgramBuildPhase;
import ai.ravenroot.api.programming.ProgramBuildSnapshot;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteArtifactRegistry;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic live/reconnect/restart evidence for durable build observation. */
class DurableProgramBuildObservationHttpIntegrationTest {
    private static final String BODY = """
            {"programs":[{"nodeId":"program-1","language":"javascript",
            "source":"return payload","testPayload":"test payload"}]}
            """;

    @TempDir Path directory;

    @Test
    void everyPhaseIsPersistedBeforeWorkAndPollingReconnectsWithoutDuplicateBuilds() throws Exception {
        var gates = new PhaseGates(EnumSet.of(ProgramBuildPhase.VALIDATE, ProgramBuildPhase.SMOKE_TEST,
                ProgramBuildPhase.APPROVE_BY_POLICY, ProgramBuildPhase.ACTIVATE));
        try (var fixture = new Fixture(directory.resolve("phases"), new RecordingRuntime(false), gates)) {
            HttpResponse<String> started = fixture.start(BODY, "tenant-a");
            assertEquals(202, started.statusCode(), started.body());
            assertPhase(started.body(), ProgramBuildPhase.REGISTER, 1);
            String buildId = text(started.body(), "buildId");

            gates.await(ProgramBuildPhase.VALIDATE);
            HttpResponse<String> validating = fixture.statusWithNewClient(buildId, "tenant-a");
            assertNonterminalPhase(validating, ProgramBuildPhase.VALIDATE, 2);

            HttpResponse<String> duplicate = fixture.start(BODY, "tenant-a");
            assertEquals(202, duplicate.statusCode(), duplicate.body());
            assertEquals(buildId, text(duplicate.body(), "buildId"),
                    "a reconnect/retry must join the matching nonterminal build");
            HttpResponse<String> crossTenant = fixture.statusWithNewClient(buildId, "tenant-b");
            HttpResponse<String> unknown = fixture.statusWithNewClient("unknown-build", "tenant-a");
            assertEquals(404, crossTenant.statusCode(),
                    "another tenant receives the same nondisclosing absence as an unknown id");
            assertEquals(unknown.statusCode(), crossTenant.statusCode());
            assertEquals(text(unknown.body(), "code"), text(crossTenant.body(), "code"));
            assertEquals("UNKNOWN_RESOURCE", text(unknown.body(), "code"));

            gates.release(ProgramBuildPhase.VALIDATE);
            gates.await(ProgramBuildPhase.SMOKE_TEST);
            assertNonterminalPhase(fixture.statusWithNewClient(buildId, "tenant-a"),
                    ProgramBuildPhase.SMOKE_TEST, 3);

            gates.release(ProgramBuildPhase.SMOKE_TEST);
            gates.await(ProgramBuildPhase.APPROVE_BY_POLICY);
            HttpResponse<String> approving = fixture.statusWithNewClient(buildId, "tenant-a");
            assertNonterminalPhase(approving, ProgramBuildPhase.APPROVE_BY_POLICY, 4);
            assertTrue(approving.body().contains("\"smokeOutput\":{\"observed\":true}"), approving.body());

            gates.release(ProgramBuildPhase.APPROVE_BY_POLICY);
            gates.await(ProgramBuildPhase.ACTIVATE);
            assertNonterminalPhase(fixture.statusWithNewClient(buildId, "tenant-a"),
                    ProgramBuildPhase.ACTIVATE, 5);

            gates.release(ProgramBuildPhase.ACTIVATE);
            String ready = fixture.awaitTerminal(buildId, "tenant-a");
            assertPhase(ready, ProgramBuildPhase.READY, 6);
            assertTrue(ready.indexOf("\"terminal\":true") < ready.indexOf("\"programs\""), ready);
            assertTrue(ready.contains("\"ready\":true"), ready);

            assertEquals(200, fixture.retire(text(ready, "artifactId"), "tenant-a").statusCode());
            HttpResponse<String> retiredStart = fixture.start(BODY, "tenant-a");
            String retired = fixture.awaitTerminal(text(retiredStart.body(), "buildId"), "tenant-a");
            assertPhase(retired, ProgramBuildPhase.RETIRED, 2);
        }
    }

    @Test
    void explicitDualControlPauseRemainsNonterminalAndReturnsAccepted() throws Exception {
        try (var fixture = new Fixture(directory.resolve("approval-pause"),
                new RecordingRuntime(false), null, true)) {
            HttpResponse<String> started = fixture.start(BODY, "tenant-a");
            HttpResponse<String> waiting = fixture.awaitPhase(
                    text(started.body(), "buildId"), "tenant-a", ProgramBuildPhase.APPROVAL_REQUIRED);
            assertNonterminalPhase(waiting, ProgramBuildPhase.APPROVAL_REQUIRED, 5);
        }
    }

    @Test
    void incompletePhaseResumesAfterCloseReopenAndFailureEvidenceSupportsRetry() throws Exception {
        Path restartStore = directory.resolve("restart");
        String interruptedBuild;
        var gates = new PhaseGates(EnumSet.of(ProgramBuildPhase.VALIDATE));
        try (var fixture = new Fixture(restartStore, new RecordingRuntime(false), gates)) {
            interruptedBuild = text(fixture.start(BODY, "tenant-a").body(), "buildId");
            gates.await(ProgramBuildPhase.VALIDATE);
            assertNonterminalPhase(fixture.statusWithNewClient(interruptedBuild, "tenant-a"),
                    ProgramBuildPhase.VALIDATE, 2);
            // close interrupts the phase gate only after VALIDATE is committed; no later phase exists yet.
        }

        var resumedRuntime = new RecordingRuntime(false);
        try (var fixture = new Fixture(restartStore, resumedRuntime, null)) {
            String ready = fixture.awaitTerminal(interruptedBuild, "tenant-a");
            assertPhase(ready, ProgramBuildPhase.READY, 6);
            assertEquals(1, resumedRuntime.validations.get(), "restart re-enters persisted VALIDATE safely");
            assertEquals(1, resumedRuntime.smokes.get());
        }

        Path failureStore = directory.resolve("failure");
        String failedBuild;
        try (var fixture = new Fixture(failureStore, new RecordingRuntime(true), null)) {
            failedBuild = text(fixture.start(BODY, "tenant-a").body(), "buildId");
            String failed = fixture.awaitTerminal(failedBuild, "tenant-a");
            assertPhase(failed, ProgramBuildPhase.FAILED, 3);
            assertTrue(failed.contains("validation diagnostic retained after page closure"), failed);
        }
        try (var fixture = new Fixture(failureStore, new RecordingRuntime(false), null)) {
            HttpResponse<String> persisted = fixture.statusWithNewClient(failedBuild, "tenant-a");
            assertEquals(200, persisted.statusCode(), persisted.body());
            assertTrue(persisted.body().contains("validation diagnostic retained after page closure"),
                    persisted.body());
            String retryBuild = text(fixture.start(BODY, "tenant-a").body(), "buildId");
            assertNotEquals(failedBuild, retryBuild, "a terminal failed job yields an explicit retry job");
            assertPhase(fixture.awaitTerminal(retryBuild, "tenant-a"), ProgramBuildPhase.READY, 6);
        }
    }

    private static void assertPhase(String body, ProgramBuildPhase phase, long nodeRevision) {
        assertTrue(body.contains("\"phase\":\"" + phase + "\""), body);
        int programs = body.indexOf("\"programs\"");
        assertTrue(body.indexOf("\"revision\":" + nodeRevision, programs) > programs, body);
    }

    private static void assertNonterminalPhase(HttpResponse<String> response,
                                               ProgramBuildPhase phase, long nodeRevision) {
        assertEquals(202, response.statusCode(),
                "an incomplete " + phase + " snapshot must remain accepted, not terminal");
        assertPhase(response.body(), phase, nodeRevision);
    }

    private static String text(String body, String name) {
        int start = body.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return body.substring(start, body.indexOf('"', start));
    }

    private static final class RecordingRuntime implements ProgramRuntime {
        private final boolean rejectValidation;
        private final AtomicInteger validations = new AtomicInteger();
        private final AtomicInteger smokes = new AtomicInteger();

        private RecordingRuntime(boolean rejectValidation) { this.rejectValidation = rejectValidation; }
        @Override public String id() { return "durable-observation-runtime"; }
        @Override public CompletionStage<Void> validate(GeneratedArtifact artifact) {
            validations.incrementAndGet();
            return rejectValidation
                    ? CompletableFuture.failedFuture(new IllegalArgumentException(
                    "validation diagnostic retained after page closure"))
                    : CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
            smokes.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of("observed", true));
        }
        @Override public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used by this test"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PekkoExecutionEngine engine = new PekkoExecutionEngine("durable-build-observation-test");
        private final RavenrootServer server;
        private final String base;

        private Fixture(Path store, ProgramRuntime runtime, PhaseGates gates) {
            this(store, runtime, gates, false);
        }

        private Fixture(Path store, ProgramRuntime runtime, PhaseGates gates, boolean dualControl) {
            ArtifactRegistry registry = SqliteArtifactRegistry.openUnder(store, artifact -> { });
            if (gates != null) registry = new GatedRegistry(registry, gates);
            var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                    registry, runtime, ignored -> Optional.empty(),
                    ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                    OutboundHttpPolicy.disabled());
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), environment);
            application.configureArtifactDualControl(dualControl);
            server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    authenticator(), dualControl);
            server.start();
            base = "http://localhost:" + server.port() + "/v1/program-artifacts";
        }

        private HttpResponse<String> start(String body, String tenant) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/build"))
                            .header("Authorization", "Bearer " + tenant).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> statusWithNewClient(String buildId, String tenant) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/builds/" + buildId))
                            .header("Authorization", "Bearer " + tenant).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> retire(String artifactId, String tenant) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create(base + "/" + artifactId + "/retire?reason=status-contract"))
                            .header("Authorization", "Bearer " + tenant)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> awaitPhase(
                String buildId, String tenant, ProgramBuildPhase phase) throws Exception {
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                response = statusWithNewClient(buildId, tenant);
                if (response.body().contains("\"phase\":\"" + phase + "\"")) return response;
                Thread.sleep(10);
            }
            throw new AssertionError("build did not reach " + phase + ": "
                    + (response == null ? "none" : response.body()));
        }

        private String awaitTerminal(String buildId, String tenant) throws Exception {
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                response = statusWithNewClient(buildId, tenant);
                int terminal = response.body().indexOf("\"terminal\":true");
                if (terminal >= 0 && terminal < response.body().indexOf("\"programs\"")) {
                    assertEquals(200, response.statusCode(),
                            "a terminal build snapshot is an observed result, not accepted work");
                    return response.body();
                }
                Thread.sleep(10);
            }
            throw new AssertionError("build did not become terminal: " + (response == null ? "none" : response.body()));
        }

        @Override public void close() {
            server.close();
            engine.close();
        }
    }

    private static RequestAuthenticator authenticator() {
        return headers -> {
            String token = headers.getFirst("Authorization");
            String tenant = token == null ? "tenant-a" : token.substring("Bearer ".length());
            return new AuthenticatedPrincipal("author", AuthenticatedPrincipal.Type.USER, "issuer", tenant,
                    Set.of(Role.TENANT_ADMIN, Role.DEVELOPER, Role.OPERATOR),
                    Set.of("ravenroot.artifact.read", "ravenroot.artifact.manage",
                            "ravenroot.artifact.retire"));
        };
    }

    private static final class PhaseGates {
        private final EnumMap<ProgramBuildPhase, CountDownLatch> arrived = new EnumMap<>(ProgramBuildPhase.class);
        private final EnumMap<ProgramBuildPhase, CountDownLatch> released = new EnumMap<>(ProgramBuildPhase.class);

        private PhaseGates(Set<ProgramBuildPhase> phases) {
            phases.forEach(phase -> {
                arrived.put(phase, new CountDownLatch(1));
                released.put(phase, new CountDownLatch(1));
            });
        }
        private void persisted(ProgramBuildPhase phase) {
            CountDownLatch seen = arrived.get(phase);
            if (seen == null) return;
            seen.countDown();
            try { released.get(phase).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        private void await(ProgramBuildPhase phase) throws InterruptedException {
            assertTrue(arrived.get(phase).await(5, TimeUnit.SECONDS), "phase was not durably reached: " + phase);
        }
        private void release(ProgramBuildPhase phase) { released.get(phase).countDown(); }
    }

    private static final class GatedRegistry implements ArtifactRegistry, AutoCloseable {
        private final ArtifactRegistry delegate;
        private final PhaseGates gates;
        private GatedRegistry(ArtifactRegistry delegate, PhaseGates gates) { this.delegate = delegate; this.gates = gates; }
        @Override public GeneratedArtifact create(String language, String source, Map<String, String> metadata) {
            return delegate.create(language, source, metadata);
        }
        @Override public Optional<GeneratedArtifact> find(String id) { return delegate.find(id); }
        @Override public List<GeneratedArtifact> list() { return delegate.list(); }
        @Override public Optional<GeneratedArtifact> findByTenantAndDigest(String tenant, String digest) {
            return delegate.findByTenantAndDigest(tenant, digest);
        }
        @Override public GeneratedArtifact recordEvidence(String id, long revision, Map<String, String> evidence) {
            return delegate.recordEvidence(id, revision, evidence);
        }
        @Override public ProgramBuildSnapshot startOrFindBuild(String tenant, String digest, boolean dual,
                Map<String, String> metadata, List<ProgramBuildNodePlan> nodes) {
            return delegate.startOrFindBuild(tenant, digest, dual, metadata, nodes);
        }
        @Override public Optional<ProgramBuildSnapshot> findBuild(String tenant, String id) {
            return delegate.findBuild(tenant, id);
        }
        @Override public List<ProgramBuildSnapshot> listIncompleteBuilds() { return delegate.listIncompleteBuilds(); }
        @Override public ProgramBuildNodeSnapshot recordBuildNode(String tenant, String build, String node,
                long revision, String artifact, ProgramBuildPhase phase, boolean terminal, boolean ready,
                boolean reused, String diagnostic, String output) {
            ProgramBuildNodeSnapshot changed = delegate.recordBuildNode(tenant, build, node, revision, artifact,
                    phase, terminal, ready, reused, diagnostic, output);
            gates.persisted(phase);
            return changed;
        }
        @Override public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target) {
            return delegate.transition(id, expected, target);
        }
        @Override public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target,
                Map<String, String> evidence) { return delegate.transition(id, expected, target, evidence); }
        @Override public ProgramAdmission admitForExecution(String tenant, String artifact) {
            return delegate.admitForExecution(tenant, artifact);
        }
        @Override public ArtifactReservation reserve(String id, ArtifactState expected, ArtifactState target) {
            return delegate.reserve(id, expected, target);
        }
        @Override public GeneratedArtifact complete(ArtifactReservation reservation, Map<String, String> evidence) {
            return delegate.complete(reservation, evidence);
        }
        @Override public void cancel(ArtifactReservation reservation) { delegate.cancel(reservation); }
        @Override public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
