package ai.ravenroot.extensions.github;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;
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
                    "project-transition", "1234:ITEM_1", digest, 123,
                    GithubOperationStore.BeginPolicy.project(7));
        };
        CompletableFuture<NodeResult> result = action(nodePackage, "project-transition", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture();
        assertEquals(GithubException.Code.CAS_LOST, githubFailure(result).code());
        assertEquals(1, http.requests.size());
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
        Map<String, Object> input = review("review-restart");
        Map<String, Object> canonical = Map.of("version", "github.app-review.v1", "pullNumber", 7L,
                "commit", GithubTestSupport.SHA, "verdict", "APPROVE",
                "bodyDigest", GithubValues.sha256("Looks good"), "correlationId", "review-restart");
        var store = new SqliteGithubOperationStore(GithubTestSupport.configuration(path).store());
        var lease = store.begin(GithubTestSupport.TENANT, GithubTestSupport.PROFILE, "github-app-review",
                "7:" + GithubTestSupport.SHA + ":review-restart",
                GithubValues.sha256(GithubValues.jsonBytes(canonical)), 123,
                GithubOperationStore.BeginPolicy.ordinary());
        store.save(lease, "AMBIGUOUS", 0, 0, 123, "99", "a".repeat(64),
                "{\"status\":\"ambiguous\"}", true);
        String marker = "<!-- ravenroot-review:" + GithubValues.sha256("review-restart:" + GithubTestSupport.SHA
                + ":APPROVE:" + GithubValues.sha256("Looks good")).substring(0, 32) + " -->";
        var http = new GithubTestSupport.HttpHarness().reply(200, repository())
                .reply(200, List.of(reviewObject(99, "APPROVED", marker)))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult result = action(GithubTestSupport.nodePackage(path), "github-app-review", http)
                .handle(GithubTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals("already-recorded", GithubValues.object(result.payload()).get("status"));
        assertTrue(http.requests.stream().allMatch(request -> request.method().equals("GET")));
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
                GithubTestSupport.SHA + ":watch-restart", GithubValues.sha256(GithubValues.jsonBytes(input)), deadline,
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

    @Test void workflowDeadlineAndRateLimitRemainBounded() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var noCalls = new GithubTestSupport.HttpHarness();
        NodeResult expired = action(nodePackage, "github-workflow-watch", noCalls).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", System.currentTimeMillis() - 1L, "correlationId", "watch-expired")))
                .toCompletableFuture().join();
        assertEquals("timeout", expired.outcome());
        assertTrue(noCalls.requests.isEmpty());

        var rate = new GithubTestSupport.HttpHarness().reply(429, Map.of("retry-after", List.of("10")), Map.of("message", "limited"));
        NodeResult limited = action(nodePackage, "github-workflow-watch", rate).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", System.currentTimeMillis() + 20L, "correlationId", "watch-rate")))
                .toCompletableFuture().join();
        assertEquals("timeout", limited.outcome());
        assertEquals(1, rate.requests.size());
    }

    @Test void cancellingWorkflowCancelsTheManagedCall() throws Exception {
        Path store = Path.of("target", "test-stores", "cancel-" + java.util.UUID.randomUUID() + ".db");
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(store);
        var http = new GithubTestSupport.HttpHarness();
        CompletableFuture<OutboundHttpResponse> completion = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        http.pending = new OutboundCall<>() {
            @Override public CompletableFuture<OutboundHttpResponse> completion() { return completion; }
            @Override public boolean cancel() { cancelled.set(true); return completion.cancel(true); }
        };
        CompletableFuture<NodeResult> result = action(nodePackage, "github-workflow-watch", http)
                .handle(GithubTestSupport.message(Map.of("version", "github.workflow-watch.v1",
                        "commit", GithubTestSupport.SHA, "deadlineEpochMs", System.currentTimeMillis() + 2_000L,
                        "correlationId", "watch-cancel"))).toCompletableFuture();
        assertTrue(http.requestArrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(result.cancel(true));
        assertTrue(cancelled.get());
        assertTrue(result.isCompletedExceptionally());
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

    private static NodeAction action(GithubNodePackage nodePackage, String behavior, GithubTestSupport.HttpHarness services) {
        return GithubTestSupport.behavior(nodePackage, behavior).create(GithubTestSupport.node(behavior), services);
    }
    private static Map<String, Object> snapshot(String status, long attempts, long generation) {
        return snapshot(status, attempts, generation, 1234, false);
    }
    private static Map<String, Object> snapshot(String status, long attempts, long generation,
                                                long repositoryId, boolean hasNextPage) {
        return Map.of("data", Map.of("node", Map.of("id", "ITEM_1", "project", Map.of("id", "PVT_example"),
                "content", Map.of("repository", Map.of("databaseId", repositoryId)),
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
    private static Map<String, Object> run(long workflow, long id, long attempt, String status, String conclusion) {
        return Map.of("workflow_id", workflow, "id", id, "run_number", id, "run_attempt", attempt,
                "created_at", "2026-09-04T00:00:00Z", "head_sha", GithubTestSupport.SHA,
                "status", status, "conclusion", conclusion);
    }
    private static GithubTestSupport.HttpHarness releaseReplies() {
        String version = Base64.getEncoder().encodeToString(("<?xml version=\"1.0\"?><project>"
                + "<modelVersion>4.0.0</modelVersion><groupId>ai.ravenroot</groupId>"
                + "<artifactId>ravenroot-parent</artifactId><version>0.1.0</version></project>")
                .getBytes(StandardCharsets.UTF_8));
        String fragment = Base64.getEncoder().encodeToString("Add GitHub automation.".getBytes(StandardCharsets.UTF_8));
        return new GithubTestSupport.HttpHarness().reply(200, Map.of("object", Map.of("sha", GithubTestSupport.SHA)))
                .reply(200, Map.of("sha", GithubTestSupport.SHA))
                .reply(200, Map.of("encoding", "base64", "content", version))
                .reply(200, List.of(Map.of("type", "file", "name", "114.feature.md", "path", ".changes/114.feature.md")))
                .reply(200, Map.of("encoding", "base64", "content", fragment))
                .reply(200, Map.of("object", Map.of("sha", GithubTestSupport.SHA)));
    }

    private static GithubException githubFailure(CompletableFuture<?> result) {
        java.util.concurrent.CompletionException completion = assertThrows(
                java.util.concurrent.CompletionException.class, result::join);
        assertInstanceOf(GithubException.class, completion.getCause());
        return (GithubException) completion.getCause();
    }

    private static final class MutableClock extends java.time.Clock {
        private long millis = 1_000_000;
        void advance(long amount) { millis += amount; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneId.of("UTC"); }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

}
