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

    @Override public void handle(HttpExchange exchange, HttpRequestContext context) throws IOException {
        try { route(exchange, context); }
        catch (AuthorizationDeniedException denied) { error(exchange, context, 403, "RUNNER_ACCESS_DENIED"); }
        catch (IllegalArgumentException invalid) { error(exchange, context, 400, "INVALID_RUNNER_REQUEST"); }
        catch (IllegalStateException conflict) { error(exchange, context, 409, "RUNNER_STATE_CONFLICT"); }
        catch (java.util.concurrent.CompletionException failed) {
            error(exchange, context, 409, "RUNNER_STORE_CONFLICT");
        }
    }

    private void route(HttpExchange exchange, HttpRequestContext context) throws IOException {
        var actor = context.applicationContext();
        String path = exchange.getRequestURI().getPath().substring("/v1/runner-plane".length());
        String method = exchange.getRequestMethod();
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
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("runner resource not found"));
            json(exchange, 200, RunnerJson.resource(resource)); return;
        }
        if (path.equals("/catalog") && method.equals("PUT")) {
            var value = RunnerJson.read(body(exchange, RunnerJson.LIMITS.maxEncodedBytes()));
            var saved = control.save(actor, RunnerJson.resource(actor.tenantId(), value), RunnerJson.number(value, "expectedRevision"));
            json(exchange, 200, RunnerJson.resource(saved)); return;
        }
        if (path.equals("/register") && method.equals("POST")) {
            var value = RunnerJson.read(body(exchange, RunnerJson.LIMITS.maxEncodedBytes()));
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
        if (pieces.length == 4 && pieces[3].equals("release") && method.equals("POST")) {
            var release = control.release(actor, process);
            json(exchange, 200, Map.of("protocolVersion", 1, "tenantId", release.execution().tenantId(),
                    "processInstanceId", process.toString(), "workspaceId", release.workspaceId().toString(),
                    "runnerId", release.runnerId(), "jobIds", release.jobIds().stream().map(UUID::toString).toList(),
                    "notBefore", release.notBefore().toString())); return;
        }
        if (pieces.length == 3 && method.equals("GET")) {
            var workspace = control.workspace(actor, process);
            json(exchange, 200, Map.of("workspaceId", workspace.workspaceId().toString(), "runnerId", workspace.runnerId(),
                    "processInstanceId", process.toString(), "jobs", workspace.jobs().values().stream()
                            .map(RunnerJson::entry).toList())); return;
        }
        if (pieces.length < 5 || !pieces[3].equals("jobs")) throw new IllegalArgumentException("invalid runner route");
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
        if (pieces.length != 6 || !method.equals("POST")) throw new IllegalArgumentException("invalid runner operation route");
        String operation = pieces[5];
        if (operation.equals("artifacts")) {
            long fence = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Runner-Fence"));
            var kind = RunnerArtifact.Kind.valueOf(exchange.getRequestHeaders().getFirst("X-Runner-Artifact-Kind"));
            json(exchange, 201, RunnerJson.artifact(control.upload(actor, process, jobId, fence, kind, exchange.getRequestBody()))); return;
        }
        RunnerJobOperation mutation;
        if (operation.equals("complete")) {
            long fence = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Runner-Fence"));
            mutation = new RunnerJobOperation.Complete(jobId, actor.subject(), fence,
                    RunnerCodec.result(body(exchange, RunnerJson.LIMITS.maxEncodedBytes())));
        } else if (operation.equals("cancel")) mutation = new RunnerJobOperation.Cancel(jobId);
        else if (operation.equals("reconcile")) mutation = new RunnerJobOperation.Reconcile(jobId);
        else {
            var value = RunnerJson.read(body(exchange, 4096));
            Duration ttl = Duration.ofSeconds(RunnerJson.number(value, "ttlSeconds"));
            mutation = switch (operation) {
                case "claim" -> new RunnerJobOperation.Claim(jobId, actor.subject(), ttl);
                case "heartbeat" -> new RunnerJobOperation.Heartbeat(jobId, actor.subject(), RunnerJson.number(value, "fence"), ttl);
                case "reconcile-report" -> new RunnerJobOperation.ReconcileReport(jobId, actor.subject(), ttl);
                default -> throw new IllegalArgumentException("unknown runner operation");
            };
        }
        var assignment = control.operate(actor, process, mutation);
        // Durability precedes delivery. A retry/sweep can resume even if this HTTP response is lost.
        if (assignment.job().state().terminal()) continuations.resume(assignment.job().identity().execution(), jobId);
        if (mutation instanceof RunnerJobOperation.Cancel || mutation instanceof RunnerJobOperation.Reconcile) {
            json(exchange, 200, RunnerJson.job(assignment.job())); return;
        }
        binary(exchange, 200, "application/vnd.ravenroot.runner-assignment.v1", RunnerCodec.assignment(assignment));
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
