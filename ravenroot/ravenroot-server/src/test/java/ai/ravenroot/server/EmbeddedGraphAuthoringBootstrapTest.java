package ai.ravenroot.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.authoring.EmptyPublishedGraphArtifactCatalogFixture;
import ai.ravenroot.server.authoring.GraphAuthoringConfiguration;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class EmbeddedGraphAuthoringBootstrapTest {
    private static final String GRAPH =
            """
<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="embedded-authoring" edgedefault="directed">
    <node id="error"><data key="kind">ERROR</data></node>
    <node id="start"><data key="kind">START</data></node>
    <node id="end"><data key="kind">END</data></node>
    <edge id="e1" source="start" target="end"><data key="outcome">continue</data></edge>
  </graph>
</graphml>
""";

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicEmbeddedBootstrapServesGitAuthoringAndArtifactRoutesFromALocalGitRepository() throws Exception {
        var repositoryDirectory = temporaryDirectory.resolve("authoring-repository");
        initGit(repositoryDirectory);
        var configured = EmbeddedGraphAuthoringConfiguration.forTest(configuration(),
                ignored -> Optional.of(new SecretValue("fixture-token".toCharArray())),
                (configuration, credentials, publications, admission) ->
                        new LocalGitRepository(repositoryDirectory, configuration, admission),
                EmptyPublishedGraphArtifactCatalogFixture::create);
        try (var engine = new PekkoExecutionEngine("embedded-authoring-" + UUID.randomUUID());
             var server = new RavenrootServer(application(engine, true),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator()).configureGraphAuthoring(configured)) {
            server.start();
            var client = HttpClient.newHttpClient();
            URI base = URI.create("http://localhost:" + server.port());

            var capabilities = get(client, base.resolve("/v1/configuration"));
            assertEquals(200, capabilities.statusCode(), capabilities.body());
            assertTrue(capabilities.body().contains("\"mode\":\"git\""), capabilities.body());
            assertTrue(capabilities.body().contains("\"provider\":\"github\""), capabilities.body());
            assertFalse(capabilities.body().contains("fixture-token"), capabilities.body());

            var saved = client.send(HttpRequest.newBuilder(base.resolve("/v1/graph-authoring/orders.graphml"))
                            .header("Idempotency-Key", "embedded-fixture-key-0001")
                            .header("X-Ravenroot-Expected-Draft", "absent")
                            .header("X-Ravenroot-Expected-Release", "absent")
                            .header("X-Ravenroot-Expected-Publication", "absent")
                            .PUT(HttpRequest.BodyPublishers.ofString(GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(saved.body().contains("orders.graphml"), saved.body());
            assertTrue(Files.isRegularFile(repositoryDirectory.resolve("graphs/customers/local/orders.graphml")));

            var listed = get(client, base.resolve("/v1/graph-authoring"));
            assertEquals(200, listed.statusCode(), listed.body());
            assertTrue(listed.body().contains("orders.graphml"), listed.body());

            var artifactRoute = get(client, base.resolve("/v1/graph-artifacts"));
            assertEquals(200, artifactRoute.statusCode(), artifactRoute.body());
            assertTrue(artifactRoute.body().contains("\"items\":[]"), artifactRoute.body());
        }
    }

    @Test
    void publicDecorationRetainsAdmissionAndClosesOwnedDelegateOnce() throws Exception {
        var directory = temporaryDirectory.resolve("decorated-repository");
        initGit(directory);
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var configured =
                EmbeddedGraphAuthoringConfiguration.forTest(
                                configuration(),
                                ignored ->
                                        Optional.of(new SecretValue("fixture-token".toCharArray())),
                                (configuration, credentials, publications, admission) ->
                                        new LocalGitRepository(directory, configuration, admission),
                                EmptyPublishedGraphArtifactCatalogFixture::create)
                        .decorateRepository(
                                delegate ->
                                        new ai.ravenroot.server.authoring
                                                .ForwardingGraphAuthoringRepository(delegate) {
                                            @Override
                                            public void close() {
                                                closed.incrementAndGet();
                                                super.close();
                                            }
                                        });
        try (var engine = new PekkoExecutionEngine("embedded-decoration-" + UUID.randomUUID());
                var server =
                        new RavenrootServer(
                                        application(engine, true),
                                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                                        null,
                                        new DisabledLoopbackAuthenticator())
                                .configureGraphAuthoring(configured)) {
            server.start();
            var invalid =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://localhost:"
                                                                    + server.port()
                                                                    + "/v1/graph-authoring/invalid.graphml"))
                                            .header("Idempotency-Key", "invalid-graph-key-0001")
                                            .header("X-Ravenroot-Expected-Draft", "absent")
                                            .header("X-Ravenroot-Expected-Release", "absent")
                                            .header("X-Ravenroot-Expected-Publication", "absent")
                                            .PUT(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            GRAPH.replace(
                                                                    "source=\"start\"",
                                                                    "source=\"missing\"")))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertTrue(invalid.statusCode() >= 400, invalid.body());
            assertFalse(Files.exists(directory.resolve("graphs/customers/local/invalid.graphml")));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void publicEnvironmentBootstrapConstructsTheProductionProviderCompositionWithoutNetworkAccess()
            throws Exception {
        var configured = EmbeddedGraphAuthoringConfiguration.fromEnvironment(configurationEnvironment(),
                ignored -> Optional.of(new SecretValue("fixture-token".toCharArray())));
        try (var engine = new PekkoExecutionEngine("embedded-provider-" + UUID.randomUUID());
             var server = new RavenrootServer(application(engine, true),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator()).configureGraphAuthoring(configured)) {
            server.start();
            var capabilities = get(HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.port() + "/v1/configuration"));
            assertEquals(200, capabilities.statusCode(), capabilities.body());
            assertTrue(capabilities.body().contains("\"mode\":\"git\""), capabilities.body());
            assertTrue(capabilities.body().contains("\"provider\":\"github\""), capabilities.body());
            assertFalse(capabilities.body().contains("fixture-token"), capabilities.body());
        }
    }

    @Test
    void localModeKeepsExistingEmbeddedBehaviorWithoutResolvingCredentials() throws Exception {
        var resolved = new AtomicBoolean();
        var configuration = EmbeddedGraphAuthoringConfiguration.fromEnvironment(Map.of(), ignored -> {
            resolved.set(true);
            throw new AssertionError("LOCAL mode must not resolve a Git credential");
        });
        try (var engine = new PekkoExecutionEngine("embedded-local-" + UUID.randomUUID());
             var server = new RavenrootServer(application(engine, false),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator()).configureGraphAuthoring(configuration)) {
            server.start();
            var response = get(HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.port() + "/v1/graph-authoring"));
            assertEquals(404, response.statusCode(), response.body());
            assertTrue(response.body().contains("GRAPH_AUTHORING_DISABLED"), response.body());
            assertFalse(resolved.get());
        }
    }

    @Test
    void explicitGitModeRejectsTheInterfaceDefaultWithoutAnImmutableStoreAndReleasesThePort() throws Exception {
        int port = availablePort();
        var closed = new AtomicBoolean();
        try (var engine = new PekkoExecutionEngine("embedded-no-store-" + UUID.randomUUID())) {
            var customApplication = new ForwardingRavenrootApplication(application(engine, true)) {
                @Override public void close() {
                    closed.set(true);
                    super.close();
                }
            };
            var server = new RavenrootServer(customApplication,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), null,
                    new DisabledLoopbackAuthenticator());
            var failure = assertThrows(IllegalStateException.class,
                    () -> server.configureGraphAuthoring(EmbeddedGraphAuthoringConfiguration.of(
                            configuration(), ignored -> Optional.of(
                                    new SecretValue("fixture-token".toCharArray())))));
            assertEquals("Git graph authoring requires an immutable graph definition store", failure.getMessage());
            assertTrue(closed.get(), "configuration failure must close the owned application");
            var restart = assertThrows(IllegalStateException.class, server::start);
            assertEquals("server is closed", restart.getMessage());
            assertPortCanBind(port);
        }
    }

    @Test
    void missingAndWhitespaceOnlyCredentialsFailWithoutDisclosingValuesAndReleaseThePort() throws Exception {
        for (String credential : List.of("", "  \t")) {
            int port = availablePort();
            try (var engine = new PekkoExecutionEngine("embedded-no-credential-" + UUID.randomUUID())) {
                var server = new RavenrootServer(application(engine, true),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port), null,
                        new DisabledLoopbackAuthenticator());
                var failure = assertThrows(IllegalStateException.class,
                        () -> server.configureGraphAuthoring(EmbeddedGraphAuthoringConfiguration.of(
                                configuration(), ignored -> credential.isEmpty() ? Optional.empty()
                                        : Optional.of(new SecretValue(credential.toCharArray())))));
                assertEquals("Git graph authoring credential is unavailable", failure.getMessage());
                assertFalse(failure.toString().contains("embedded-fixture"), failure.toString());
                assertPortCanBind(port);
            }
        }
        int port = availablePort();
        try (var engine = new PekkoExecutionEngine("embedded-resolver-failure-" + UUID.randomUUID())) {
            var server = new RavenrootServer(application(engine, true),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), null,
                    new DisabledLoopbackAuthenticator());
            var resolverFailure = new IllegalStateException(
                    "Git graph authoring credential is unavailable",
                    new IllegalArgumentException("embedded-fixture-secret-cause"));
            resolverFailure.addSuppressed(new IllegalArgumentException("embedded-fixture-secret-suppressed"));
            var failure = assertThrows(IllegalStateException.class,
                    () -> server.configureGraphAuthoring(EmbeddedGraphAuthoringConfiguration.of(
                            configuration(), ignored -> { throw resolverFailure; })));
            assertEquals("Git graph authoring credential is unavailable", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertFalse(failure.toString().contains("embedded-fixture-secret"), failure.toString());
            assertPortCanBind(port);
        }
    }

    @Test
    void incompleteGitConfigurationFailsBeforeAListenerIsConstructed() {
        var environment = new java.util.HashMap<String, String>();
        environment.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> EmbeddedGraphAuthoringConfiguration.fromEnvironment(environment, ignored -> Optional.empty()));
        assertTrue(failure.getMessage().contains("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER"),
                failure.getMessage());
    }

    @Test
    void nullEmbeddedConfigurationClosesTheBoundServer() throws Exception {
        int port = availablePort();
        var closed = new AtomicBoolean();
        try (var engine = new PekkoExecutionEngine("embedded-null-configuration-" + UUID.randomUUID())) {
            var customApplication = new ForwardingRavenrootApplication(application(engine, false)) {
                @Override public void close() {
                    closed.set(true);
                    super.close();
                }
            };
            var server = new RavenrootServer(customApplication,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), null,
                    new DisabledLoopbackAuthenticator());
            assertThrows(NullPointerException.class, () -> server.configureGraphAuthoring(null));
            assertTrue(closed.get(), "configuration failure must close the owned application");
            assertPortCanBind(port);
        }
    }

    private static GraphAuthoringConfiguration configuration() {
        return GraphAuthoringConfiguration.fromEnvironment(configurationEnvironment());
    }

    private static Map<String, String> configurationEnvironment() {
        return Map.ofEntries(
                Map.entry("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_PROVIDER", "GITHUB"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "PAT"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "embedded-fixture"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "local=customers/local"),
                Map.entry("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/"),
                Map.entry("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "0"));
    }

    private static DefaultRavenrootApplication application(PekkoExecutionEngine engine, boolean definitions) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(), BehaviorRegistry.standard(),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(),
                null, 0, UnknownBehaviorPolicy.passThrough(),
                definitions ? new InMemoryGraphDefinitionStore(Clock.systemUTC()) : null);
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void assertPortCanBind(int port) throws IOException {
        try (var ignored = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(ignored.isBound());
        }
    }

    private static void initGit(Path directory) throws Exception {
        Files.createDirectories(directory);
        git(directory, "init", "--initial-branch=main");
        git(directory, "config", "user.name", "Ravenroot test");
        git(directory, "config", "user.email", "test@ravenroot.invalid");
        git(directory, "commit", "--allow-empty", "-m", "Initialize local graph fixture");
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("local Git fixture command failed: " + output);
        return output.strip();
    }

    private static final class LocalGitRepository implements GraphAuthoringRepository {
        private final Path root;
        private final GraphAuthoringConfiguration configuration;
        private final Consumer<byte[]> admission;

        private LocalGitRepository(Path root, GraphAuthoringConfiguration configuration,
                Consumer<byte[]> admission) {
            this.root = root;
            this.configuration = configuration;
            this.admission = admission;
        }

        @Override public Set<Capability> capabilities() {
            return Set.of(Capability.LIST, Capability.OPEN, Capability.SAVE);
        }

        @Override public CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor) {
            try {
                String prefix = configuration.tenantDirectory(actor.tenantId()) + "/";
                Revision revision = revision();
                var items = git(root, "ls-files", prefix).lines()
                        .filter(path -> path.endsWith(".graphml"))
                        .map(path -> summary(path.substring(prefix.length()), revision))
                        .toList();
                return CompletableFuture.completedFuture(new Page<>(items, ""));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<Document> open(Actor actor, String documentId, boolean released) {
            try {
                String path = configuration.tenantDirectory(actor.tenantId()) + "/" + documentId;
                byte[] bytes = git(root, "show", "HEAD:" + path).getBytes(StandardCharsets.UTF_8);
                return CompletableFuture.completedFuture(new Document(summary(documentId, revision()), bytes));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<Document> save(Actor actor, SaveRequest request) {
            try {
                admission.accept(request.graphMl());
                String path = configuration.tenantDirectory(actor.tenantId()) + "/" + request.documentId();
                Path target = root.resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, request.graphMl());
                git(root, "add", "--", path);
                git(root, "commit", "-m", "Save " + request.documentId());
                return CompletableFuture.completedFuture(
                        new Document(summary(request.documentId(), revision()), request.graphMl()));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<Page<HistoryEntry>> history(Actor actor, String id, String cursor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Difference> diff(Actor actor, String id, String from, String to) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Document> restore(Actor actor, MutationRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Document> discardDraft(Actor actor, MutationRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        private Revision revision() throws Exception {
            String head = git(root, "rev-parse", "HEAD");
            return new Revision(head, head, "absent");
        }

        private static DocumentSummary summary(String id, Revision revision) {
            return new DocumentSummary(id, id, "embedded-authoring", 1, revision,
                    false, false, false, "", 0);
        }
    }
}
