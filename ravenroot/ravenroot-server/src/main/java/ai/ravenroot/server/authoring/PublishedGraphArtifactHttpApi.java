package ai.ravenroot.server.authoring;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.server.HttpRequestContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lists, imports, and registers only digest-verified CI publications; no Git ref is executable here. */
public final class PublishedGraphArtifactHttpApi {
    private static final PayloadLimits LIMITS = new PayloadLimits(256 * 1024, 12, 1000, 10_000, 128 * 1024, 256);
    private final PublishedGraphArtifactCatalog catalog;
    private final AuthorizedRavenrootApplication application;
    private final AuthorizationService authorization;

    public PublishedGraphArtifactHttpApi(PublishedGraphArtifactCatalog catalog,
            AuthorizedRavenrootApplication application, AuthorizationService authorization) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.application = java.util.Objects.requireNonNull(application, "application");
        this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
    }

    public void handle(HttpExchange exchange, HttpRequestContext http) throws IOException {
        var context = http.applicationContext();
        String suffix = exchange.getRequestURI().getPath().substring("/v1/graph-artifacts".length());
        try {
            if (suffix.isEmpty() || "/".equals(suffix)) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
                authorization.requireAllowed(context, AuthorizationAction.GRAPH_READ,
                        ProtectedResource.collection("published-graphs", context.tenantId()));
                var items = catalog.list(context.tenantId()).stream().map(value -> Map.of(
                        "graphId", value.graphId(), "releaseVersion", value.releaseVersion(),
                        "sha256", value.graphMlSha256(), "compatibilityContract", value.compatibilityContract(),
                        "dependencies", value.dependencies())).toList();
                write(exchange, 200, Map.of("items", items)); return;
            }
            String[] parts = suffix.split("/");
            if (parts.length != 4 || parts[1].isBlank() || parts[2].isBlank()
                    || !("import".equals(parts[3]) || "deploy".equals(parts[3]))
                    || !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                error(exchange, 404, "UNKNOWN_RESOURCE"); return;
            }
            String graphId = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            long version = Long.parseLong(parts[2]);
            authorization.requireAllowed(context, AuthorizationAction.GRAPH_ARTIFACT_DEPLOY,
                    ProtectedResource.collection("published-graphs", context.tenantId()));
            var artifact = catalog.resolve(context.tenantId(), graphId, version);
            var identity = new GraphDefinitionIdentity(artifact.graphId(), Long.toString(artifact.releaseVersion()));
            if ("import".equals(parts[3])) {
                var stored = application.importPublishedGraphDefinition(context, identity,
                        new ByteArrayInputStream(artifact.graphMl()));
                write(exchange, 200, Map.of("graphId", identity.graphId(),
                        "releaseVersion", artifact.releaseVersion(), "sha256", artifact.graphMlSha256(),
                        "contentId", stored.canonical().contentId().value(), "state", "PINNED"));
                return;
            }
            String deploymentId = required(query(exchange), "id");
            var status = application.registerPinnedLocalDeployment(context, deploymentId, identity);
            write(exchange, 200, Map.of("deploymentId", status.deploymentId(), "state", status.state().name(),
                    "scope", ai.ravenroot.api.application.LocalDeploymentStatus.SCOPE, "graphId", artifact.graphId(),
                    "releaseVersion", artifact.releaseVersion(), "sha256", artifact.graphMlSha256()));
        } catch (AuthorizationDeniedException denied) {
            error(exchange, 403, "FORBIDDEN");
        } catch (GraphAuthoringException failure) {
            error(exchange, switch (failure.failure()) {
                case NOT_FOUND -> 404; case CONFLICT -> 409; case INVALID_DOCUMENT -> 422;
                case LIMIT_EXCEEDED -> 413; case UNAUTHORIZED -> 403; default -> 503;
            }, failure.failure().name());
        } catch (ai.ravenroot.api.persistence.GraphDefinitionStoreException failure) {
            var classified = failure.failure();
            if (classified instanceof ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.NotFound) {
                error(exchange, 412, "PUBLISHED_DEFINITION_NOT_IMPORTED");
            } else if (classified instanceof ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.IdentityConflict) {
                error(exchange, 409, "PUBLISHED_DEFINITION_CONFLICT");
            } else if (classified instanceof ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.DefinitionTooLarge) {
                error(exchange, 413, "PUBLISHED_DEFINITION_TOO_LARGE");
            } else if (classified instanceof ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.NotAuthorized) {
                error(exchange, 403, "FORBIDDEN");
            } else if (classified instanceof ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.InvalidRequest) {
                error(exchange, 422, "INVALID_DOCUMENT");
            } else {
                error(exchange, 503, "PUBLISHED_DEFINITION_UNAVAILABLE");
            }
        } catch (ai.ravenroot.core.graph.GraphMlParseException
                 | ai.ravenroot.core.graph.GraphMlCompatibilityException
                 | ai.ravenroot.api.application.GraphAdmissionException failure) {
            error(exchange, 422, "INVALID_DOCUMENT");
        } catch (ai.ravenroot.api.application.LocalDeploymentException failure) {
            error(exchange, failure.reason() == ai.ravenroot.api.application.LocalDeploymentException.Reason.GRAPH_CONFLICT
                    ? 409 : 422, failure.reason() == ai.ravenroot.api.application.LocalDeploymentException.Reason.GRAPH_CONFLICT
                    ? "DEPLOYMENT_CONFLICT" : "INVALID_DEPLOYMENT");
        } catch (ai.ravenroot.api.deployment.DeploymentAdmissionException failure) {
            error(exchange, 429, "DEPLOYMENT_LIMIT_EXCEEDED");
        } catch (UnsupportedOperationException failure) {
            error(exchange, 501, "IMMUTABLE_DEFINITION_STORE_REQUIRED");
        } catch (IllegalArgumentException failure) {
            error(exchange, 400, "INVALID_REQUEST");
        } catch (RuntimeException failure) {
            error(exchange, 500, "ARTIFACT_OPERATION_FAILED");
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>(); String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return result;
        for (String part : raw.split("&")) { int at = part.indexOf('=');
            String key = URLDecoder.decode(at < 0 ? part : part.substring(0, at), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(at < 0 ? "" : part.substring(at + 1), StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate query"); }
        return result;
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key); return value;
    }
    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        write(exchange, status, Map.of("error", code));
    }
    private static void write(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = PayloadJson.writeJava(value, LIMITS);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
