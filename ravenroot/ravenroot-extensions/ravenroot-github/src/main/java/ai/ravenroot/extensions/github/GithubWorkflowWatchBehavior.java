package ai.ravenroot.extensions.github;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Persistent exact-commit workflow observation with bounded rate-aware polling. */
public final class GithubWorkflowWatchBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "github-workflow-watch";
    private final GithubRuntime runtime;
    GithubWorkflowWatchBehavior(GithubRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return GithubBehaviorDescriptors.descriptor(BEHAVIOR,
            "Watch GitHub workflows", "Waits durably for configured workflows on one exact commit.",
            false, false, true); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = GithubBehaviorDescriptors.profile(configuration);
        return message -> invoke(message, services, profileName);
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services, String profileName) {
        final Input input; final GithubProfile profile;
        try {
            input = Input.parse(message.payload()); profile = runtime.requireProfile(message.tenantId(), profileName);
            long maximum = System.currentTimeMillis() + (long) profile.maxPolls() * (profile.pollIntervalMs() + profile.timeoutMs());
            if (input.deadlineEpochMs > maximum) throw GithubValues.invalid();
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
        String key = input.commit + ":" + input.correlationId;
        try { return runtime.submit(message, services, profile, BEHAVIOR, key, input.canonical(), input.deadlineEpochMs,
                (api, operation, control) -> watch(api, profile, input, operation, control)); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
    }

    private static NodeResult watch(GithubApi api, GithubProfile profile, Input input,
                                    GithubOperationStore.Lease operation, GithubApi.CallControl control) {
        int polls = (int) operation.record().attempts();
        while (polls < profile.maxPolls()) {
            control.check();
            if (System.currentTimeMillis() >= input.deadlineEpochMs) return result("timeout", "timeout", input,
                    List.of(), polls, "DEADLINE_EXCEEDED");
            GithubApi.Response response;
            try { response = api.get(profile.repositoryPath() + "/actions/runs?head_sha=" + input.commit + "&per_page=100"); }
            catch (GithubProtocol.RateLimited limited) {
                persistWait(operation, input.commit, polls, input.deadlineEpochMs, "rate-limited", limited.retryAt());
                waitUntil(Math.min(limited.retryAt(), input.deadlineEpochMs), control, operation); continue;
            }
            if (response.rateLimited()) {
                long retry = response.retryAfterEpochMs();
                persistWait(operation, input.commit, polls, input.deadlineEpochMs, "rate-limited", retry);
                waitUntil(Math.min(retry, input.deadlineEpochMs), control, operation); continue;
            }
            Map<String, Object> root = GithubProtocol.object(response);
            List<Run> runs = runs(root, profile, input.commit);
            polls++;
            Evaluation evaluation = evaluate(profile.workflowIds(), runs);
            String runIds = evaluation.runs.stream().map(run -> run.workflowId + "/" + run.id + "/" + run.attempt)
                    .collect(java.util.stream.Collectors.joining(","));
            String evidence = GithubValues.sha256(runIds + ":" + polls + ":" + evaluation.state);
            if (evaluation.state.equals("continue")) return result("continue", "succeeded", input, evaluation.runs, polls, "");
            if (evaluation.state.equals("failed")) return result("failed", "failed", input, evaluation.runs, polls, "WORKFLOW_FAILED");
            operationStoreSave(operation, input.commit, evaluation.runs, polls, input.deadlineEpochMs, runIds, evidence);
            long delay = Math.min(input.deadlineEpochMs, System.currentTimeMillis()
                    + Math.min(60_000L, (long) profile.pollIntervalMs() * Math.min(8, polls)));
            waitUntil(delay, control, operation);
        }
        return result("timeout", "timeout", input, List.of(), polls, "POLL_LIMIT");
    }

    private static void operationStoreSave(GithubOperationStore.Lease operation, String commit, List<Run> runs,
                                           int polls, long deadline, String runIds, String evidence) {
        String persisted = new String(GithubValues.jsonBytes(Map.of("commit", commit, "runs", runs.stream()
                .map(run -> Map.<String, Object>of("workflowId", run.workflowId, "runId", run.id,
                        "attempt", run.attempt, "status", run.status, "conclusion", run.conclusion)).toList())),
                java.nio.charset.StandardCharsets.UTF_8);
        operation.store().save(operation, "WAITING", 0, polls, deadline,
                runIds.length() > 256 ? runIds.substring(0, 256) : runIds, evidence, persisted, false);
    }

    private static String persistWait(GithubOperationStore.Lease operation, String commit, int polls, long deadline,
                                      String reason, long retryAt) {
        String evidence = GithubValues.sha256(reason + ":" + retryAt + ":" + polls);
        String persisted = new String(GithubValues.jsonBytes(Map.of("commit", commit, "runs", List.of())),
                java.nio.charset.StandardCharsets.UTF_8);
        operation.store().save(operation, "WAITING", 0, polls, deadline, "", evidence, persisted, false);
        return evidence;
    }

    private static void waitUntil(long target, GithubApi.CallControl control,
                                  GithubOperationStore.Lease operation) {
        while (System.currentTimeMillis() < target) {
            control.check();
            long remaining = target - System.currentTimeMillis();
            try { Thread.sleep(Math.min(remaining, 1_000)); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new GithubException(GithubException.Code.CANCELLED); }
            control.check();
            operation.store().renew(operation);
        }
    }

    private static List<Run> runs(Map<String, Object> root, GithubProfile profile, String commit) {
        Map<Long, Run> newest = new LinkedHashMap<>();
        for (Object raw : GithubValues.list(root.get("workflow_runs"))) {
            Map<String, Object> value = GithubValues.object(raw);
            long workflow = GithubValues.number(value.get("workflow_id"), 1, Long.MAX_VALUE);
            if (!profile.workflowIds().contains(workflow)) continue;
            if (!commit.equals(value.get("head_sha"))) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            Run run = new Run(workflow, GithubValues.number(value.get("id"), 1, Long.MAX_VALUE),
                    GithubValues.number(value.get("run_attempt"), 1, Integer.MAX_VALUE),
                    GithubValues.string(value.get("status"), 32),
                    value.get("conclusion") == null ? "" : GithubValues.string(value.get("conclusion"), 64));
            newest.merge(workflow, run, (first, second) -> second.attempt > first.attempt
                    || second.attempt == first.attempt && second.id > first.id ? second : first);
        }
        return newest.values().stream().sorted(Comparator.comparingLong(Run::workflowId)).toList();
    }

    private static Evaluation evaluate(Set<Long> required, List<Run> runs) {
        if (runs.stream().anyMatch(run -> "completed".equals(run.status) && !"success".equals(run.conclusion)))
            return new Evaluation("failed", runs);
        if (!runs.stream().map(Run::workflowId).collect(java.util.stream.Collectors.toSet()).containsAll(required))
            return new Evaluation("waiting", runs);
        if (runs.stream().allMatch(run -> "completed".equals(run.status) && "success".equals(run.conclusion)))
            return new Evaluation("continue", runs);
        return new Evaluation("waiting", runs);
    }

    private static NodeResult result(String outcome, String status, Input input, List<Run> runs, int polls, String reason) {
        List<Map<String, Object>> values = new ArrayList<>();
        runs.forEach(run -> values.add(Map.of("workflowId", run.workflowId, "runId", run.id,
                "attempt", run.attempt, "status", run.status, "conclusion", run.conclusion)));
        Map<String, Object> output = new LinkedHashMap<>(); output.put("version", "github.workflow-watch.result.v1");
        output.put("status", status); output.put("commit", input.commit); output.put("polls", (long) polls);
        output.put("workflows", List.copyOf(values)); output.put("generation", 0L); output.put("attempts", (long) polls);
        output.put("remoteId", input.commit); if (!reason.isEmpty()) output.put("reason", reason);
        return new NodeResult(outcome, Map.copyOf(output), Map.of());
    }
    private static GithubException sanitize(RuntimeException failure) {
        return failure instanceof GithubException safe ? safe : new GithubException(GithubException.Code.INVALID_INPUT);
    }
    private record Run(long workflowId, long id, long attempt, String status, String conclusion) { }
    private record Evaluation(String state, List<Run> runs) { }
    private record Input(String commit, long deadlineEpochMs, String correlationId) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "commit", "deadlineEpochMs", "correlationId"));
            if (!"github.workflow-watch.v1".equals(value.get("version"))) throw GithubValues.invalid();
            String commit = GithubValues.string(value.get("commit"), 40);
            if (!commit.matches("[0-9a-f]{40}")) throw GithubValues.invalid();
            long deadline = GithubValues.number(value.get("deadlineEpochMs"), 1, Long.MAX_VALUE);
            return new Input(commit, deadline, GithubValues.string(value.get("correlationId"), 128));
        }
        Map<String, Object> canonical() { return Map.of("version", "github.workflow-watch.v1", "commit", commit,
                "deadlineEpochMs", deadlineEpochMs, "correlationId", correlationId); }
    }
}
