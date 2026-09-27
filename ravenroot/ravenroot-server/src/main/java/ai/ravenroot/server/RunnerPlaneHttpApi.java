package ai.ravenroot.server;

import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.core.runner.AuthorizedRunnerControl;
import ai.ravenroot.core.runner.RunnerJson;
import ai.ravenroot.core.runner.PinnedRunnerContinuationExecutor;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Authenticated protocol-v1 adapter. Installed only through the server's protected API wrapper. */
final class RunnerPlaneHttpApi implements HttpRequestContext.Handler {
    private final AuthorizedRunnerControl control;
    private final PinnedRunnerContinuationExecutor continuations;
    RunnerPlaneHttpApi(AuthorizedRunnerControl control, PinnedRunnerContinuationExecutor continuations) {
        this.control = control; this.continuations = continuations;
    }

    void bindLifecycle(ai.ravenroot.core.process.ProcessLifecycleService lifecycle) {
        if (continuations == null) throw new IllegalStateException("runner coordinator does not own graph execution");
        continuations.bindLifecycle(lifecycle);
    }

    @Override public void handle(HttpExchange exchange, HttpRequestContext context) throws IOException {
        try { route(exchange, context); }
        catch (AuthorizationDeniedException denied) { error(exchange, context, 403, "RUNNER_ACCESS_DENIED"); }
        catch (NoSuchElementException absent) { error(exchange, context, 404, "RUNNER_RESOURCE_NOT_FOUND"); }
        catch (IllegalArgumentException invalid) { error(exchange, context, 400, "INVALID_RUNNER_REQUEST"); }
        catch (IllegalStateException conflict) { error(exchange, context, 409, "RUNNER_STATE_CONFLICT"); }
        catch (java.util.concurrent.CompletionException failed) {
            if (failed.getCause() instanceof ai.ravenroot.api.persistence.ExecutionStoreException storeFailure
                    && storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.NotFound) {
                error(exchange, context, 404, "RUNNER_RESOURCE_NOT_FOUND");
            } else if (failed.getCause() instanceof ai.ravenroot.api.persistence.ExecutionStoreException storeFailure
                    && storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.InvalidRequest) {
                // Request shape has already been checked above the port. The transactional fold
                // refuses a now-ineligible claim/capacity/stop revision; this is a state conflict.
                error(exchange, context, 409, "RUNNER_STATE_CONFLICT");
            } else error(exchange, context, 409, "RUNNER_STORE_CONFLICT");
        }
    }

    private void route(HttpExchange exchange, HttpRequestContext context) throws IOException {
        var actor = context.applicationContext();
        control.verifyWorkerCodecs(actor, exchange.getRequestHeaders().getFirst("X-Ravenroot-Runner-Codecs"));
        String path = exchange.getRequestURI().getPath().substring("/v1/runner-plane".length());
        String method = exchange.getRequestMethod();
        if (path.equals("/availability")) {
            if (method.equals("POST")) {
                var value = RunnerJson.read(body(exchange, 65536));
                var profiles = RunnerJson.strings(value.get("runtimeProfiles"));
                var accepted = control.availability(actor, UUID.fromString(RunnerJson.text(value, "sessionId")),
                        Math.toIntExact(RunnerJson.number(value, "capacity")), Math.toIntExact(RunnerJson.number(value, "activeJobs")), profiles,
                        java.time.Duration.parse(RunnerJson.text(value, "ttl")));
                json(exchange, 200, availability(accepted)); return;
            }
            if (method.equals("GET")) { json(exchange, 200, Map.of("items", control.availability(actor).stream().map(RunnerPlaneHttpApi::availability).toList())); return; }
        }
        if ((path.equals("/health") || path.equals("/audit")) && method.equals("GET")) {
            String raw = exchange.getRequestURI().getRawQuery();
            String name = path.equals("/health") ? "cursor" : "afterOffset";
            if (raw != null && (!raw.startsWith(name + "=") || raw.contains("&"))) throw new IllegalArgumentException("invalid runner page");
            String cursor = raw == null ? null : java.net.URLDecoder.decode(raw.substring(name.length() + 1), StandardCharsets.UTF_8);
            json(exchange, 200, path.equals("/health") ? control.health(actor, cursor)
                    : control.audit(actor, cursor == null ? 0 : Long.parseLong(cursor))); return;
        }
        if (path.equals("/catalog") && method.equals("GET")) {
            var items = control.resources(actor).stream().map(resource -> {
                var value = new LinkedHashMap<>(RunnerJson.resource(resource)); value.remove("document"); return value;
            }).toList();
            json(exchange, 200, Map.of("protocolVersion", 1, "items", items)); return;
        }
        if (path.startsWith("/catalog/") && method.equals("GET")) {
            String key = path.substring("/catalog/".length());
            var resource = control.resources(actor).stream().filter(value -> value.key().equals(key))
                    .findFirst().orElseThrow(() -> new NoSuchElementException("runner resource not found"));
            json(exchange, 200, RunnerJson.resource(resource)); return;
        }
        if (path.equals("/catalog") && method.equals("PUT")) {
            var value = RunnerJson.read(body(exchange, RunnerJson.LIMITS.maxEncodedBytes()));
            var saved = control.save(actor, RunnerJson.resource(actor.tenantId(), value), RunnerJson.number(value, "expectedRevision"));
            json(exchange, 200, RunnerJson.resource(saved)); return;
        }
        if (path.equals("/register") && method.equals("POST")) {
            var value = RunnerJson.read(body(exchange, RunnerJson.LIMITS.maxEncodedBytes()));
            if ("kubernetes-pod-v1".equals(value.get("trustProfile")) && !RunnerCodec.NATIVE_CAPABILITIES.equals(
                    exchange.getRequestHeaders().getFirst("X-Ravenroot-Runner-Codecs")))
                throw new IllegalStateException("native worker codec capabilities are incompatible");
            json(exchange, 200, RunnerJson.resource(control.register(actor, RunnerJson.registration(actor.tenantId(), value)))); return;
        }
        if (path.equals("/assignments") && method.equals("GET")) {
            String raw = exchange.getRequestURI().getRawQuery(); String cursor = null;
            if (raw != null) {
                if (!raw.startsWith("cursor=") || raw.contains("&")) throw new IllegalArgumentException("invalid runner cursor");
                cursor = java.net.URLDecoder.decode(raw.substring(7), StandardCharsets.UTF_8);
            }
            var page = control.assignments(actor, cursor);
            var value = new LinkedHashMap<String, Object>(); value.put("items", page.items()); value.put("nextCursor", page.nextCursor());
            json(exchange, 200, value); return;
        }
        String[] pieces = path.split("/");
        if (pieces.length < 3 || !pieces[1].equals("workspaces")) { error(exchange, context, 404, "RUNNER_RESOURCE_NOT_FOUND"); return; }
        UUID process = UUID.fromString(pieces[2]);
        if (pieces.length == 4 && pieces[3].equals("worker-revision") && method.equals("GET")) {
            json(exchange, 200, Map.of("revision", control.workerRevision(actor, process))); return;
        }
        if (pieces.length == 6 && pieces[3].equals("resources") && method.equals("POST")) {
            if (pieces[5].equals("release")) {
                var release = control.releaseWorkspace(actor, process, pieces[4]);
                var response = new LinkedHashMap<String, Object>(Map.of("protocolVersion", 1, "tenantId", release.execution().tenantId(),
                        "processInstanceId", process.toString(), "workspaceId", release.workspaceId().toString(),
                        "runnerId", release.runnerId(), "jobIds", release.jobIds().stream().map(UUID::toString).toList(),
                        "notBefore", release.notBefore().toString()));
                response.put("workspaceScope", release.workspaceScope().name()); response.put("ownershipGeneration", release.generation());
                response.put("physicalCleanup", release.physicalCleanup()); json(exchange, 200, response); return;
            }
            var value = RunnerJson.read(body(exchange, 4096));
            long revision = switch (pieces[5]) {
                case "abort" -> control.stopWorkspace(actor, process, pieces[4], RunnerJson.number(value, "expectedRevision"));
                case "stopped" -> control.workspaceStopped(actor, process, pieces[4], UUID.fromString(RunnerJson.text(value, "workspaceId")),
                        RunnerJson.number(value, "expectedRevision"));
                case "released" -> control.workspaceReleased(actor, process, pieces[4], UUID.fromString(RunnerJson.text(value, "workspaceId")),
                        RunnerJson.number(value, "expectedRevision"));
                default -> throw new NoSuchElementException("unknown Workspace operation");
            };
            json(exchange, 200, Map.of("revision", revision)); return;
        }
        if (pieces.length == 4 && pieces[3].equals("release") && method.equals("POST")) {
            var release = control.release(actor, process);
            json(exchange, 200, Map.of("protocolVersion", 1, "tenantId", release.execution().tenantId(),
                    "processInstanceId", process.toString(), "workspaceId", release.workspaceId().toString(),
                    "runnerId", release.runnerId(), "jobIds", release.jobIds().stream().map(UUID::toString).toList(),
                    "notBefore", release.notBefore().toString())); return;
        }
        if (pieces.length == 3 && method.equals("GET")) {
            var view = control.view(actor, process); var workspace = view.workspace();
            json(exchange, 200, Map.of("workspaceId", workspace.workspaceId().toString(), "runnerId", workspace.runnerId(),
                    "processInstanceId", process.toString(), "revision", view.revision(), "jobs", workspace.jobs().values().stream()
                            .map(RunnerJson::entry).toList(), "workspaces", workspace.workspaces().values().stream().map(RunnerJson::workspace).toList())); return;
        }
        if (pieces.length < 5 || !pieces[3].equals("jobs")) throw new NoSuchElementException("unknown runner route");
        UUID jobId = UUID.fromString(pieces[4]);
        if (pieces.length == 5 && method.equals("GET")) {
            binary(exchange, 200, "application/vnd.ravenroot.runner-assignment.v1", RunnerCodec.assignment(control.assignment(actor, process, jobId))); return;
        }
        if (pieces.length == 7 && pieces[5].equals("artifacts") && method.equals("GET")) {
            try (var input = control.artifact(actor, process, jobId, UUID.fromString(pieces[6]))) {
                if ("application/json".equals(exchange.getRequestHeaders().getFirst("Accept"))) {
                    byte[] bytes = input.readNBytes(65_537);
                    json(exchange, 200, Map.of("content", new String(bytes, 0, Math.min(bytes.length, 65_536), StandardCharsets.UTF_8),
                            "truncated", bytes.length > 65_536)); return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) { input.transferTo(output); }
            } return;
        }
        if (pieces.length != 6 || !method.equals("POST")) throw new NoSuchElementException("unknown runner operation route");
        String operation = pieces[5];
        if (operation.equals("resolve-continuation")) {
            if (continuations == null) {
                error(exchange, context, 409, "RUNNER_GRAPH_AUTHORITY_REQUIRED"); return;
            }
            requireContentType(exchange, "application/json");
            var value = RunnerJson.read(body(exchange, 4096));
            if (!value.keySet().equals(Set.of("expectedRevision", "resolution"))) throw new IllegalArgumentException("invalid continuation resolution");
            var resolution = RunnerJobOperation.ContinuationResolution.valueOf(RunnerJson.text(value, "resolution"));
            long revision = control.resolve(actor, process, jobId, RunnerJson.number(value, "expectedRevision"), resolution, continuations);
            if (resolution == RunnerJobOperation.ContinuationResolution.RESUME) continuations.resume(
                    new ai.ravenroot.api.persistence.ExecutionKey(actor.tenantId(), process), jobId);
            json(exchange, 200, Map.of("revision", revision, "resolution", resolution.name())); return;
        }
        if (operation.equals("artifacts")) {
            requireContentType(exchange, "application/octet-stream");
            long fence = Long.parseLong(requiredHeader(exchange, "X-Runner-Fence"));
            var kind = RunnerArtifact.Kind.valueOf(requiredHeader(exchange, "X-Runner-Artifact-Kind"));
            json(exchange, 201, RunnerJson.artifact(control.upload(actor, process, jobId, fence, kind, exchange.getRequestBody()))); return;
        }
        RunnerJobOperation mutation;
        if (operation.equals("complete")) {
            requireContentType(exchange, "application/vnd.ravenroot.runner-result.v1");
            long fence = Long.parseLong(requiredHeader(exchange, "X-Runner-Fence"));
            mutation = new RunnerJobOperation.Complete(jobId, actor.subject(), fence,
                    RunnerCodec.result(body(exchange, RunnerJson.LIMITS.maxEncodedBytes())));
        } else if (operation.equals("cancel")) mutation = new RunnerJobOperation.Cancel(jobId);
        else if (operation.equals("reconcile")) mutation = new RunnerJobOperation.Reconcile(jobId);
        else {
            if (!Set.of("claim", "heartbeat", "reconcile-report").contains(operation)) throw new NoSuchElementException("unknown runner operation");
            requireContentType(exchange, "application/json");
            var value = RunnerJson.read(body(exchange, 4096));
            Duration ttl = Duration.ofSeconds(RunnerJson.number(value, "ttlSeconds"));
            mutation = switch (operation) {
                case "claim" -> new RunnerJobOperation.Claim(jobId, actor.subject(), ttl,
                        value.containsKey("workerSession") ? UUID.fromString(RunnerJson.text(value, "workerSession")) : null);
                case "heartbeat" -> new RunnerJobOperation.Heartbeat(jobId, actor.subject(), RunnerJson.number(value, "fence"), ttl,
                        value.containsKey("workerSession") ? UUID.fromString(RunnerJson.text(value, "workerSession")) : null,
                        value.containsKey("kubernetes") ? RunnerJson.kubernetes(RunnerJson.map(value.get("kubernetes"))) : null);
                case "reconcile-report" -> new RunnerJobOperation.ReconcileReport(jobId, actor.subject(), ttl);
                default -> throw new IllegalArgumentException("unknown runner operation");
            };
        }
        var assignment = control.operate(actor, process, mutation);
        // Durability precedes delivery. A retry/sweep can resume even if this HTTP response is lost.
        if (assignment.job().state().terminal() && continuations != null)
            continuations.resume(assignment.job().identity().execution(), jobId);
        if (mutation instanceof RunnerJobOperation.Cancel || mutation instanceof RunnerJobOperation.Reconcile) {
            json(exchange, 200, RunnerJson.job(assignment.job())); return;
        }
        binary(exchange, 200, "application/vnd.ravenroot.runner-assignment.v1", RunnerCodec.assignment(assignment));
    }

    private static Map<String, Object> availability(RunnerAvailability value) {
        return Map.of("runnerId", value.runnerId(), "sessionId", value.sessionId().toString(), "capacity", value.capacity(),
                "activeJobs", value.activeJobs(), "availableJobs", value.capacity() - value.activeJobs(), "runtimeProfiles", value.runtimeProfiles(),
                "observedAt", value.observedAt().toString(), "leaseUntil", value.leaseUntil().toString());
    }
    private static String requiredHeader(HttpExchange exchange, String name) {
        var values = exchange.getRequestHeaders().get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("required runner header is missing or ambiguous");
        }
        return values.getFirst();
    }
    private static void requireContentType(HttpExchange exchange, String type) {
        if (!requiredHeader(exchange, "Content-Type").split(";", 2)[0].trim().equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("unsupported runner content type");
        }
    }

    private static byte[] body(HttpExchange exchange, int limit) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new IllegalArgumentException("runner body quota exceeded");
        return bytes;
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        binary(exchange, status, "application/json; charset=utf-8", RunnerJson.write(value));
    }
    private static void binary(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void error(HttpExchange exchange, HttpRequestContext context, int status, String code) throws IOException {
        json(exchange, status, Map.of("error", code, "requestId", context.requestId()));
    }
}
