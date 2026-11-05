package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.SecretValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(30)
class GitlabAuthoringTransportTest {
    @TempDir Path temporary;
    private static final GraphAuthoringRepository.Actor ACTOR =
            new GraphAuthoringRepository.Actor("tenant-a", "transport-fixture");

    @Test
    void faithfulFilesGetTransfersContentAndHeadTransfersOnlyMetadata() throws Exception {
        var api = new GitlabAuthoringRepositoryTest.Gitlab();
        api.seed(1);
        var uri =
                URI.create(
                        "https://gitlab.example.test/api/v4/projects/platform%2Fnested%2Fgraphs/repository/files/graphs%2Fcustomers%2Fa%2Fg0000.graphml?ref="
                                + api.refs.get("draft/customers-a"));
        var get =
                api.send(
                        HttpRequest.newBuilder(uri)
                                .header("PRIVATE-TOKEN", "server-secret")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        var file = (Map<?, ?>) PayloadJson.read(get.body(), PayloadLimits.DEFAULTS).toJava();
        assertEquals("base64", file.get("encoding"));
        byte[] content = Base64.getDecoder().decode(file.get("content").toString());
        assertTrue(new String(content, StandardCharsets.UTF_8).contains("graphml"));
        assertEquals(1, api.benchmarkBlobs.get());
        assertEquals(1, api.benchmarkFileGets.get());
        assertEquals(content.length, api.benchmarkDocumentBytes.get());
        long transferred = api.benchmarkResponseBytes.get();
        assertTrue(transferred > content.length);
        var head =
                api.send(
                        HttpRequest.newBuilder(uri)
                                .header("PRIVATE-TOKEN", "server-secret")
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(0, head.body().length);
        assertEquals(
                file.get("blob_id"), head.headers().firstValue("X-Gitlab-Blob-Id").orElseThrow());
        assertEquals(
                file.get("last_commit_id"),
                head.headers().firstValue("X-Gitlab-Last-Commit-Id").orElseThrow());
        assertEquals(transferred, api.benchmarkResponseBytes.get());
        assertEquals(content.length, api.benchmarkDocumentBytes.get());
        assertEquals(1, api.benchmarkFileHeads.get());
    }

    @Test
    void warmAndRestartedOpensReuseContentWithFreshCredentialsAndMutationCas() {
        var api = new GitlabAuthoringRepositoryTest.Gitlab();
        api.seed(1);
        var token = new AtomicReference<>("server-secret");
        Path file = temporary.resolve("index.db");
        try (var repository = repository(api, token, file)) {
            repository.list(ACTOR, "").toCompletableFuture().join();
            int bodies = api.benchmarkBlobs.get();
            long content = api.benchmarkDocumentBytes.get();
            for (int i = 0; i < 10; i++)
                repository.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
            assertEquals(bodies, api.benchmarkBlobs.get());
            assertEquals(content, api.benchmarkDocumentBytes.get());
            assertEquals(0, api.benchmarkFileGets.get());
            assertTrue(api.benchmarkFileHeads.get() >= 10);
        }
        try (var reopened = repository(api, token, file)) {
            int bodies = api.benchmarkBlobs.get();
            long content = api.benchmarkDocumentBytes.get();
            var original =
                    reopened.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
            assertEquals(bodies, api.benchmarkBlobs.get());
            assertEquals(content, api.benchmarkDocumentBytes.get());
            api.expectedToken = "rotated-fixture-secret";
            token.set(api.expectedToken);
            reopened.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
            assertEquals(bodies, api.benchmarkBlobs.get());
            api.unauthorized = true;
            failure(
                    GraphAuthoringException.Failure.UNAUTHORIZED,
                    () ->
                            reopened.open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.unauthorized = false;
            token.set(null);
            failure(
                    GraphAuthoringException.Failure.UNAUTHORIZED,
                    () ->
                            reopened.open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            token.set(api.expectedToken);
            api.external(
                    "graphs/customers/a/g0000.graphml",
                    AuthoringReadIndexIntegrationTest.graph("g0", "external"));
            int heads = api.benchmarkFileHeads.get();
            failure(
                    GraphAuthoringException.Failure.CONFLICT,
                    () ->
                            reopened.save(
                                            ACTOR,
                                            new GraphAuthoringRepository.SaveRequest(
                                                    "g0000.graphml",
                                                    original.graphMl(),
                                                    original.summary().revision(),
                                                    "stale-transport-request-001"))
                                    .toCompletableFuture()
                                    .join());
            assertTrue(
                    api.benchmarkFileHeads.get() > heads,
                    "remote metadata still gates mutation preconditions");
            var current = reopened.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
            reopened.save(
                            ACTOR,
                            new GraphAuthoringRepository.SaveRequest(
                                    "g0000.graphml",
                                    AuthoringReadIndexIntegrationTest.graph("g0", "saved"),
                                    current.summary().revision(),
                                    "valid-transport-request-001"))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, api.writes);
            assertEquals(0, api.benchmarkFileGets.get());
        }
    }

    @Test
    void metadataIdentityHeadersFailClosedAndHeadRateLimitRetriesAreBounded() {
        var api = new GitlabAuthoringRepositoryTest.Gitlab();
        api.seed(1);
        var token = new AtomicReference<>("server-secret");
        try (var repository = repository(api, token, temporary.resolve("headers.db"))) {
            repository.list(ACTOR, "").toCompletableFuture().join();
            int bodies = api.benchmarkBlobs.get();
            api.headBlobOverride = "f".repeat(40);
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headBlobOverride = null;
            api.headCommitOverride = "e".repeat(40);
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headCommitOverride = null;
            api.headMissingLast = true;
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headMissingLast = false;
            api.headMissingCommit = true;
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headMissingCommit = false;
            api.headDuplicateBlob = true;
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headDuplicateBlob = false;
            api.headStatus = 403;
            failure(
                    GraphAuthoringException.Failure.UNAUTHORIZED,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            api.headStatus = 0;
            api.retryHeadResponses.set(3);
            failure(
                    GraphAuthoringException.Failure.UNAVAILABLE,
                    () ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join());
            assertEquals(
                    1, api.retryHeadResponses.get(), "one initial HEAD plus one configured retry");
            api.retryHeadResponses.set(1);
            repository.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
            assertEquals(0, api.retryHeadResponses.get());
            assertEquals(bodies, api.benchmarkBlobs.get());
            assertEquals(0, api.benchmarkFileGets.get());
        }
    }

    private static GitlabAuthoringRepository repository(
            GitlabAuthoringRepositoryTest.Gitlab api, AtomicReference<String> token, Path file) {
        var repository =
                new GitlabAuthoringRepository(
                        GitlabAuthoringRepositoryTest.configuration(),
                        ignored ->
                                Optional.ofNullable(token.get())
                                        .map(value -> new SecretValue(value.toCharArray())),
                        PublicationEvidence.absent(),
                        ignored -> {},
                        api,
                        Clock.systemUTC());
        repository.useReadIndex(new SqliteGraphAuthoringReadIndex(file));
        return repository;
    }

    private static void failure(GraphAuthoringException.Failure expected, Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (!(failure instanceof GraphAuthoringException) && failure.getCause() != null)
            failure = failure.getCause();
        assertInstanceOf(GraphAuthoringException.class, failure);
        assertEquals(expected, ((GraphAuthoringException) failure).failure());
    }
}
