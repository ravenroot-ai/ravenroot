package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuthoringPublicationIndexTest {
    @Test
    void publicationOnlyChangesAreFreshAndWarmValidationIsConstantWithoutProviderBodies() {
        var artifacts = new PublishedGraphArtifactCatalogTest.ArtifactClient();
        artifacts.catalog();
        var catalog =
                new PublishedGraphArtifactCatalog(
                        PublishedGraphArtifactCatalogTest.configuration(), artifacts);
        var github = new GithubAuthoringRepositoryTest.ScriptedGithub();
        github.seed(1);
        PublishedGraphArtifactCatalogTest.addRelease(
                artifacts, "g0", 1, "published", "%040x".formatted(11));
        byte[] graph = artifacts.objects.get("graphs/g0/1.graphml");
        github.external("graphs/customers/a/g0000.graphml", graph);
        github.mergeDraftToMain();
        // Catalog is still the previously published empty observation.
        try (var repository = GithubAuthoringRepositoryTest.repository(github, catalog)) {
            var before =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join();
            assertFalse(before.items().getFirst().published());
            int providerBodies = github.benchmarkBlobs.get();
            artifacts.catalog();
            long start = catalog.providerCalls();
            var published =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join();
            assertTrue(published.items().getFirst().published());
            assertEquals(providerBodies, github.benchmarkBlobs.get());
            assertEquals(3, catalog.providerCalls() - start);
            start = catalog.providerCalls();
            for (int i = 0; i < 50; i++)
                assertTrue(
                        repository
                                .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                                .toCompletableFuture()
                                .join()
                                .items()
                                .getFirst()
                                .published());
            assertEquals(50, catalog.providerCalls() - start);
            assertEquals(providerBodies, github.benchmarkBlobs.get());
            artifacts.entries.clear();
            artifacts.catalog();
            assertFalse(
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join()
                            .items()
                            .getFirst()
                            .published());
        }
    }

    @Test
    void providerRateLimitsRetryButAuthorizationDoesNot() {
        for (boolean github : java.util.List.of(true, false))
            for (int status : new int[] {403, 429}) {
                var gh = github ? new GithubAuthoringRepositoryTest.ScriptedGithub() : null;
                var gl = github ? null : new GitlabAuthoringRepositoryTest.Gitlab();
                GraphAuthoringConfiguration configuration;
                if (github) {
                    gh.seed(10);
                    gh.transientStatus = status;
                    gh.transientResponses.set(1);
                    var environment = GithubAuthoringRepositoryTest.environment();
                    environment.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "1");
                    configuration = GraphAuthoringConfiguration.fromEnvironment(environment);
                } else {
                    gl.seed(10);
                    gl.transientStatus = status;
                    gl.transientResponses.set(1);
                    configuration = GitlabAuthoringRepositoryTest.configuration();
                }
                try (ai.ravenroot.api.authoring.GraphAuthoringRepository repository =
                        github
                                ? new GithubAuthoringRepository(
                                        configuration,
                                        ignored ->
                                                java.util.Optional.of(
                                                        new ai.ravenroot.api.security.SecretValue(
                                                                "pat".toCharArray())),
                                        PublicationEvidence.absent(),
                                        ignored -> {},
                                        gh,
                                        java.time.Clock.systemUTC())
                                : GitlabAuthoringRepositoryTest.repository(
                                        gl, PublicationEvidence.absent())) {
                    assertFalse(
                            repository
                                    .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                                    .toCompletableFuture()
                                    .join()
                                    .items()
                                    .isEmpty());
                    if (github) {
                        gh.transientStatus = 403;
                        gh.transientResponses.set(1);
                        gh.rateHeaders = false;
                    } else {
                        gl.transientStatus = 403;
                        gl.transientResponses.set(1);
                        gl.rateHeaders = false;
                    }
                    AuthoringReadIndexIntegrationTest.assertFailure(
                            ai.ravenroot.api.authoring.GraphAuthoringException.Failure.UNAUTHORIZED,
                            () ->
                                    repository
                                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                                            .toCompletableFuture()
                                            .join());
                }
            }
    }

    @Test
    void retryClassificationHonorsRateLimitsAndDoesNotRetryAuthorization() {
        var limited =
                java.net.http.HttpHeaders.of(
                        java.util.Map.of("X-RateLimit-Remaining", java.util.List.of("0")),
                        (left, right) -> true);
        var authorization = java.net.http.HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
        assertTrue(AuthoringRetry.retryable(403, limited));
        assertFalse(AuthoringRetry.retryable(403, authorization));
        assertTrue(AuthoringRetry.retryable(429, authorization));
        var delay =
                java.net.http.HttpHeaders.of(
                        java.util.Map.of("Retry-After", java.util.List.of("999999")),
                        (left, right) -> true);
        assertThrows(
                ai.ravenroot.api.authoring.GraphAuthoringException.class,
                () -> AuthoringRetry.pause(delay, 0, java.time.Duration.ofSeconds(1)));
        var immediate =
                java.net.http.HttpHeaders.of(
                        java.util.Map.of("Retry-After", java.util.List.of("0")),
                        (left, right) -> true);
        AuthoringRetry.pause(immediate, 0, java.time.Duration.ofSeconds(1));
    }
}
