package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.*;

/**
 * Replicable runner control plane. No graph engine, authoring credentials, consent, program
 * registry, embedded browser or local audit-file authority is constructed here. Accepted reports
 * are delivered by the graph authority's durable recovery sweep, never a coordinator-local callback.
 */
public final class RunnerCoordinator implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    public RunnerCoordinator(InetSocketAddress address, RequestAuthenticator authentication,
                             AuthorizationService authorization, RunnerJobService jobs, String issuer,
                             RunnerArtifactStore sharedArtifacts, Clock clock, int threads, int queue) throws IOException {
        if (threads < 1 || queue < 1) throw new IllegalArgumentException("positive coordinator HTTP capacities required");
        server = HttpServer.create(address, queue);
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                Thread.ofPlatform().daemon(true).name("runner-coordinator-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        var api = new RunnerPlaneHttpApi(new AuthorizedRunnerControl(jobs, authorization, issuer, sharedArtifacts, clock), null);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith("/v1/runner-plane/")) {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                var context = HttpRequestContext.create();
                try {
                    var principal = authentication.authenticate(exchange.getRequestHeaders());
                    api.handle(exchange, context.withPrincipal(principal));
                } catch (ai.ravenroot.server.security.AuthenticationException denied) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    exchange.sendResponseHeaders(401, -1);
                } catch (RuntimeException failed) {
                    // Neither a JDBC diagnostic nor configuration/credential material reaches a caller.
                    exchange.sendResponseHeaders(503, -1);
                }
            }
        });
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
