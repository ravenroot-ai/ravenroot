package ai.ravenroot.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** Serves the optional Ravenroot UI without introducing a second runtime process. */
final class StaticUiHandler implements HttpHandler {
    private final Path externalDirectory;
    private final ClassLoader classLoader;

    StaticUiHandler(Path externalDirectory) {
        this.externalDirectory = externalDirectory == null
                ? null
                : externalDirectory.toAbsolutePath().normalize();
        this.classLoader = StaticUiHandler.class.getClassLoader();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        // The path is judged before the method. A request under /v1 never belongs to this
        // static-asset fallback -- it means the API route table has no context for it (typically a
        // misconfigured base URL that puts the client outside /v1 entirely), and the correct answer
        // is 404 "no such route" regardless of method. Answering 405 here for e.g. a POST asserts a
        // false and misleading cause: that the method was wrong, when the method was never evaluated
        // -- the place was. No Allow header is set: advertising GET/HEAD/OPTIONS as the allowed
        // methods for a route that does not exist here is noise, not information.
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/v1") || requestPath.startsWith("/v1/")) {
            reply(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"not found\"}", "HEAD".equals(method));
            return;
        }

        if ("OPTIONS".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            reply(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method not allowed\"}", false);
            return;
        }

        String relativePath = normalize(requestPath);
        if (relativePath == null) {
            reply(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"invalid path\"}", "HEAD".equals(method));
            return;
        }

        Asset asset = load(relativePath);
        if (asset == null && isApplicationRoute(relativePath)) {
            relativePath = "index.html";
            asset = load(relativePath);
        }
        if (asset == null) {
            reply(exchange, 404, "application/json; charset=utf-8",
                    "{\"error\":\"UI asset not found; API remains available under /v1\"}", "HEAD".equals(method));
            return;
        }

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(relativePath));
        headers.set("Cache-Control", relativePath.startsWith("assets/")
                ? "public, max-age=31536000, immutable"
                : "no-cache");
        headers.set("Content-Length", Integer.toString(asset.bytes().length));
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, asset.bytes().length);
        try (var output = exchange.getResponseBody()) {
            output.write(asset.bytes());
        }
    }

    private String normalize(String requestPath) {
        String candidate = requestPath == null || requestPath.equals("/")
                ? "index.html"
                : requestPath.substring(1);
        if (candidate.indexOf('\\') >= 0 || candidate.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path normalized = Path.of(candidate).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) {
                return null;
            }
            String result = normalized.toString().replace('\\', '/');
            return result.isBlank() ? "index.html" : result;
        } catch (InvalidPathException error) {
            return null;
        }
    }

    private Asset load(String relativePath) throws IOException {
        if (externalDirectory != null) {
            Path file = externalDirectory.resolve(relativePath).normalize();
            if (file.startsWith(externalDirectory) && Files.isRegularFile(file)) {
                return new Asset(Files.readAllBytes(file));
            }
        }
        try (InputStream input = classLoader.getResourceAsStream("ui/" + relativePath)) {
            return input == null ? null : new Asset(input.readAllBytes());
        }
    }

    private static boolean isApplicationRoute(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        int dot = relativePath.lastIndexOf('.');
        return dot <= slash;
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json; charset=utf-8";
        if (lower.endsWith(".graphml") || lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body, boolean head)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record Asset(byte[] bytes) {
    }
}
