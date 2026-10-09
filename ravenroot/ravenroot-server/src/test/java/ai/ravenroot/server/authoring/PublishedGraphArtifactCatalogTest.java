package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class PublishedGraphArtifactCatalogTest {
    private static final PayloadLimits JSON = new PayloadLimits(2 * 1024 * 1024, 24, 10_000,
            100_000, 1024 * 1024, 512);

    @Test void retainedVersionsCanBeSelectedForRollbackWithVerifiedSourceEvidence() {
        var client = new ArtifactClient();
        addRelease(client, "orders", 1, "one", "a".repeat(40));
        addRelease(client, "orders", 2, "two", "b".repeat(40));
        client.catalog();
        var catalog = new PublishedGraphArtifactCatalog(configuration(), client);

        var versions = catalog.list("tenant-a");
        assertEquals(List.of(1L, 2L), versions.stream().map(PublishedGraphArtifactCatalog.Artifact::releaseVersion).toList());
        var rollback = catalog.resolve("tenant-a", "orders", 1);
        assertEquals("a".repeat(40), rollback.sourceCommit());
        assertTrue(new String(rollback.graphMl(), StandardCharsets.UTF_8).contains("one"));
        assertEquals(List.of(new PinnedNodePackage("mail", "d".repeat(64))),
                rollback.dependencies().nodePackages());
        assertTrue(rollback.dependencies().programs().isEmpty());
    }

    @Test void digestMismatchAndUnavailableManifestAreRefused() {
        var mismatch = new ArtifactClient();
        addRelease(mismatch, "orders", 1, "one", "a".repeat(40));
        mismatch.entries.getFirst().put("manifestSha256", "0".repeat(64));
        mismatch.catalog();
        assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                () -> new PublishedGraphArtifactCatalog(configuration(), mismatch).list("tenant-a"));

        var unavailable = new ArtifactClient();
        addRelease(unavailable, "orders", 1, "one", "a".repeat(40));
        unavailable.catalog();
        unavailable.failManifest = true;
        assertFailure(GraphAuthoringException.Failure.UNAVAILABLE,
                () -> new PublishedGraphArtifactCatalog(configuration(), unavailable).list("tenant-a"));
    }

    @Test void publicationTokenRequiresExactReleaseCommitAndBytes() {
        var client = new ArtifactClient();
        String source = "a".repeat(40);
        addRelease(client, "orders", 1, "one", source);
        client.catalog();
        var catalog = new PublishedGraphArtifactCatalog(configuration(), client);
        byte[] graph = catalog.resolve("tenant-a", "orders", 1).graphMl();

        assertNotEquals("absent", catalog.token("tenant-a", "orders", 1, source, graph));
        assertEquals("absent", catalog.token("tenant-a", "orders", 1, "b".repeat(40), graph));
        graph[graph.length - 1] ^= 1;
        assertEquals("absent", catalog.token("tenant-a", "orders", 1, source, graph));
    }

    @Test void conflictingDuplicateIdentityAndSourceCommitMismatchAreRefused() {
        var duplicate = new ArtifactClient();
        addRelease(duplicate, "orders", 1, "one", "a".repeat(40));
        duplicate.entries.add(new HashMap<>(duplicate.entries.getFirst()));
        duplicate.catalog();
        assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                () -> new PublishedGraphArtifactCatalog(configuration(), duplicate).list("tenant-a"));

        var sourceMismatch = new ArtifactClient();
        addRelease(sourceMismatch, "orders", 1, "one", "a".repeat(40));
        sourceMismatch.entries.getFirst().put("sourceCommit", "b".repeat(40));
        sourceMismatch.catalog();
        assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                () -> new PublishedGraphArtifactCatalog(configuration(), sourceMismatch).list("tenant-a"));
    }

    @Test void catalogVersionsRejectAliasesFractionsExponentsAndUnsafeIntegers() {
        for (Object invalid : List.of("1", 1.0, 1.5, 1e20, 9_007_199_254_740_992L)) {
            var client = new ArtifactClient();
            addRelease(client, "orders", 1, "one", "a".repeat(40));
            client.entries.getFirst().put("releaseVersion", invalid);
            client.catalog();
            assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                    () -> new PublishedGraphArtifactCatalog(configuration(), client).list("tenant-a"));
        }
    }

    private static void addRelease(ArtifactClient client, String graphId, long version, String label, String source) {
        byte[] raw = ("<?xml version=\"1.0\"?><graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">"
                + "<key id=\"label\" for=\"graph\" attr.name=\"label\" attr.type=\"string\"/>"
                + "<graph id=\"G\" edgedefault=\"directed\"><data key=\"label\">" + label
                + "</data></graph></graphml>").getBytes(StandardCharsets.UTF_8);
        byte[] graph = GraphReleaseMetadata.assign(raw, graphId, version).graphMl();
        String graphHash = digest(graph);
        String graphPath = "graphs/" + graphId + "/" + version + ".graphml";
        Map<String, Object> manifest = Map.of(
                "contract", "ravenroot-graph-artifact-v1", "tenantId", "tenant-a",
                "graphId", graphId, "releaseVersion", version, "sourceCommit", source,
                "graphMlSha256", graphHash,
                "compatibility", Map.of("accepted", true,
                        "contract", "ravenroot-local-deployment-admission-v1",
                        "purpose", "LOCAL_DEPLOYMENT", "nodes", 1, "edges", 0,
                        "startNodes", 1, "endNodes", 1,
                        "violations", List.of(), "findings", List.of()),
                "dependencies", Map.of("contract", "ravenroot-graph-dependencies-v1",
                        "nodePackages", List.of(Map.of("packageId", "mail", "identityDigest", "d".repeat(64))),
                        "programs", List.of()));
        byte[] manifestBytes = PayloadJson.writeJava(manifest, JSON);
        String manifestPath = "manifests/" + graphId + "-" + version + ".json";
        client.objects.put(graphPath, graph);
        client.objects.put(manifestPath, manifestBytes);
        client.entries.add(new HashMap<>(Map.of(
                "tenantId", "tenant-a", "graphId", graphId, "releaseVersion", version,
                "sourceCommit", source, "graphMlSha256", graphHash, "graphMlPath", graphPath,
                "manifestSha256", digest(manifestBytes), "manifestPath", manifestPath)));
    }

    private static GraphAuthoringConfiguration configuration() {
        var environment = new HashMap<String, String>();
        environment.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "PAT");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "git-authoring");
        environment.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=customers/a");
        environment.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/releases/");
        return GraphAuthoringConfiguration.fromEnvironment(environment);
    }

    private static void assertFailure(GraphAuthoringException.Failure expected, Runnable action) {
        GraphAuthoringException failure = assertThrows(GraphAuthoringException.class, action::run);
        assertEquals(expected, failure.failure());
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private static final class ArtifactClient extends HttpClient {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<Map<String, Object>> entries = new java.util.ArrayList<>();
        boolean failManifest;

        void catalog() {
            objects.put("catalog.json", PayloadJson.writeJava(
                    Map.of("contract", "ravenroot-graph-catalog-v1", "artifacts", entries), JSON));
        }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            String prefix = "/releases/";
            String path = request.uri().getPath().substring(prefix.length());
            if (failManifest && path.startsWith("manifests/")) {
                return (HttpResponse<T>) new Response(request, 503, new byte[0]);
            }
            byte[] bytes = objects.get(path);
            return (HttpResponse<T>) new Response(request, bytes == null ? 404 : 200,
                    bytes == null ? new byte[0] : bytes);
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                          HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }

    private record Response(HttpRequest request, int statusCode, byte[] body)
            implements HttpResponse<byte[]> {
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
