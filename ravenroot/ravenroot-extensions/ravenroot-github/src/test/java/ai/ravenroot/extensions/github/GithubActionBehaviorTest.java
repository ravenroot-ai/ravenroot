package ai.ravenroot.extensions.github;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GithubActionBehaviorTest {
    @TempDir Path directory;

    @Test void projectClaimWritesAbsoluteAttemptsAndGenerationThenReconciles() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var http = new GithubTestSupport.HttpHarness()
                .reply(200, snapshot("Todo", 2, 7))
                .reply(200, Map.of("data", Map.of("status", Map.of("clientMutationId", "x"))))
                .reply(200, snapshot("InProgress", 3, 8));
        NodeResult result = action(nodePackage, "project-transition", http).handle(GithubTestSupport.message(Map.of(
                "version", "github.project-transition.v1", "itemId", "ITEM_1", "fromStatus", "Todo",
                "toStatus", "InProgress", "expectedGeneration", 7L, "expectedAttempts", 2L,
                "correlationId", "claim-1"))).toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals(3L, GithubValues.object(result.payload()).get("attempts"));
        assertEquals(8L, GithubValues.object(result.payload()).get("generation"));
        Map<String, Object> update = GithubValues.json(http.requests.get(1).body());
        Map<String, Object> variables = GithubValues.object(update.get("variables"));
        assertEquals(3L, variables.get("attempts"));
        assertEquals(8L, variables.get("generation"));
    }

    @Test void projectClaimRejectsAttemptsOverflowBeforeAnyOutboundCall() {
        Map<String, Object> input = new java.util.LinkedHashMap<>(project("attempts-overflow"));
        input.put("expectedAttempts", (long) Integer.MAX_VALUE);
        var http = new GithubTestSupport.HttpHarness();
        CompletableFuture<NodeResult> result = action(GithubTestSupport.nodePackage(
                        directory.resolve("attempts-overflow.db")), "project-transition", http)
                .handle(GithubTestSupport.message(Map.copyOf(input))).toCompletableFuture();
        assertEquals(GithubException.Code.INVALID_INPUT, githubFailure(result).code());
        assertTrue(http.requests.isEmpty());
    }

    @Test void secretShapedUntrustedIdentifiersNeverReachOperationOrAuditStorage() throws Exception {
        String secretShaped = "ghp_" + "A".repeat(36);
        Path path = directory.resolve("sanitized-keys.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);

        Map<String, Object> projectInput = new java.util.LinkedHashMap<>(project(secretShaped));
        projectInput.put("itemId", secretShaped);
        var projectHttp = new GithubTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("1")), Map.of());
        new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), projectHttp)
                .handle(GithubTestSupport.message(Map.copyOf(projectInput))).toCompletableFuture().join();

        var reviewHttp = new GithubTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("1")), Map.of());
        new GithubAppReviewBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("github-app-review"), reviewHttp)
                .handle(GithubTestSupport.message(review(secretShaped))).toCompletableFuture().join();

        var releaseHttp = new GithubTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("1")), Map.of());
        new ReleasePrepareBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("release-prepare"), releaseHttp)
                .handle(GithubTestSupport.message(Map.of("version", "github.release-prepare.v1",
                        "commit", GithubTestSupport.SHA, "releaseKind", "minor",
                        "correlationId", secretShaped))).toCompletableFuture().join();

        ManualScheduler scheduler = new ManualScheduler();
        var workflowHttp = new GithubTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("1")), Map.of());
        CompletableFuture<NodeResult> workflow = new GithubWorkflowWatchBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock), clock, scheduler)
                .create(GithubTestSupport.node("github-workflow-watch"), workflowHttp)
                .handle(GithubTestSupport.message(Map.of("version", "github.workflow-watch.v1",
                        "commit", GithubTestSupport.SHA, "deadlineEpochMs", clock.millis() + 10_000,
                        "correlationId", secretShaped))).toCompletableFuture();
        scheduler.take().run(); scheduler.take();

        String rawDatabase = new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        assertFalse(rawDatabase.contains(secretShaped));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            for (String table : List.of("github_operations", "github_operation_audit"))
                try (var rows = statement.executeQuery("SELECT operation_key FROM " + table)) {
                    while (rows.next()) assertFalse(rows.getString(1).contains(secretShaped), table);
                }
        }
        workflow.cancel(true);
        assertEquals(GithubException.Code.CANCELLED, githubFailure(workflow).code());
    }

    @Test void effectFreeRateLimitsPersistWaitingAcrossReviewProjectAndReleaseReopen() {
        assertEffectFreeRateRetry("review", (runtime, http, correlation) ->
                        new GithubAppReviewBehavior(runtime).create(GithubTestSupport.node("github-app-review"), http)
                                .handle(GithubTestSupport.message(review(correlation))).toCompletableFuture(),
                new GithubTestSupport.HttpHarness().reply(429, Map.of("retry-after", List.of("1")), Map.of()),
                new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                        .reply(200, pull(GithubTestSupport.SHA))
                        .reply(201, reviewObject(71, "PENDING", reviewMarker("rate-review")))
                        .reply(200, pull(GithubTestSupport.SHA))
                        .reply(200, reviewObject(71, "APPROVED", reviewMarker("rate-review")))
                        .reply(200, pull(GithubTestSupport.SHA)));

        assertEffectFreeRateRetry("project", (runtime, http, correlation) ->
                        new ProjectTransitionBehavior(runtime).create(GithubTestSupport.node("project-transition"), http)
                                .handle(GithubTestSupport.message(project(correlation))).toCompletableFuture(),
                new GithubTestSupport.HttpHarness().reply(403,
                        Map.of("x-ratelimit-remaining", List.of("0")), Map.of()),
                new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7))
                        .reply(200, Map.of("data", Map.of("status", Map.of("clientMutationId", "x"))))
                        .reply(200, snapshot("InProgress", 3, 8)));

        assertEffectFreeRateRetry("release", (runtime, http, correlation) ->
                        new ReleasePrepareBehavior(runtime).create(GithubTestSupport.node("release-prepare"), http)
                                .handle(GithubTestSupport.message(Map.of("version", "github.release-prepare.v1",
                                        "commit", GithubTestSupport.SHA, "releaseKind", "minor",
                                        "correlationId", correlation))).toCompletableFuture(),
                new GithubTestSupport.HttpHarness().reply(403,
                        Map.of("retry-after", List.of("1")), Map.of()), releaseReplies());
    }

    @Test void projectReconciliationRateLimitPersistsAmbiguityAndNeverDuplicatesMutation() {
        Path path = directory.resolve("project-rate-reconcile.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        var first = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7))
                .reply(500, Map.of("message", "unknown"))
                .reply(403, Map.of("x-ratelimit-remaining", List.of("0")), Map.of());
        NodeResult ambiguous = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), first)
                .handle(GithubTestSupport.message(project("project-rate-reconcile"))).toCompletableFuture().join();
        assertEquals("ambiguous", ambiguous.outcome());
        assertEquals("RATE_LIMITED", GithubValues.object(ambiguous.payload()).get("reason"));
        assertEquals(1, mutationRequests(first));

        var premature = new GithubTestSupport.HttpHarness();
        NodeResult replay = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), premature)
                .handle(GithubTestSupport.message(project("project-rate-reconcile"))).toCompletableFuture().join();
        assertEquals("ambiguous", replay.outcome()); assertTrue(premature.requests.isEmpty());

        clock.advance(30_000);
        var recovered = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8));
        NodeResult completed = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), recovered)
                .handle(GithubTestSupport.message(project("project-rate-reconcile"))).toCompletableFuture().join();
        assertEquals("continue", completed.outcome()); assertEquals(0, mutationRequests(recovered));
    }

    @Test void reviewPostDispatchReconciliationRateLimitRestartsWithoutDuplicateDraft() {
        Path path = directory.resolve("review-rate-reconcile.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        String correlation = "review-rate-reconcile";
        var first = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull(GithubTestSupport.SHA)).reply(500, Map.of("message", "unknown"))
                .reply(403, Map.of("retry-after", List.of("1")), Map.of());
        NodeResult ambiguous = new GithubAppReviewBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("github-app-review"), first)
                .handle(GithubTestSupport.message(review(correlation))).toCompletableFuture().join();
        assertEquals("ambiguous", ambiguous.outcome());
        assertEquals("RATE_LIMITED", GithubValues.object(ambiguous.payload()).get("reason"));
        assertEquals(1, first.requests.stream().filter(request -> request.method().equals("POST")).count());

        var premature = new GithubTestSupport.HttpHarness();
        NodeResult replay = new GithubAppReviewBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("github-app-review"), premature)
                .handle(GithubTestSupport.message(review(correlation))).toCompletableFuture().join();
        assertEquals("ambiguous", replay.outcome()); assertTrue(premature.requests.isEmpty());

        clock.advance(30_000);
        String marker = reviewMarker(correlation);
        var recovered = new GithubTestSupport.HttpHarness().reply(200, repository())
                .reply(200, List.of(reviewObject(74, "PENDING", marker)))
                .reply(200, pull(GithubTestSupport.SHA))
                .reply(200, reviewObject(74, "APPROVED", marker)).reply(200, pull(GithubTestSupport.SHA));
        NodeResult completed = new GithubAppReviewBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("github-app-review"), recovered)
                .handle(GithubTestSupport.message(review(correlation))).toCompletableFuture().join();
        assertEquals("continue", completed.outcome());
        assertEquals(1, recovered.requests.stream().filter(request -> request.method().equals("POST")).count());
        assertTrue(recovered.requests.stream().filter(request -> request.method().equals("POST"))
                .allMatch(request -> request.destination().getPath().endsWith("/events")));
    }

    @Test void projectCommentDiscoveryRateLimitWaitsDurablyBeforePosting() {
        Path path = directory.resolve("comment-rate.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        Map<String, Object> input = new java.util.LinkedHashMap<>(project("comment-rate"));
        input.put("comment", Map.of("kind", "claim", "body", "Claim after retry."));
        var limited = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8))
                .reply(429, Map.of("retry-after", List.of("1")), Map.of());
        NodeResult waiting = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), limited)
                .handle(GithubTestSupport.message(Map.copyOf(input))).toCompletableFuture().join();
        assertEquals("waiting", GithubValues.object(waiting.payload()).get("status"));
        var premature = new GithubTestSupport.HttpHarness();
        NodeResult replay = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), premature)
                .handle(GithubTestSupport.message(Map.copyOf(input))).toCompletableFuture().join();
        assertEquals(waiting.payload(), replay.payload()); assertTrue(premature.requests.isEmpty());
        clock.advance(2_000);
        String marker = "<!-- ravenroot-project-transition:" + GithubValues.sha256("1234:ITEM_1:ISSUE_1:8:claim:"
                + GithubValues.sha256("Claim after retry.")).substring(0, 32) + " -->";
        var recovered = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8))
                .reply(200, List.of()).reply(201, Map.of("id", 75L, "body", "Claim after retry.\n\n" + marker,
                        "user", Map.of("login", "example-reviewer[bot]")));
        NodeResult completed = new ProjectTransitionBehavior(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock))
                .create(GithubTestSupport.node("project-transition"), recovered)
                .handle(GithubTestSupport.message(Map.copyOf(input))).toCompletableFuture().join();
        assertEquals("continue", completed.outcome());
        assertEquals(1, recovered.requests.stream().filter(request -> request.method().equals("POST")
                && request.destination().getPath().endsWith("/comments")).count());
    }

    @Test void projectLostCasNeverMutatesOrIncrementsAttempts() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var http = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 3, 8));
        NodeResult result = action(nodePackage, "project-transition", http).handle(GithubTestSupport.message(Map.of(
                "version", "github.project-transition.v1", "itemId", "ITEM_1", "fromStatus", "Todo",
                "toStatus", "InProgress", "expectedGeneration", 7L, "expectedAttempts", 2L,
                "correlationId", "claim-2"))).toCompletableFuture().join();
        assertEquals("conflict", result.outcome());
        assertEquals(1, http.requests.size());
        assertEquals(3L, GithubValues.object(result.payload()).get("attempts"));
    }

    @Test void projectRejectsWrongRepositoryAndTruncatedFieldSnapshot() {
        Map<String, Object> wrongRepository = snapshot("Todo", 2, 7, 9999, false);
        var wrong = new GithubTestSupport.HttpHarness().reply(200, wrongRepository);
        var rejected = action(GithubTestSupport.nodePackage(directory.resolve("wrong.db")),
                "project-transition", wrong).handle(GithubTestSupport.message(project("repo-check")))
                .toCompletableFuture();
        assertEquals(GithubException.Code.FORBIDDEN, githubFailure(rejected).code());
        assertEquals(1, wrong.requests.size());

        var truncated = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7, 1234, true));
        var incomplete = action(GithubTestSupport.nodePackage(directory.resolve("truncated.db")),
                "project-transition", truncated).handle(GithubTestSupport.message(project("page-check")))
                .toCompletableFuture();
        assertEquals(GithubException.Code.RESPONSE_INVALID, githubFailure(incomplete).code());
        assertEquals(1, truncated.requests.size());
    }

    @Test void projectReconcilesPartialMutationResponseWithoutRedispatch() {
        var http = new GithubTestSupport.HttpHarness()
                .reply(200, snapshot("Todo", 2, 7))
                .reply(200, Map.of("data", Map.of("status", Map.of("clientMutationId", "x")),
                        "errors", List.of(Map.of("message", "alias failed"))))
                .reply(200, snapshot("InProgress", 3, 8));
        NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("partial.db")),
                "project-transition", http).handle(GithubTestSupport.message(project("partial")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals("applied", GithubValues.object(result.payload()).get("status"));
        assertEquals(3, http.requests.size());
    }

    @Test void projectCompletesEverySafeSerialPrefixWithAbsoluteAttemptsOnce() {
        List<Map<String, Object>> prefixes = List.of(snapshot("Todo", 2, 7),
                snapshot("InProgress", 2, 7), snapshot("InProgress", 3, 7));
        for (int prefix = 0; prefix < prefixes.size(); prefix++) {
            var http = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7))
                    .reply(200, Map.of("data", Map.of(), "errors", List.of(Map.of("message", "partial"))))
                    .reply(200, prefixes.get(prefix))
                    .reply(200, Map.of("data", Map.of("generation", Map.of("clientMutationId", "x"))))
                    .reply(200, snapshot("InProgress", 3, 8));
            NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("prefix-" + prefix + ".db")),
                    "project-transition", http).handle(GithubTestSupport.message(project("prefix-" + prefix)))
                    .toCompletableFuture().join();
            assertEquals("continue", result.outcome());
            assertEquals(3L, GithubValues.object(result.payload()).get("attempts"));
            assertEquals(2, http.requests.stream().filter(request -> new String(request.body(), StandardCharsets.UTF_8)
                    .contains("mutation(")).count());
        }
    }

    @Test void projectSnapshotFailureAfterMutationPersistsAmbiguityAndReconcilesOnRestart() {
        Path path = directory.resolve("project-reconcile-restart.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        var firstHttp = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7))
                .reply(200, Map.of("data", Map.of("generation", Map.of("clientMutationId", "x"))))
                .reply(500, Map.of("message", "snapshot unavailable"));
        NodeResult first = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "project-transition", firstHttp).handle(GithubTestSupport.message(project("snapshot-restart")))
                .toCompletableFuture().join();
        assertEquals("ambiguous", first.outcome());
        clock.advance(30_000);
        var reopenedHttp = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8));
        NodeResult reconciled = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "project-transition", reopenedHttp).handle(GithubTestSupport.message(project("snapshot-restart")))
                .toCompletableFuture().join();
        assertEquals("continue", reconciled.outcome());
        assertEquals(1, reopenedHttp.requests.size());
    }

    @Test void projectOwnershipLossAfterSnapshotPreventsMutationDispatch() {
        Path path = directory.resolve("project-takeover.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration base = GithubTestSupport.configuration(path);
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 100, 24, 100);
        var firstStore = new SqliteGithubOperationStore(policy, clock);
        GithubNodePackage nodePackage = new GithubNodePackage(
                new GithubConfiguration(base.authority(), base.projection(), policy, base.profiles()), firstStore);
        Map<String, Object> input = project("takeover");
        String digest = GithubValues.sha256(GithubValues.jsonBytes(input));
        var http = new GithubTestSupport.HttpHarness().reply(200, snapshot("Todo", 2, 7));
        http.onRequest = () -> {
            clock.advance(101);
            new SqliteGithubOperationStore(policy, clock).begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE,
                    "project-transition", projectKey("ITEM_1"), digest, 123,
                    GithubOperationStore.BeginPolicy.project(7));
        };
        CompletableFuture<NodeResult> result = action(nodePackage, "project-transition", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertEquals(GithubException.Code.CAS_LOST, githubFailure(result).code());
        assertEquals(1, http.requests.size());
    }

    @Test void transitionCommentLostResponseReconcilesAfterRestartWithoutDuplicatePost() {
        Path path = directory.resolve("project-comment.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        var store = new SqliteGithubOperationStore(configuration.store(), clock);
        Map<String, Object> input = new java.util.LinkedHashMap<>(project("comment-restart"));
        input.put("comment", Map.of("kind", "claim", "body", "Claimed for bounded processing."));
        var firstHttp = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8))
                .reply(200, List.of()).reply(500, Map.of("message", "unknown"))
                .reply(200, List.of());
        NodeResult ambiguous = action(new GithubNodePackage(configuration, store), "project-transition", firstHttp)
                .handle(GithubTestSupport.message(Map.copyOf(input))).toCompletableFuture().join();
        assertEquals("ambiguous", ambiguous.outcome());
        assertEquals(1, firstHttp.requests.stream().filter(request -> request.method().equals("POST")
                && request.destination().getPath().endsWith("/issues/7/comments")).count());

        clock.advance(30_000);
        String marker = "<!-- ravenroot-project-transition:" + GithubValues.sha256("1234:ITEM_1:ISSUE_1:8:claim:"
                + GithubValues.sha256("Claimed for bounded processing.")).substring(0, 32) + " -->";
        String originalPost = firstHttp.requests.stream().filter(request -> request.method().equals("POST")
                && request.destination().getPath().endsWith("/issues/7/comments")).findFirst()
                .map(request -> new String(request.body(), StandardCharsets.UTF_8)).orElseThrow();
        assertTrue(originalPost.contains(marker), originalPost);
        Map<String, Object> landed = Map.of("id", 77L,
                "body", "Claimed for bounded processing.\n\n" + marker,
                "user", Map.of("login", "example-reviewer[bot]"));
        var restartedHttp = new GithubTestSupport.HttpHarness().reply(200, snapshot("InProgress", 3, 8))
                .reply(200, List.of(landed));
        NodeResult reconciled = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "project-transition", restartedHttp).handle(GithubTestSupport.message(Map.copyOf(input)))
                .toCompletableFuture().join();
        assertEquals("continue", reconciled.outcome(), restartedHttp.requests.stream()
                .map(request -> request.method() + " " + request.destination() + " "
                        + new String(request.body(), StandardCharsets.UTF_8)).toList().toString());
        assertEquals(0, restartedHttp.requests.stream().filter(request -> request.method().equals("POST")
                && request.destination().getPath().endsWith("/issues/7/comments")).count());
    }

    @Test void staleReviewHeadProducesNoReviewMutation() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var http = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull("f".repeat(40)));
        NodeResult result = action(nodePackage, "github-app-review", http).handle(GithubTestSupport.message(review("review-stale")))
                .toCompletableFuture().join();
        assertEquals("stale", result.outcome());
        assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test void dismissedAndFutureReviewStatesAreAuthoritativeConflictsWithZeroPosts() {
        for (String state : List.of("DISMISSED", "FUTURE_PROVIDER_STATE")) {
            String correlation = "state-" + state.toLowerCase(java.util.Locale.ROOT);
            var http = new GithubTestSupport.HttpHarness().reply(200, repository())
                    .reply(200, List.of(reviewObject(81, state, reviewMarker(correlation))))
                    .reply(200, pull(GithubTestSupport.SHA));
            NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve(correlation + ".db")),
                    "github-app-review", http).handle(GithubTestSupport.message(review(correlation)))
                    .toCompletableFuture().join();
            assertEquals("conflict", result.outcome(), state);
            assertEquals("REVIEW_STATE_CONFLICT", GithubValues.object(result.payload()).get("reason"));
            assertEquals(0, http.requests.stream().filter(request -> request.method().equals("POST")).count(), state);
        }
    }

    @Test void pendingReviewIsRecoveredAndSubmittedOnlyWhileCommitIsStillHead() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        String marker = "<!-- ravenroot-review:" + GithubValues.sha256("review-recover:" + GithubTestSupport.SHA
                + ":APPROVE:" + GithubValues.sha256("Looks good")).substring(0, 32) + " -->";
        Map<String, Object> pending = reviewObject(99, "PENDING", marker);
        var http = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of(pending))
                .reply(200, pull(GithubTestSupport.SHA)).reply(200, reviewObject(99, "APPROVED", marker))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult result = action(nodePackage, "github-app-review", http).handle(GithubTestSupport.message(review("review-recover")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals(List.of("GET", "GET", "GET", "POST", "GET"),
                http.requests.stream().map(value -> value.method()).toList());
    }

    @Test void ambiguousReviewIsReacquiredAfterRestartAndReconciledWithoutMutation() {
        Path path = directory.resolve("review-restart.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        Map<String, Object> input = review("review-restart");
        Map<String, Object> canonical = Map.of("version", "github.app-review.v1", "pullNumber", 7L,
                "commit", GithubTestSupport.SHA, "verdict", "APPROVE",
                "bodyDigest", GithubValues.sha256("Looks good"), "correlationId", "review-restart");
        var store = new SqliteGithubOperationStore(configuration.store(), clock);
        var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-app-review",
                reviewKey("review-restart"),
                GithubValues.sha256(GithubValues.jsonBytes(canonical)), 123,
                GithubOperationStore.BeginPolicy.ordinary());
        store.save(lease, "AMBIGUOUS", 0, 0, 123, "99", "a".repeat(64),
                "{\"status\":\"ambiguous\"}", true);
        clock.advance(5_001);
        String marker = "<!-- ravenroot-review:" + GithubValues.sha256("review-restart:" + GithubTestSupport.SHA
                + ":APPROVE:" + GithubValues.sha256("Looks good")).substring(0, 32) + " -->";
        var http = new GithubTestSupport.HttpHarness().reply(200, repository())
                .reply(200, List.of(reviewObject(99, "APPROVED", marker)))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult result = action(new GithubNodePackage(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock)), "github-app-review", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals("already-recorded", GithubValues.object(result.payload()).get("status"));
        assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test void malformedDraftResponseBecomesAmbiguousThenSafelyReattemptsAfterGrace() {
        Path path = directory.resolve("review-malformed.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        Map<String, Object> input = review("review-malformed");
        var firstHttp = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull(GithubTestSupport.SHA)).reply(201, Map.of()).reply(200, List.of());
        NodeResult first = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "github-app-review", firstHttp).handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("ambiguous", first.outcome());
        assertEquals(1, firstHttp.requests.stream().filter(request -> request.method().equals("POST")).count());

        clock.advance(30_000);
        String marker = reviewMarker("review-malformed");
        var restarted = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull(GithubTestSupport.SHA)).reply(201, reviewObject(88, "PENDING", marker))
                .reply(200, pull(GithubTestSupport.SHA)).reply(200, reviewObject(88, "APPROVED", marker))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult completed = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "github-app-review", restarted).handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("continue", completed.outcome());
        assertEquals(2, restarted.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test void everyUncertainDraftResponseIsClassifiedAndReconciledWithoutImmediateDuplicate() {
        for (String variant : List.of("http-500", "malformed-201", "rate-429")) {
            var http = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                    .reply(200, pull(GithubTestSupport.SHA));
            if (variant.equals("http-500")) http.reply(500, Map.of("message", "unknown"));
            else if (variant.equals("malformed-201")) http.reply(201, Map.of());
            else http.reply(429, Map.of("retry-after", List.of("2")), Map.of("message", "limited"));
            http.reply(200, List.of());
            NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("draft-" + variant + ".db")),
                    "github-app-review", http).handle(GithubTestSupport.message(review("draft-" + variant)))
                    .toCompletableFuture().join();
            assertEquals("ambiguous", result.outcome(), variant);
            assertEquals(1, http.requests.stream().filter(request -> request.method().equals("POST")).count(), variant);
            assertEquals(variant.equals("rate-429") ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN",
                    GithubValues.object(result.payload()).get("reason"), variant);
        }
    }

    @Test void lostSubmitResponseReconcilesLandedFormalReviewWithoutSecondSubmit() {
        String marker = reviewMarker("review-submit-lost");
        var http = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull(GithubTestSupport.SHA)).reply(201, reviewObject(91, "PENDING", marker))
                .reply(200, pull(GithubTestSupport.SHA)).reply(500, Map.of("message", "unknown"))
                .reply(200, List.of(reviewObject(91, "APPROVED", marker)))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("review-submit-lost.db")),
                "github-app-review", http).handle(GithubTestSupport.message(review("review-submit-lost")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals(2, http.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test void everyUncertainSubmitResponseIsClassifiedAndReconciledWithoutSecondSubmit() {
        for (String variant : List.of("http-500", "malformed-200", "rate-429")) {
            String correlation = "submit-" + variant;
            String marker = reviewMarker(correlation);
            var http = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                    .reply(200, pull(GithubTestSupport.SHA)).reply(201, reviewObject(92, "PENDING", marker))
                    .reply(200, pull(GithubTestSupport.SHA));
            if (variant.equals("http-500")) http.reply(500, Map.of("message", "unknown"));
            else if (variant.equals("malformed-200")) http.reply(200, Map.of());
            else http.reply(429, Map.of("retry-after", List.of("2")), Map.of("message", "limited"));
            http.reply(200, List.of(reviewObject(92, "PENDING", marker)));
            NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("submit-" + variant + ".db")),
                    "github-app-review", http).handle(GithubTestSupport.message(review(correlation)))
                    .toCompletableFuture().join();
            assertEquals("ambiguous", result.outcome(), variant);
            assertEquals(2, http.requests.stream().filter(request -> request.method().equals("POST")).count(), variant);
            assertEquals(variant.equals("rate-429") ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN",
                    GithubValues.object(result.payload()).get("reason"), variant);
        }
    }

    @Test void expiredReviewWriterCannotBeTakenOverDuringOutboundUncertaintyWindow() {
        Path path = directory.resolve("review-takeover.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        var store = new SqliteGithubOperationStore(configuration.store(), clock);
        Map<String, Object> input = review("review-takeover");
        Map<String, Object> canonical = Map.of("version", "github.app-review.v1", "pullNumber", 7L,
                "commit", GithubTestSupport.SHA, "verdict", "APPROVE",
                "bodyDigest", GithubValues.sha256("Looks good"), "correlationId", "review-takeover");
        store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-app-review",
                reviewKey("review-takeover"),
                GithubValues.sha256(GithubValues.jsonBytes(canonical)), clock.millis() + 5_000,
                GithubOperationStore.BeginPolicy.forAmbiguousReconciliation(5_000));
        clock.advance(1_100);
        var http = new GithubTestSupport.HttpHarness();
        CompletableFuture<NodeResult> takeover = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "github-app-review", http).handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertEquals(GithubException.Code.CAPACITY, githubFailure(takeover).code());
        assertTrue(http.requests.isEmpty(), "a possibly in-flight draft must not be followed by another mutation");
    }

    @Test void inFlightDraftPostCannotBeDuplicatedAfterLeaseExpiry() {
        Path path = directory.resolve("review-live-takeover.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        var store = new SqliteGithubOperationStore(configuration.store(), clock);
        var firstHttp = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of())
                .reply(200, pull(GithubTestSupport.SHA));
        CompletableFuture<OutboundHttpResponse> blocked = new CompletableFuture<>();
        firstHttp.pending = new OutboundCall<>() {
            @Override public CompletableFuture<OutboundHttpResponse> completion() { return blocked; }
            @Override public boolean cancel() { return blocked.cancel(true); }
        };
        firstHttp.pendingAtRequest = 4;
        CompletableFuture<NodeResult> first = action(new GithubNodePackage(configuration, store),
                "github-app-review", firstHttp).handle(GithubTestSupport.message(review("live-takeover")))
                .toCompletableFuture();
        awaitRequests(firstHttp, 4);
        assertEquals(1, firstHttp.requests.stream().filter(request -> request.method().equals("POST")).count());

        clock.advance(1_100);
        var secondHttp = new GithubTestSupport.HttpHarness().reply(200, repository()).reply(200, List.of());
        CompletableFuture<NodeResult> second = action(new GithubNodePackage(configuration,
                        new SqliteGithubOperationStore(configuration.store(), clock)),
                "github-app-review", secondHttp).handle(GithubTestSupport.message(review("live-takeover")))
                .toCompletableFuture();
        assertEquals(GithubException.Code.CAPACITY, githubFailure(second).code());
        assertEquals(0, secondHttp.requests.stream().filter(request -> request.method().equals("POST")).count());
        blocked.complete(new OutboundHttpResponse(201, Map.of(),
                GithubTestSupport.json(reviewObject(93, "PENDING", reviewMarker("live-takeover")))));
        githubFailure(first); // join the first worker before TempDir cleanup
    }

    @Test void workflowUsesExactShaAndNewestAttempts() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var runs = List.of(run(1001, 11, 1, "completed", "failure"),
                run(1001, 12, 2, "completed", "success"), run(1002, 13, 1, "completed", "success"));
        var http = new GithubTestSupport.HttpHarness().reply(200, Map.of("total_count", 3L, "workflow_runs", runs));
        NodeResult result = action(nodePackage, "github-workflow-watch", http).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", System.currentTimeMillis() + 2_000L, "correlationId", "watch-1")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertTrue(http.requests.getFirst().destination().getQuery().contains("head_sha=" + GithubTestSupport.SHA));
        @SuppressWarnings("unchecked") List<Map<String, Object>> selected = (List<Map<String, Object>>) GithubValues.object(result.payload()).get("workflows");
        assertEquals(Set.of(12L, 13L), selected.stream().map(value -> (Long) value.get("runId")).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void workflowFailsClosedWhenExactCommitResultsRequirePagination() {
        var http = new GithubTestSupport.HttpHarness().reply(200,
                Map.of("total_count", 101L, "workflow_runs", List.of()));
        var result = action(GithubTestSupport.nodePackage(directory.resolve("workflow-page.db")),
                "github-workflow-watch", http).handle(GithubTestSupport.message(Map.of(
                        "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                        "deadlineEpochMs", System.currentTimeMillis() + 2_000L,
                        "correlationId", "watch-page"))).toCompletableFuture();
        assertEquals(GithubException.Code.RESPONSE_INVALID, githubFailure(result).code());
        assertEquals(1, http.requests.size());
    }

    @Test void workflowRestoresPersistedRunsAfterStoreReopen() {
        Path path = directory.resolve("workflow-restart.db");
        long deadline = System.currentTimeMillis() + 5_000L;
        Map<String, Object> input = Map.of("version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", deadline, "correlationId", "watch-restart");
        var store = new SqliteGithubOperationStore(GithubTestSupport.configuration(path).store());
        var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-workflow-watch",
                workflowKey("watch-restart"), GithubValues.sha256(GithubValues.jsonBytes(input)), deadline,
                GithubOperationStore.BeginPolicy.ordinary());
        Map<String, Object> waiting = Map.of("version", "github.workflow-watch.result.v1", "status", "waiting",
                "commit", GithubTestSupport.SHA, "polls", 1L, "attempts", 1L, "generation", 0L,
                "remoteId", GithubTestSupport.SHA, "retryAtEpochMs", System.currentTimeMillis(),
                "workflows", List.of(Map.of("workflowId", 1001L, "runId", 11L, "runNumber", 11L,
                        "attempt", 1L, "createdAt", "2026-09-04T00:00:00Z", "status", "completed",
                        "conclusion", "success")));
        store.save(lease, "WAITING", 0, 1, deadline, GithubTestSupport.SHA, "a".repeat(64),
                new String(GithubValues.jsonBytes(waiting), StandardCharsets.UTF_8), false);
        store.release(lease);
        var http = new GithubTestSupport.HttpHarness().reply(200, Map.of("total_count", 1L, "workflow_runs",
                List.of(run(1002, 13, 1, "completed", "success"))));
        NodeResult result = action(GithubTestSupport.nodePackage(path), "github-workflow-watch", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals(2L, GithubValues.object(result.payload()).get("polls"));
        assertEquals(1, http.requests.size());
    }

    @Test void workflowRestoresFutureRateDeadlineReleasesPermitAndPersistsCancellation() throws Exception {
        Path path = directory.resolve("workflow-rate-restart.db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = withConcurrency(GithubTestSupport.configuration(path), 1);
        var store = new SqliteGithubOperationStore(configuration.store(), clock);
        long deadline = clock.millis() + 20_000;
        long retryAt = clock.millis() + 5_000;
        Map<String, Object> input = Map.of("version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", deadline, "correlationId", "watch-rate-restart");
        var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-workflow-watch",
                workflowKey("watch-rate-restart"), GithubValues.sha256(GithubValues.jsonBytes(input)),
                deadline, GithubOperationStore.BeginPolicy.ordinary());
        Map<String, Object> waiting = Map.of("version", "github.workflow-watch.result.v1", "status", "waiting",
                "commit", GithubTestSupport.SHA, "polls", 1L, "attempts", 1L, "generation", 0L,
                "remoteId", GithubTestSupport.SHA, "reason", "RATE_LIMITED", "retryAtEpochMs", retryAt,
                "workflows", List.of());
        store.save(lease, "WAITING", 0, 1, retryAt, GithubTestSupport.SHA, "a".repeat(64),
                new String(GithubValues.jsonBytes(waiting), StandardCharsets.UTF_8), false);
        store.release(lease);

        GithubRuntime runtime = new GithubRuntime(configuration, store);
        ManualScheduler scheduler = new ManualScheduler();
        var http = new GithubTestSupport.HttpHarness();
        NodeAction watch = new GithubWorkflowWatchBehavior(runtime, clock, scheduler)
                .create(GithubTestSupport.node("github-workflow-watch"), http);
        CompletableFuture<NodeResult> result = watch.handle(GithubTestSupport.message(input)).toCompletableFuture();
        ManualScheduler.Entry initial = scheduler.take(); assertEquals(0, initial.delayMs); initial.run();
        ManualScheduler.Entry delayed = scheduler.take();
        assertEquals(5_000, delayed.delayMs);
        assertTrue(http.requests.isEmpty(), "restart must not call GitHub before persisted Retry-After");

        GithubProfile profile = configuration.profile(GithubTestSupport.TENANT, GithubTestSupport.PROFILE).orElseThrow();
        NodeResult probe = runtime.submit(GithubTestSupport.message(Map.of()), http, profile, "probe", "probe",
                Map.of("probe", "permit"), deadline, (api, operation, control) -> NodeResult.continueWith(Map.of(
                        "status", "done", "generation", 0L, "attempts", 0L, "remoteId", "probe")))
                .toCompletableFuture().join();
        assertEquals("continue", probe.outcome(), "waiting watch must release its profile permit");

        assertTrue(result.cancel(true));
        awaitState(store, "github-workflow-watch", workflowKey("watch-rate-restart"), "CANCELLED");
        ManualScheduler replayScheduler = new ManualScheduler();
        CompletableFuture<NodeResult> replay = new GithubWorkflowWatchBehavior(runtime, clock, replayScheduler)
                .create(GithubTestSupport.node("github-workflow-watch"), http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture();
        replayScheduler.take().run();
        assertEquals(GithubException.Code.CANCELLED, githubFailure(replay).code());
        assertTrue(http.requests.isEmpty());
    }

    @Test void sameWorkflowIdentityAdvancesAfterReopenToSuccessFailureAndCancellation() {
        Map<String, String> expected = Map.of("success", "continue", "failure", "failed", "cancelled", "failed");
        expected.forEach((conclusion, outcome) -> {
            Path path = directory.resolve("workflow-advance-" + conclusion + ".db");
            long deadline = System.currentTimeMillis() + 5_000;
            Map<String, Object> input = Map.of("version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                    "deadlineEpochMs", deadline, "correlationId", "advance-" + conclusion);
            var store = new SqliteGithubOperationStore(GithubTestSupport.configuration(path).store());
            var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-workflow-watch",
                    workflowKey("advance-" + conclusion),
                    GithubValues.sha256(GithubValues.jsonBytes(input)), deadline,
                    GithubOperationStore.BeginPolicy.ordinary());
            Map<String, Object> waiting = Map.of("version", "github.workflow-watch.result.v1", "status", "waiting",
                    "commit", GithubTestSupport.SHA, "polls", 1L, "attempts", 1L, "generation", 0L,
                    "remoteId", GithubTestSupport.SHA, "retryAtEpochMs", System.currentTimeMillis(),
                    "workflows", List.of(runOutput(1001, 11, 1, "queued", ""),
                            runOutput(1002, 12, 1, "completed", "success")));
            store.save(lease, "WAITING", 0, 1, deadline, GithubTestSupport.SHA, "a".repeat(64),
                    new String(GithubValues.jsonBytes(waiting), StandardCharsets.UTF_8), false);
            store.release(lease);
            var http = new GithubTestSupport.HttpHarness().reply(200, Map.of("total_count", 1L,
                    "workflow_runs", List.of(run(1001, 11, 1, "completed", conclusion))));
            NodeResult result = action(GithubTestSupport.nodePackage(path), "github-workflow-watch", http)
                    .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
            assertEquals(outcome, result.outcome());
            assertEquals(1, http.requests.size());
        });
    }

    @Test void contradictoryTerminalConclusionForSameRunAttemptFailsClosed() {
        Path path = directory.resolve("workflow-terminal-contradiction.db");
        long deadline = System.currentTimeMillis() + 5_000;
        Map<String, Object> input = Map.of("version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", deadline, "correlationId", "terminal-contradiction");
        var store = new SqliteGithubOperationStore(GithubTestSupport.configuration(path).store());
        var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-workflow-watch",
                workflowKey("terminal-contradiction"), GithubValues.sha256(GithubValues.jsonBytes(input)),
                deadline, GithubOperationStore.BeginPolicy.ordinary());
        Map<String, Object> waiting = Map.of("version", "github.workflow-watch.result.v1", "status", "waiting",
                "commit", GithubTestSupport.SHA, "polls", 1L, "attempts", 1L, "generation", 0L,
                "remoteId", GithubTestSupport.SHA, "retryAtEpochMs", System.currentTimeMillis(),
                "workflows", List.of(runOutput(1001, 11, 1, "completed", "success")));
        store.save(lease, "WAITING", 0, 1, deadline, GithubTestSupport.SHA, "a".repeat(64),
                new String(GithubValues.jsonBytes(waiting), StandardCharsets.UTF_8), false);
        store.release(lease);
        var http = new GithubTestSupport.HttpHarness().reply(200, Map.of("total_count", 1L,
                "workflow_runs", List.of(run(1001, 11, 1, "completed", "failure"))));
        CompletableFuture<NodeResult> result = action(GithubTestSupport.nodePackage(path), "github-workflow-watch", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertEquals(GithubException.Code.RESPONSE_INVALID, githubFailure(result).code());
        assertEquals(1, http.requests.size());
    }

    @Test @Timeout(5) void workflowDeadlineAndRateLimitRemainBounded() throws Exception {
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(directory.resolve("workflow-deadline.db"));
        GithubRuntime runtime = new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock);
        var noCalls = new GithubTestSupport.HttpHarness();
        ManualScheduler expiredScheduler = new ManualScheduler();
        CompletableFuture<NodeResult> expiredResult = new GithubWorkflowWatchBehavior(runtime, clock, expiredScheduler)
                .create(GithubTestSupport.node("github-workflow-watch"), noCalls).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", clock.millis() - 1L, "correlationId", "watch-expired"))).toCompletableFuture();
        expiredScheduler.take().run();
        NodeResult expired = expiredResult.join();
        assertEquals("timeout", expired.outcome());
        assertTrue(noCalls.requests.isEmpty());

        var rate = new GithubTestSupport.HttpHarness().reply(429, Map.of("retry-after", List.of("10")), Map.of("message", "limited"));
        ManualScheduler rateScheduler = new ManualScheduler();
        CompletableFuture<NodeResult> rateResult = new GithubWorkflowWatchBehavior(runtime, clock, rateScheduler)
                .create(GithubTestSupport.node("github-workflow-watch"), rate).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", clock.millis() + 20L, "correlationId", "watch-rate"))).toCompletableFuture();
        rateScheduler.take().run();
        ManualScheduler.Entry deadlinePoll = rateScheduler.take();
        assertEquals(20, deadlinePoll.delayMs);
        clock.advance(20);
        deadlinePoll.run();
        NodeResult limited = rateResult.join();
        assertEquals("timeout", limited.outcome());
        assertEquals(1, rate.requests.size());
    }

    @Test void cancellingWorkflowCancelsTheManagedCall() throws Exception {
        Path store = directory.resolve("cancel.db");
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(store);
        var http = new GithubTestSupport.HttpHarness();
        CompletableFuture<OutboundHttpResponse> completion = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        http.pending = new OutboundCall<>() {
            @Override public CompletableFuture<OutboundHttpResponse> completion() { return completion; }
            @Override public boolean cancel() { cancelled.set(true); return completion.cancel(true); }
        };
        Map<String, Object> input = Map.of("version", "github.workflow-watch.v1",
                "commit", GithubTestSupport.SHA, "deadlineEpochMs", System.currentTimeMillis() + 2_000L,
                "correlationId", "watch-cancel");
        CompletableFuture<NodeResult> result = action(nodePackage, "github-workflow-watch", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertTrue(http.requestArrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(result.cancel(true));
        assertTrue(cancelled.get());
        assertEquals(GithubException.Code.CANCELLED, githubFailure(result).code());
        var replayHttp = new GithubTestSupport.HttpHarness();
        CompletableFuture<NodeResult> replay = action(GithubTestSupport.nodePackage(store),
                "github-workflow-watch", replayHttp).handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertEquals(GithubException.Code.CANCELLED, githubFailure(replay).code());
        assertTrue(replayHttp.requests.isEmpty(), "observed cancellation must already be durable after reopen");
    }

    @Test void releasePreparationIsReadOnlyAndTerminalResultReplaysAfterRestart() {
        Path store = directory.resolve("operations.db");
        var http = releaseReplies();
        Object input = Map.of("version", "github.release-prepare.v1", "commit", GithubTestSupport.SHA,
                "releaseKind", "minor", "correlationId", "release-1");
        NodeResult first = action(GithubTestSupport.nodePackage(store), "release-prepare", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("continue", first.outcome());
        assertEquals("0.2.0", GithubValues.object(first.payload()).get("nextVersion"));
        assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")));
        http.requests.forEach(request -> {
            var credential = request.credential().orElseThrow();
            assertEquals("github-installation", credential.bindingId());
            assertEquals("github-installation-token", credential.reference());
            assertEquals("api.github.com", request.destination().getHost());
        });

        var restartedHttp = new GithubTestSupport.HttpHarness();
        NodeResult replay = action(GithubTestSupport.nodePackage(store), "release-prepare", restartedHttp)
                .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals(first.payload(), replay.payload());
        assertTrue(restartedHttp.requests.isEmpty());
    }

    @Test void releasePreparationAcceptsMinorForPreOneBreakingFragment() {
        var http = releaseReplies("0.1.0", "114.breaking.md");
        NodeResult result = action(GithubTestSupport.nodePackage(directory.resolve("release-breaking.db")),
                "release-prepare", http).handle(GithubTestSupport.message(Map.of(
                        "version", "github.release-prepare.v1", "commit", GithubTestSupport.SHA,
                        "releaseKind", "minor", "correlationId", "release-breaking")))
                .toCompletableFuture().join();
        assertEquals("0.2.0", GithubValues.object(result.payload()).get("nextVersion"));
        assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test void releasePreparationRejectsNonCanonicalSemver() {
        for (String version : List.of("01.2.3", "1.2.3-01", "1.2.3-alpha.01")) {
            var http = releaseReplies(version, "114.feature.md");
            GithubException failure = githubFailure(action(GithubTestSupport.nodePackage(directory.resolve(
                            "release-invalid-" + version.replace('.', '-') + ".db")), "release-prepare", http)
                    .handle(GithubTestSupport.message(Map.of("version", "github.release-prepare.v1",
                            "commit", GithubTestSupport.SHA, "releaseKind", "minor",
                            "correlationId", "invalid-" + version))).toCompletableFuture());
            assertEquals(GithubException.Code.RESPONSE_INVALID, failure.code());
        }
    }

    @Test void releasePreparationRejectsEveryComponentOverflowStably() {
        Map<String, String> cases = Map.of("major", Long.MAX_VALUE + ".0.0",
                "minor", "1." + Long.MAX_VALUE + ".0", "patch", "1.2." + Long.MAX_VALUE);
        cases.forEach((kind, version) -> {
            String fragmentKind = kind.equals("major") ? "breaking" : kind.equals("minor") ? "feature" : "fix";
            var http = releaseReplies(version, "114." + fragmentKind + ".md");
            CompletableFuture<NodeResult> result = action(GithubTestSupport.nodePackage(
                            directory.resolve("release-overflow-" + kind + ".db")), "release-prepare", http)
                    .handle(GithubTestSupport.message(Map.of("version", "github.release-prepare.v1",
                            "commit", GithubTestSupport.SHA, "releaseKind", kind,
                            "correlationId", "overflow-" + kind))).toCompletableFuture();
            assertEquals(GithubException.Code.RESPONSE_INVALID, githubFailure(result).code(), kind);
            assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")), kind);
        });
    }

    private static NodeAction action(GithubNodePackage nodePackage, String behavior, GithubTestSupport.HttpHarness services) {
        return GithubTestSupport.behavior(nodePackage, behavior).create(GithubTestSupport.node(behavior), services);
    }
    private static Map<String, Object> snapshot(String status, long attempts, long generation) {
        return snapshot(status, attempts, generation, 1234, false);
    }
    private static Map<String, Object> snapshot(String status, long attempts, long generation,
                                                long repositoryId, boolean hasNextPage) {
        return Map.of("data", Map.of("node", Map.of("id", "ITEM_1", "project", Map.of("id", "PVT_example"),
                "content", Map.of("id", "ISSUE_1", "number", 7L,
                        "repository", Map.of("databaseId", repositoryId)),
                "fieldValues", Map.of("pageInfo", Map.of("hasNextPage", hasNextPage), "nodes", List.of(
                        Map.of("field", Map.of("id", "PVTSSF_status"), "optionId", status.equals("Todo") ? "todo-id" : "progress-id"),
                        Map.of("field", Map.of("id", "PVTF_attempts"), "number", attempts),
                        Map.of("field", Map.of("id", "PVTF_generation"), "number", generation))))));
    }
    private static Map<String, Object> project(String correlation) {
        return Map.of("version", "github.project-transition.v1", "itemId", "ITEM_1",
                "fromStatus", "Todo", "toStatus", "InProgress", "expectedGeneration", 7L,
                "expectedAttempts", 2L, "correlationId", correlation);
    }
    private static String projectKey(String item) {
        return "1234:" + GithubValues.keyDigest("project-item", item);
    }
    private static String reviewKey(String correlation) {
        return "7:" + GithubTestSupport.SHA + ":" + GithubValues.keyDigest("review-correlation", correlation);
    }
    private static String workflowKey(String correlation) {
        return GithubTestSupport.SHA + ":" + GithubValues.keyDigest("workflow-correlation", correlation);
    }
    private static Map<String, Object> pull(String sha) {
        return Map.of("base", Map.of("repo", Map.of("id", 1234L)), "head", Map.of("sha", sha));
    }
    private static Map<String, Object> repository() {
        return Map.of("id", 1234L, "full_name", "example/service");
    }
    private static Map<String, Object> review(String correlation) {
        return Map.of("version", "github.app-review.v1", "pullNumber", 7L, "commit", GithubTestSupport.SHA,
                "verdict", "APPROVE", "body", "Looks good", "correlationId", correlation);
    }
    private static Map<String, Object> reviewObject(long id, String state, String marker) {
        return Map.of("id", id, "state", state, "commit_id", GithubTestSupport.SHA,
                "body", "Looks good\n\n" + marker, "user", Map.of("login", "example-reviewer[bot]"));
    }
    private static String reviewMarker(String correlation) {
        return "<!-- ravenroot-review:" + GithubValues.sha256(correlation + ":" + GithubTestSupport.SHA
                + ":APPROVE:" + GithubValues.sha256("Looks good")).substring(0, 32) + " -->";
    }
    private static Map<String, Object> run(long workflow, long id, long attempt, String status, String conclusion) {
        return Map.of("workflow_id", workflow, "id", id, "run_number", id, "run_attempt", attempt,
                "created_at", "2026-09-04T00:00:00Z", "head_sha", GithubTestSupport.SHA,
                "status", status, "conclusion", conclusion);
    }
    private static Map<String, Object> runOutput(long workflow, long id, long attempt,
                                                 String status, String conclusion) {
        return Map.of("workflowId", workflow, "runId", id, "runNumber", id, "attempt", attempt,
                "createdAt", "2026-09-04T00:00:00Z", "status", status, "conclusion", conclusion);
    }
    private static GithubTestSupport.HttpHarness releaseReplies() {
        return releaseReplies("0.1.0", "114.feature.md");
    }
    private static GithubTestSupport.HttpHarness releaseReplies(String projectVersion, String fragmentName) {
        String version = Base64.getEncoder().encodeToString(("<?xml version=\"1.0\"?><project>"
                + "<modelVersion>4.0.0</modelVersion><groupId>ai.ravenroot</groupId>"
                + "<artifactId>ravenroot-parent</artifactId><version>" + projectVersion + "</version></project>")
                .getBytes(StandardCharsets.UTF_8));
        String fragment = Base64.getEncoder().encodeToString("Add GitHub automation.".getBytes(StandardCharsets.UTF_8));
        return new GithubTestSupport.HttpHarness().reply(200, Map.of("object", Map.of("sha", GithubTestSupport.SHA)))
                .reply(200, Map.of("sha", GithubTestSupport.SHA))
                .reply(200, Map.of("encoding", "base64", "content", version))
                .reply(200, List.of(Map.of("type", "file", "name", fragmentName, "path", ".changes/" + fragmentName)))
                .reply(200, Map.of("encoding", "base64", "content", fragment))
                .reply(200, Map.of("object", Map.of("sha", GithubTestSupport.SHA)));
    }

    private static GithubException githubFailure(CompletableFuture<?> result) {
        java.util.concurrent.CompletionException completion = assertThrows(
                java.util.concurrent.CompletionException.class, result::join);
        assertInstanceOf(GithubException.class, completion.getCause());
        return (GithubException) completion.getCause();
    }

    private static GithubConfiguration withConcurrency(GithubConfiguration base, int concurrency) {
        GithubProfile old = base.profile(GithubTestSupport.TENANT, GithubTestSupport.PROFILE).orElseThrow();
        GithubProfile profile = new GithubProfile(old.name(), old.tenantId(), old.apiOrigin(), old.owner(),
                old.repository(), old.repositoryId(), old.installationId(), old.reviewerLogin(),
                old.credentialBindingId(), old.credentialReference(), old.webhookSecretReference(), old.route(),
                old.webhookEvents(), old.project(), old.workflowIds(), old.release(), old.timeoutMs(),
                old.maxRequestBytes(), old.maxResponseBytes(), concurrency, old.maxPolls(), old.pollIntervalMs());
        return new GithubConfiguration(base.authority(), base.projection(), base.store(),
                Map.of(GithubTestSupport.TENANT + "\u0000" + GithubTestSupport.PROFILE, profile));
    }

    private static void awaitState(GithubOperationStore store, String kind, String key, String state) {
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < limit) {
            if (store.find(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, kind, key)
                    .map(record -> state.equals(record.state())).orElse(false)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
        }
        fail("operation did not reach " + state);
    }

    private static void awaitRequests(GithubTestSupport.HttpHarness http, int count) {
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < limit) {
            if (http.requests.size() >= count) return;
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
        }
        fail("outbound request count did not reach " + count);
    }

    private void assertEffectFreeRateRetry(String kind, RateInvocation invocation,
                                           GithubTestSupport.HttpHarness limited,
                                           GithubTestSupport.HttpHarness recovered) {
        Path path = directory.resolve("rate-" + kind + ".db");
        MutableClock clock = new MutableClock();
        GithubConfiguration configuration = GithubTestSupport.configuration(path);
        String correlation = "rate-" + kind;
        NodeResult waiting = invocation.invoke(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock), limited, correlation).join();
        assertEquals("waiting", GithubValues.object(waiting.payload()).get("status"), kind);
        assertEquals("RATE_LIMITED", GithubValues.object(waiting.payload()).get("reason"), kind);

        var premature = new GithubTestSupport.HttpHarness();
        NodeResult replay = invocation.invoke(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock), premature, correlation).join();
        assertEquals(waiting.payload(), replay.payload(), kind);
        assertTrue(premature.requests.isEmpty(), kind + " retried before its durable rate deadline");

        clock.advance(2_000);
        NodeResult completed = invocation.invoke(new GithubRuntime(configuration,
                new SqliteGithubOperationStore(configuration.store(), clock), clock), recovered, correlation).join();
        assertEquals("continue", completed.outcome(), kind);
        assertFalse(recovered.requests.isEmpty(), kind);
    }

    private static long mutationRequests(GithubTestSupport.HttpHarness http) {
        return http.requests.stream().filter(request -> request.method().equals("POST")
                && new String(request.body(), StandardCharsets.UTF_8).contains("mutation(")).count();
    }

    @FunctionalInterface private interface RateInvocation {
        CompletableFuture<NodeResult> invoke(GithubRuntime runtime, GithubTestSupport.HttpHarness http,
                                             String correlation);
    }

    private static final class ManualScheduler implements GithubWorkflowWatchBehavior.PollScheduler {
        private final java.util.concurrent.BlockingQueue<Entry> entries = new java.util.concurrent.LinkedBlockingQueue<>();
        @Override public GithubWorkflowWatchBehavior.ScheduledPoll schedule(Runnable task, long delayMs) {
            Entry entry = new Entry(task, delayMs); entries.add(entry); return entry::cancel;
        }
        Entry take() throws InterruptedException {
            Entry entry = entries.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(entry, "scheduled poll did not arrive"); return entry;
        }
        private static final class Entry {
            private final Runnable task; private final long delayMs; private boolean cancelled;
            Entry(Runnable task, long delayMs) { this.task = task; this.delayMs = delayMs; }
            void run() { if (!cancelled) task.run(); }
            void cancel() { cancelled = true; }
        }
    }

    private static final class MutableClock extends java.time.Clock {
        private long millis = System.currentTimeMillis();
        void advance(long amount) { millis += amount; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneId.of("UTC"); }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

}
