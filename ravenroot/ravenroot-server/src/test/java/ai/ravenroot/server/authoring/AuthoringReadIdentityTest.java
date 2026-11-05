package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.SecretValue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class AuthoringReadIdentityTest {
    @Test
    void githubAppRotationAndRevocationGateWarmHitsAndTenantCursors() throws Exception {
        var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
        api.seed(100);
        var environment = GithubAuthoringRepositoryTest.environment();
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "APP");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID", "1");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID", "1");
        environment.put(
                "RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES",
                "tenant-a=customers/a,tenant-b=customers/b");
        var secret = new AtomicReference<>(key());
        try (var repository =
                new GithubAuthoringRepository(
                        GraphAuthoringConfiguration.fromEnvironment(environment),
                        ignored ->
                                Optional.ofNullable(secret.get())
                                        .map(value -> new SecretValue(value.toCharArray())),
                        PublicationEvidence.absent(),
                        ignored -> {},
                        api,
                        Clock.systemUTC())) {
            var page =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join();
            assertEquals(1, api.tokenMints.get());
            int bodies = api.benchmarkBlobs.get();
            repository
                    .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                    .toCompletableFuture()
                    .join();
            assertEquals(bodies, api.benchmarkBlobs.get());
            secret.set(key());
            repository
                    .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                    .toCompletableFuture()
                    .join();
            assertEquals(2, api.tokenMints.get());
            var other = new GraphAuthoringRepository.Actor("tenant-b", "same-reader");
            assertTrue(repository.list(other, "").toCompletableFuture().join().items().isEmpty());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> repository.list(other, page.nextCursor()).toCompletableFuture().join());
            secret.set(null);
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            repository
                                    .open(
                                            AuthoringReadIndexIntegrationTest.ACTOR,
                                            "g0000.graphml",
                                            false)
                                    .toCompletableFuture()
                                    .join());
        }
    }

    @Test
    void immutableHistoryAndDiffResultsRemainScopedAndReusePages() {
        var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
        api.seed(10);
        try (var repository =
                GithubAuthoringRepositoryTest.repository(api, PublicationEvidence.absent())) {
            var first =
                    repository
                            .open(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", false)
                            .toCompletableFuture()
                            .join();
            int warmOpenCalls = api.benchmarkRequests.get();
            repository
                    .open(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", false)
                    .toCompletableFuture()
                    .join();
            assertEquals(
                    3,
                    api.benchmarkRequests.get() - warmOpenCalls,
                    "auth and two mutable refs only; immutable commit/tree/blob objects reused"
                        + " without ETag");
            String one = first.summary().revision().draft().split(":")[0];
            api.external(
                    "graphs/customers/a/g0000.graphml",
                    AuthoringReadIndexIntegrationTest.graph("g0", "next"));
            var second =
                    repository
                            .open(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", false)
                            .toCompletableFuture()
                            .join();
            String two = second.summary().revision().draft().split(":")[0];
            repository
                    .history(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", "")
                    .toCompletableFuture()
                    .join();
            int calls = api.benchmarkRequests.get();
            repository
                    .history(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", "")
                    .toCompletableFuture()
                    .join();
            assertEquals(2, api.benchmarkRequests.get() - calls);
            var difference =
                    repository
                            .diff(
                                    AuthoringReadIndexIntegrationTest.ACTOR,
                                    "g0000.graphml",
                                    one,
                                    two)
                            .toCompletableFuture()
                            .join();
            assertFalse(difference.unifiedDiff().isEmpty());
            calls = api.benchmarkRequests.get();
            assertEquals(
                    difference,
                    repository
                            .diff(
                                    AuthoringReadIndexIntegrationTest.ACTOR,
                                    "g0000.graphml",
                                    one,
                                    two)
                            .toCompletableFuture()
                            .join());
            assertEquals(1, api.benchmarkRequests.get() - calls);
            api.unauthorized = true;
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            repository
                                    .diff(
                                            AuthoringReadIndexIntegrationTest.ACTOR,
                                            "g0000.graphml",
                                            one,
                                            two)
                                    .toCompletableFuture()
                                    .join());
        }
    }

    static String key() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
