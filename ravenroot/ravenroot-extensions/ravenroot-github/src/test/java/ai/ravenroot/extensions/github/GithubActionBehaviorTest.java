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

    @Test void staleReviewHeadProducesNoReviewMutation() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var http = new GithubTestSupport.HttpHarness().reply(200, List.of())
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
        var http = new GithubTestSupport.HttpHarness().reply(200, List.of(pending))
                .reply(200, pull(GithubTestSupport.SHA)).reply(200, reviewObject(99, "APPROVED", marker))
                .reply(200, pull(GithubTestSupport.SHA));
        NodeResult result = action(nodePackage, "github-app-review", http).handle(GithubTestSupport.message(review("review-recover")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertEquals(List.of("GET", "GET", "POST", "GET"), http.requests.stream().map(value -> value.method()).toList());
    }

    @Test void workflowUsesExactShaAndNewestAttempts() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        var runs = List.of(run(1001, 11, 1, "completed", "failure"),
                run(1001, 12, 2, "completed", "success"), run(1002, 13, 1, "completed", "success"));
        var http = new GithubTestSupport.HttpHarness().reply(200, Map.of("workflow_runs", runs));
        NodeResult result = action(nodePackage, "github-workflow-watch", http).handle(GithubTestSupport.message(Map.of(
                "version", "github.workflow-watch.v1", "commit", GithubTestSupport.SHA,
                "deadlineEpochMs", System.currentTimeMillis() + 2_000L, "correlationId", "watch-1")))
                .toCompletableFuture().join();
        assertEquals("continue", result.outcome());
        assertTrue(http.requests.getFirst().destination().getQuery().contains("head_sha=" + GithubTestSupport.SHA));
        @SuppressWarnings("unchecked") List<Map<String, Object>> selected = (List<Map<String, Object>>) GithubValues.object(result.payload()).get("workflows");
        assertEquals(Set.of(12L, 13L), selected.stream().map(value -> (Long) value.get("runId")).collect(java.util.stream.Collectors.toSet()));
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

    @Test void cancellingWorkflowCancelsTheManagedCall() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
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
        while (http.requests.isEmpty()) Thread.onSpinWait();
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
        return Map.of("data", Map.of("node", Map.of("id", "ITEM_1", "project", Map.of("id", "PVT_example"),
                "fieldValues", Map.of("nodes", List.of(
                        Map.of("field", Map.of("id", "PVTSSF_status"), "optionId", status.equals("Todo") ? "todo-id" : "progress-id"),
                        Map.of("field", Map.of("id", "PVTF_attempts"), "number", attempts),
                        Map.of("field", Map.of("id", "PVTF_generation"), "number", generation))))));
    }
    private static Map<String, Object> pull(String sha) {
        return Map.of("base", Map.of("repo", Map.of("id", 1234L)), "head", Map.of("sha", sha));
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
        return Map.of("workflow_id", workflow, "id", id, "run_attempt", attempt, "head_sha", GithubTestSupport.SHA,
                "status", status, "conclusion", conclusion);
    }
    private static GithubTestSupport.HttpHarness releaseReplies() {
        String version = Base64.getEncoder().encodeToString("<version>0.1.0</version>".getBytes(StandardCharsets.UTF_8));
        String fragment = Base64.getEncoder().encodeToString("Add GitHub automation.".getBytes(StandardCharsets.UTF_8));
        return new GithubTestSupport.HttpHarness().reply(200, Map.of("object", Map.of("sha", GithubTestSupport.SHA)))
                .reply(200, Map.of("sha", GithubTestSupport.SHA))
                .reply(200, Map.of("encoding", "base64", "content", version))
                .reply(200, List.of(Map.of("type", "file", "name", "114.feature.md", "path", ".changes/114.feature.md")))
                .reply(200, Map.of("encoding", "base64", "content", fragment));
    }
}
