package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.server.HttpRequestContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Authenticated HTTP adapter for one provider-neutral, tenant-confined graph source archive. */
public final class GraphAuthoringHttpApi implements AutoCloseable {
    private static final PayloadLimits JSON_LIMITS = new PayloadLimits(2 * 1024 * 1024, 24, 10_000,
            100_000, 1024 * 1024, 512);
    private final GraphAuthoringConfiguration configuration;
    private final GraphAuthoringRepository repository;
    private final AuthorizationService authorization;
    private final Duration timeout;
    private final Consumer<String> audit;

    public GraphAuthoringHttpApi(GraphAuthoringConfiguration configuration, GraphAuthoringRepository repository,
                                 AuthorizationService authorization, Consumer<String> audit) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.timeout = configuration.requestTimeout().multipliedBy(configuration.retryLimit() + 2L);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public String capabilitiesJson() {
        Map<String, Object> value = new LinkedHashMap<>(configuration.capabilities());
        var operations = new java.util.TreeSet<String>();
        repository.capabilities().stream().map(Enum::name).forEach(operations::add);
        operations.add("ARTIFACT_IMPORT");
        operations.add("ARTIFACT_DEPLOY");
        value.put("operations", operations);
        return new String(PayloadJson.writeJava(value, JSON_LIMITS), StandardCharsets.UTF_8);
    }

    public void handle(HttpExchange exchange, HttpRequestContext http) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring("/v1/graph-authoring".length());
        var context = http.applicationContext();
        var actor = new GraphAuthoringRepository.Actor(context.tenantId(), context.subject());
        String operation = "unknown";
        try {
            if (suffix.isEmpty() || "/".equals(suffix)) {
                operation = "list";
                require(context, AuthorizationAction.GRAPH_READ, "graphs");
                requireMethod(exchange, "GET");
                write(exchange, 200, page(await(repository.list(actor, query(exchange).get("cursor")))));
                return;
            }
            List<String> segments = segments(suffix);
            if (segments.isEmpty() || segments.size() > 2) throw new NotFound();
            String documentId = segments.getFirst();
            String child = segments.size() == 2 ? segments.get(1) : "";
            if (child.isEmpty()) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    operation = "open";
                    require(context, AuthorizationAction.GRAPH_READ, documentId);
                    var document = await(repository.open(actor, documentId,
                            "release".equals(query(exchange).get("source"))));
                    write(exchange, 200, document(document));
                    return;
                }
                if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                    operation = "save";
                    require(context, AuthorizationAction.GRAPH_WRITE, documentId);
                    byte[] graph = readBounded(exchange, configuration.maxDocumentBytes());
                    var request = new GraphAuthoringRepository.SaveRequest(documentId, graph,
                            expected(exchange), requiredHeader(exchange, "Idempotency-Key"));
                    write(exchange, 200, document(await(repository.save(actor, request))));
                    audited(http, actor, operation, documentId, "success");
                    return;
                }
                if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                    operation = "delete";
                    require(context, AuthorizationAction.GRAPH_WRITE, documentId);
                    await(repository.deleteDraft(actor, mutation(exchange, documentId, "")));
                    write(exchange, 204, null);
                    audited(http, actor, operation, documentId, "success");
                    return;
                }
                throw new MethodNotAllowed("GET, PUT, DELETE");
            }
            switch (child) {
                case "history" -> {
                    operation = "history"; requireMethod(exchange, "GET");
                    require(context, AuthorizationAction.GRAPH_READ, documentId);
                    write(exchange, 200, page(await(repository.history(actor, documentId, query(exchange).get("cursor")))));
                }
                case "diff" -> {
                    operation = "diff"; requireMethod(exchange, "GET");
                    require(context, AuthorizationAction.GRAPH_READ, documentId);
                    var query = query(exchange);
                    write(exchange, 200, difference(await(repository.diff(actor, documentId,
                            required(query, "from"), required(query, "to")))));
                }
                case "restore" -> {
                    operation = "restore"; requireMethod(exchange, "POST");
                    require(context, AuthorizationAction.GRAPH_WRITE, documentId);
                    var value = await(repository.restore(actor, mutation(exchange, documentId,
                            required(query(exchange), "revision"))));
                    write(exchange, 200, document(value)); audited(http, actor, operation, documentId, "success");
                }
                case "discard" -> {
                    operation = "discard"; requireMethod(exchange, "POST");
                    require(context, AuthorizationAction.GRAPH_WRITE, documentId);
                    write(exchange, 200, document(await(repository.discardDraft(actor,
                            mutation(exchange, documentId, "")))));
                    audited(http, actor, operation, documentId, "success");
                }
                case "release" -> {
                    operation = "release"; requireMethod(exchange, "POST");
                    require(context, AuthorizationAction.GRAPH_RELEASE, documentId);
                    write(exchange, 200, proposal(await(repository.proposeRelease(actor,
                            mutation(exchange, documentId, "")))));
                    audited(http, actor, operation, documentId, "success");
                }
                default -> throw new NotFound();
            }
        } catch (MethodNotAllowed failure) {
            exchange.getResponseHeaders().set("Allow", failure.allow);
            error(exchange, 405, "METHOD_NOT_ALLOWED");
        } catch (NotFound failure) {
            error(exchange, 404, "UNKNOWN_RESOURCE");
        } catch (AuthorizationDeniedException failure) {
            audited(http, actor, operation, suffix, "denied");
            error(exchange, 403, "FORBIDDEN");
        } catch (GraphAuthoringException failure) {
            audited(http, actor, operation, suffix, failure.failure().name());
            error(exchange, status(failure.failure()), failure.failure().name());
        } catch (IllegalArgumentException failure) {
            error(exchange, 400, "INVALID_REQUEST");
        } catch (TimeoutException failure) {
            error(exchange, 503, "AUTHORING_UNAVAILABLE");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error(exchange, 503, "REQUEST_INTERRUPTED");
        } catch (ExecutionException | CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof GraphAuthoringException classified) {
                audited(http, actor, operation, suffix, classified.failure().name());
                error(exchange, status(classified.failure()), classified.failure().name());
            } else error(exchange, 503, "AUTHORING_UNAVAILABLE");
        }
    }

    @Override public void close() { repository.close(); }

    private void require(ai.ravenroot.api.security.RequestContext context, AuthorizationAction action, String id) {
        authorization.requireAllowed(context, action, ProtectedResource.owned("graph-authoring", id, context.tenantId()));
    }
    private <T> T await(java.util.concurrent.CompletionStage<T> stage)
            throws ExecutionException, InterruptedException, TimeoutException {
        return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    private static GraphAuthoringRepository.MutationRequest mutation(HttpExchange exchange, String id, String source) {
        return new GraphAuthoringRepository.MutationRequest(id, source, expected(exchange),
                requiredHeader(exchange, "Idempotency-Key"));
    }
    private static GraphAuthoringRepository.Revision expected(HttpExchange exchange) {
        return new GraphAuthoringRepository.Revision(requiredHeader(exchange, "X-Ravenroot-Expected-Draft"),
                requiredHeader(exchange, "X-Ravenroot-Expected-Release"),
                requiredHeader(exchange, "X-Ravenroot-Expected-Publication"));
    }
    private static String requiredHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException(name);
        return value;
    }
    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) throw new MethodNotAllowed(method);
    }
    private static byte[] readBounded(HttpExchange exchange, int limit) throws IOException {
        try (var input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new GraphAuthoringException(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            return bytes;
        }
    }
    private static List<String> segments(String suffix) {
        var result = new ArrayList<String>();
        for (String value : suffix.split("/")) if (!value.isBlank()) {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.contains("/") || decoded.contains("\\") || decoded.equals(".") || decoded.equals("..")) throw new NotFound();
            result.add(decoded);
        }
        return result;
    }
    private static Map<String, String> query(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(equals < 0 ? "" : part.substring(equals + 1), StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate query");
        }
        return result;
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key); return value;
    }
    private static Map<String, Object> document(GraphAuthoringRepository.Document value) {
        var result = summary(value.summary());
        result.put("graphMl", Base64.getEncoder().encodeToString(value.graphMl()));
        return result;
    }
    private static Map<String, Object> summary(GraphAuthoringRepository.DocumentSummary value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("documentId", value.documentId()); result.put("displayPath", value.displayPath());
        result.put("graphId", value.graphId()); result.put("releaseVersion", value.releaseVersion());
        result.put("revision", revision(value.revision())); result.put("released", value.released());
        result.put("published", value.published()); result.put("draftDeleted", value.draftDeleted()); return result;
    }
    private static Map<String, Object> revision(GraphAuthoringRepository.Revision value) {
        return Map.of("draft", value.draft(), "release", value.release(), "publication", value.publication());
    }
    private static Map<String, Object> page(GraphAuthoringRepository.Page<?> value) {
        List<Object> items = value.items().stream().map(item -> {
            if (item instanceof GraphAuthoringRepository.DocumentSummary summary) return summary(summary);
            if (item instanceof GraphAuthoringRepository.HistoryEntry entry) return Map.of("revision", entry.revision(),
                    "summary", entry.summary(), "author", entry.author(), "authoredAt", entry.authoredAt().toString());
            return item;
        }).toList();
        return Map.of("items", items, "nextCursor", value.nextCursor());
    }
    private static Map<String, Object> difference(GraphAuthoringRepository.Difference value) {
        return Map.of("fromRevision", value.fromRevision(), "toRevision", value.toRevision(),
                "unifiedDiff", value.unifiedDiff(), "truncated", value.truncated());
    }
    private static Map<String, Object> proposal(GraphAuthoringRepository.ReleaseProposal value) {
        return Map.of("url", value.url(), "draftHead", value.draftHead(), "releaseHead", value.releaseHead(), "reused", value.reused());
    }
    private static void write(HttpExchange exchange, int status, Object value) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        if (status == 204) { exchange.sendResponseHeaders(status, -1); return; }
        byte[] body = PayloadJson.writeJava(value, JSON_LIMITS);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        write(exchange, status, Map.of("error", code));
    }
    private void audited(HttpRequestContext http, GraphAuthoringRepository.Actor actor, String operation,
                         String resource, String outcome) {
        audit.accept("{\"type\":\"graph_authoring\",\"requestId\":\"" + safe(http.requestId())
                + "\",\"tenantHash\":\"" + Integer.toHexString(actor.tenantId().hashCode())
                + "\",\"actorHash\":\"" + Integer.toHexString(actor.subject().hashCode())
                + "\",\"operation\":\"" + safe(operation) + "\",\"resource\":\"" + safe(resource)
                + "\",\"outcome\":\"" + safe(outcome) + "\"}");
    }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9._/-]", "_"); }
    private static int status(GraphAuthoringException.Failure failure) {
        return switch (failure) {
            case NOT_FOUND -> 404; case CONFLICT -> 409; case UNAUTHORIZED -> 403;
            case INVALID_DOCUMENT -> 422; case LIMIT_EXCEEDED -> 413;
            case UNSUPPORTED_PROVIDER -> 501; case PUBLICATION_REQUIRED -> 412; case UNAVAILABLE -> 503;
        };
    }
    private static final class NotFound extends RuntimeException { }
    private static final class MethodNotAllowed extends RuntimeException {
        private final String allow; private MethodNotAllowed(String allow) { this.allow = allow; }
    }
}
