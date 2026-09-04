package ai.ravenroot.extensions.github;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Persistent exact-commit workflow observation using one bounded lease and HTTP call per scheduled poll. */
public final class GithubWorkflowWatchBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "github-workflow-watch";
    private static final ScheduledExecutorService POLLS = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "ravenroot-github-workflow-poll"); thread.setDaemon(true); return thread;
    });
    private final GithubRuntime runtime;
    private final Clock clock;
    private final PollScheduler scheduler;
    private final Runnable beforeInnerCompletion;
    private final Runnable beforeWaitingSchedule;

    GithubWorkflowWatchBehavior(GithubRuntime runtime) {
        this(runtime, Clock.systemUTC(), (task, delay) -> {
            ScheduledFuture<?> future = POLLS.schedule(task, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        });
    }
    GithubWorkflowWatchBehavior(GithubRuntime runtime, Clock clock, PollScheduler scheduler) {
        this(runtime, clock, scheduler, () -> { }, () -> { });
    }
    GithubWorkflowWatchBehavior(GithubRuntime runtime, Clock clock, PollScheduler scheduler,
                                Runnable beforeInnerCompletion, Runnable beforeWaitingSchedule) {
        this.runtime = runtime; this.clock = clock; this.scheduler = scheduler;
        this.beforeInnerCompletion = java.util.Objects.requireNonNull(beforeInnerCompletion);
        this.beforeWaitingSchedule = java.util.Objects.requireNonNull(beforeWaitingSchedule);
    }
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
            long maximum = Math.addExact(clock.millis(),
                    Math.multiplyExact((long) profile.maxPolls(), profile.pollIntervalMs() + (long) profile.timeoutMs()));
            if (input.deadlineEpochMs > maximum) throw GithubValues.invalid();
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
        WatchTask task = new WatchTask(message, services, profile, input);
        task.schedule(0); return task;
    }

    private final class WatchTask extends CompletableFuture<NodeResult> {
        private final NodeMessage message; private final NodePackageServices services;
        private final GithubProfile profile; private final Input input;
        private boolean cancelled;
        private boolean terminalReplayWon;
        private ScheduledPoll scheduled; private CompletableFuture<NodeResult> active;

        WatchTask(NodeMessage message, NodePackageServices services, GithubProfile profile, Input input) {
            this.message = message; this.services = services; this.profile = profile; this.input = input;
        }
        synchronized void schedule(long delayMs) {
            if (!isDone() && !cancelled)
                scheduled = scheduler.schedule(this::poll, Math.max(0, delayMs));
        }
        private synchronized void poll() {
            scheduled = null;
            if (isDone() || cancelled) return;
            try {
                active = runtime.submit(message, services, profile, BEHAVIOR,
                        operationKey(input), input.canonical(), input.deadlineEpochMs,
                        (api, operation, control) -> pollOnce(api, profile, input, operation)).toCompletableFuture();
            } catch (RuntimeException failure) { completeExceptionally(sanitize(failure)); return; }
            CompletableFuture<NodeResult> submitted = active;
            submitted.whenComplete((result, failure) -> {
                beforeInnerCompletion.run();
                settle(submitted, result, failure);
            });
        }
        @Override public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || cancelled) return false;
            CompletableFuture<NodeResult> running = active;
            if (running != null && running.isDone()) settleCompleted(running);
            if (isDone()) return false;
            running = active;
            if (running != null && !running.cancel(true)) {
                if (running.isDone()) settleCompleted(running);
                if (isDone() || active != null) return false;
            }
            cancelled = true;
            ScheduledPoll pending = scheduled; if (pending != null) pending.cancel();
            if (active == null) persistCancellation();
            return !terminalReplayWon;
        }

        private void settleCompleted(CompletableFuture<NodeResult> completed) {
            try { settle(completed, completed.join(), null); }
            catch (CompletionException failure) { settle(completed, null, failure); }
        }

        private synchronized void settle(CompletableFuture<NodeResult> completed, NodeResult result,
                                         Throwable failure) {
            if (completed != active || isDone()) return;
            active = null;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                completeExceptionally(cause); return;
            }
            Map<String, Object> output = GithubValues.object(result.payload());
            if (!"waiting".equals(output.get("status"))) { complete(result); return; }
            beforeWaitingSchedule.run();
            if (cancelled) { persistCancellation(); return; }
            long retryAt = GithubValues.number(output.get("retryAtEpochMs"), 1, Long.MAX_VALUE);
            schedule(Math.max(0, retryAt - clock.millis()));
        }

        private void persistCancellation() {
            try {
                runtime.cancelDurably(message, services, profile, BEHAVIOR, operationKey(input),
                        input.canonical(), input.deadlineEpochMs).whenComplete(this::settleCancellation);
            } catch (RuntimeException failure) {
                completeExceptionally(sanitize(failure));
            }
        }

        private synchronized void settleCancellation(NodeResult replay, Throwable failure) {
            if (isDone()) return;
            if (failure == null) { terminalReplayWon = true; complete(replay); return; }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            completeExceptionally(cause);
        }
    }

    private NodeResult pollOnce(GithubApi api, GithubProfile profile, Input input,
                                GithubOperationStore.Lease operation) {
        int previousPolls = Math.toIntExact(operation.record().attempts());
        Restored restored = restore(operation.record(), input.commit);
        List<Run> previous = restored.runs;
        long now = clock.millis();
        if (now >= input.deadlineEpochMs) return result("timeout", "timeout", input, previous,
                previousPolls, "DEADLINE_EXCEEDED", 0);
        if (previousPolls >= profile.maxPolls()) return result("timeout", "timeout", input, previous,
                previousPolls, "POLL_LIMIT", 0);
        if (restored.retryAtEpochMs > now) return result("continue", "waiting", input, previous,
                previousPolls, "RATE_LIMITED", Math.min(input.deadlineEpochMs, restored.retryAtEpochMs));
        int polls = previousPolls + 1;
        final Map<String, Object> root;
        try {
            root = GithubProtocol.object(api.get(profile.repositoryPath() + "/actions/runs?head_sha="
                    + input.commit + "&per_page=100&page=1"));
        } catch (GithubProtocol.RateLimited limited) {
            return result("continue", "waiting", input, previous, polls, "RATE_LIMITED",
                    Math.min(input.deadlineEpochMs, limited.retryAt()));
        }
        long total = GithubValues.number(root.get("total_count"), 0, Long.MAX_VALUE);
        if (total > 100) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        List<Run> runs = merge(previous, runs(root, profile, input.commit));
        Evaluation evaluation = evaluate(profile.workflowIds(), runs);
        if (evaluation.state.equals("continue")) return result("continue", "succeeded", input, runs, polls, "", 0);
        if (evaluation.state.equals("failed")) return result("failed", "failed", input, runs, polls,
                "WORKFLOW_FAILED", 0);
        long backoff = Math.min(60_000L, (long) profile.pollIntervalMs() * Math.min(8, polls));
        return result("continue", "waiting", input, runs, polls, "",
                Math.min(input.deadlineEpochMs, clock.millis() + backoff));
    }

    private static Restored restore(GithubOperationStore.Record record, String commit) {
        if (record.resultJson().isEmpty()) return new Restored(List.of(), 0);
        try {
            Map<String, Object> result = GithubValues.object(ai.ravenroot.api.payload.PayloadJson.read(
                    record.resultJson().getBytes(java.nio.charset.StandardCharsets.UTF_8), GithubValues.LIMITS).toJava());
            if (!commit.equals(result.get("commit"))) throw GithubValues.invalid();
            List<Run> restored = new ArrayList<>();
            for (Object raw : GithubValues.list(result.get("workflows")))
                restored.add(Run.parse(GithubValues.object(raw), commit, false));
            long retryAt = result.get("retryAtEpochMs") == null ? 0
                    : GithubValues.number(result.get("retryAtEpochMs"), 1, Long.MAX_VALUE);
            return new Restored(List.copyOf(restored), retryAt);
        } catch (RuntimeException corrupt) { throw new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE); }
    }

    private static List<Run> runs(Map<String, Object> root, GithubProfile profile, String commit) {
        Map<Long, Run> newest = new LinkedHashMap<>();
        for (Object raw : GithubValues.list(root.get("workflow_runs"))) {
            Run candidate = Run.parse(GithubValues.object(raw), commit, true);
            if (profile.workflowIds().contains(candidate.workflowId))
                newest.merge(candidate.workflowId, candidate, GithubWorkflowWatchBehavior::newer);
        }
        return newest.values().stream().sorted(Comparator.comparingLong(Run::workflowId)).toList();
    }

    private static Run newer(Run first, Run second) {
        if (first.id == second.id) {
            if (first.runNumber != second.runNumber || !first.createdAt.equals(second.createdAt))
                throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            int attempt = Long.compare(first.attempt, second.attempt);
            if (attempt != 0) return attempt < 0 ? second : first;
            if ("completed".equals(first.status) && "completed".equals(second.status)
                    && !first.conclusion.equals(second.conclusion))
                throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            return statusRank(second.status) > statusRank(first.status) ? second : first;
        }
        int order = Long.compare(first.runNumber, second.runNumber);
        if (order == 0) order = first.createdAt.compareTo(second.createdAt);
        if (order == 0) order = Long.compare(first.id, second.id);
        return order < 0 ? second : first;
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "queued", "waiting", "requested", "pending" -> 0;
            case "in_progress" -> 1;
            case "completed" -> 2;
            default -> throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        };
    }

    private static List<Run> merge(List<Run> persisted, List<Run> observed) {
        Map<Long, Run> newest = new LinkedHashMap<>();
        persisted.forEach(run -> newest.put(run.workflowId, run));
        observed.forEach(run -> newest.merge(run.workflowId, run, GithubWorkflowWatchBehavior::newer));
        return newest.values().stream().sorted(Comparator.comparingLong(Run::workflowId)).toList();
    }

    private static Evaluation evaluate(Set<Long> required, List<Run> runs) {
        if (runs.stream().anyMatch(run -> "completed".equals(run.status) && !"success".equals(run.conclusion)))
            return new Evaluation("failed");
        if (!runs.stream().map(Run::workflowId).collect(java.util.stream.Collectors.toSet()).containsAll(required))
            return new Evaluation("waiting");
        if (runs.stream().allMatch(run -> "completed".equals(run.status) && "success".equals(run.conclusion)))
            return new Evaluation("continue");
        return new Evaluation("waiting");
    }
    private static String operationKey(Input input) {
        return input.commit + ":" + GithubValues.keyDigest("workflow-correlation", input.correlationId);
    }

    private static NodeResult result(String outcome, String status, Input input, List<Run> runs, int polls,
                                     String reason, long retryAt) {
        Map<String, Object> output = new LinkedHashMap<>(); output.put("version", "github.workflow-watch.result.v1");
        output.put("status", status); output.put("commit", input.commit); output.put("polls", (long) polls);
        output.put("workflows", runs.stream().map(Run::output).toList()); output.put("generation", 0L);
        output.put("attempts", (long) polls); output.put("remoteId", input.commit);
        if (!reason.isEmpty()) output.put("reason", reason); if (retryAt > 0) output.put("retryAtEpochMs", retryAt);
        return GithubValues.result(outcome, Map.copyOf(output), Map.of());
    }
    private static GithubException sanitize(RuntimeException failure) {
        return failure instanceof GithubException safe ? safe : new GithubException(GithubException.Code.INVALID_INPUT);
    }

    private record Run(long workflowId, long id, long runNumber, long attempt, Instant createdAt,
                       String status, String conclusion) {
        static Run parse(Map<String, Object> value, String commit, boolean wire) {
            if (wire && !commit.equals(value.get("head_sha"))) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            return new Run(GithubValues.number(value.get(wire ? "workflow_id" : "workflowId"), 1, Long.MAX_VALUE),
                    GithubValues.number(value.get(wire ? "id" : "runId"), 1, Long.MAX_VALUE),
                    GithubValues.number(value.get(wire ? "run_number" : "runNumber"), 1, Long.MAX_VALUE),
                    GithubValues.number(value.get(wire ? "run_attempt" : "attempt"), 1, Integer.MAX_VALUE),
                    instant(GithubValues.string(value.get(wire ? "created_at" : "createdAt"), 64)),
                    GithubValues.string(value.get("status"), 32),
                    conclusion(value.get("conclusion")));
        }
        Map<String, Object> output() { return Map.of("workflowId", workflowId, "runId", id,
                "runNumber", runNumber, "attempt", attempt, "createdAt", createdAt.toString(),
                "status", status, "conclusion", conclusion); }
        private static Instant instant(String value) {
            try { return Instant.parse(value); }
            catch (RuntimeException invalid) { throw new GithubException(GithubException.Code.RESPONSE_INVALID); }
        }
        private static String conclusion(Object value) {
            if (value == null) return "";
            if (!(value instanceof String text) || text.length() > 64
                    || text.codePoints().anyMatch(character -> character < 0x20 || character == 0x7f))
                throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            return text;
        }
    }
    private record Evaluation(String state) { }
    private record Restored(List<Run> runs, long retryAtEpochMs) { }
    @FunctionalInterface interface PollScheduler { ScheduledPoll schedule(Runnable task, long delayMs); }
    @FunctionalInterface interface ScheduledPoll { void cancel(); }
    private record Input(String commit, long deadlineEpochMs, String correlationId) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "commit", "deadlineEpochMs", "correlationId"));
            if (!"github.workflow-watch.v1".equals(value.get("version"))) throw GithubValues.invalid();
            String commit = GithubValues.string(value.get("commit"), 40);
            if (!commit.matches("[0-9a-f]{40}")) throw GithubValues.invalid();
            return new Input(commit, GithubValues.number(value.get("deadlineEpochMs"), 1, Long.MAX_VALUE),
                    GithubValues.string(value.get("correlationId"), 128));
        }
        Map<String, Object> canonical() { return Map.of("version", "github.workflow-watch.v1", "commit", commit,
                "deadlineEpochMs", deadlineEpochMs, "correlationId", correlationId); }
    }
}
