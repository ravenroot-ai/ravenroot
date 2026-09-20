package ai.ravenroot.cli;

import ai.ravenroot.cli.remote.RemoteBackend;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerPlaneCommandTest {
    @Test void operatorCommandsUseAuthenticatedExactResourcesAndNeverWorkerDispatch(@TempDir Path directory) throws Exception {
        var seen = new ArrayList<String>(); var bodies = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runner-plane/", exchange -> {
            try (exchange) {
                assertEquals("Bearer conformance-identity", exchange.getRequestHeaders().getFirst("Authorization"));
                seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                bodies.add(new String(exchange.getRequestBody().readAllBytes()));
                byte[] result = "{\"result\":\"direct answer\",\"driver\":\"KUBERNETES\",\"kubernetes\":{\"cluster\":\"approved\",\"podUid\":\"11111111-1111-4111-8111-111111111111\",\"phase\":\"UNKNOWN\"}}".getBytes();
                exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result);
            }
        });
        server.start();
        try {
            var backend = new RemoteBackend(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "conformance-identity", Duration.ofSeconds(2));
            String process = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
            var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
            var catalog = directory.resolve("catalog.json"); Files.writeString(catalog, "{\"expectedRevision\":7,\"approved\":false}");
            for (var arguments : List.of(new String[]{"runner", "catalog"}, new String[]{"runner", "catalog", "AGENT_DEFINITION:polaris:1"},
                    new String[]{"runner", "availability"}, new String[]{"runner", "workspace", process},
                    new String[]{"runner", "stop-workspace", process, "repository", "17"},
                    new String[]{"runner", "cancel-job", process, job}, new String[]{"runner", "reconcile-job", process, job},
                    new String[]{"runner", "resolve-job", process, job, "19", "ACKNOWLEDGE"},
                    new String[]{"runner", "artifact", process, job, UUID.randomUUID().toString()}, new String[]{"runner", "publish", catalog.toString()}))
                assertEquals(0, RunnerPlaneCommand.run(arguments, backend, new PrintStream(output), new PrintStream(errors)), errors.toString());
            assertEquals(10, seen.size());
            assertTrue(seen.get(4).endsWith("/resources/repository/abort")); assertTrue(bodies.get(4).contains("17"));
            assertTrue(bodies.get(7).contains("ACKNOWLEDGE")); assertTrue(seen.get(9).startsWith("PUT "));
            assertTrue(output.toString().contains("direct answer")); assertFalse(output.toString().contains("conformance-identity"));
            assertTrue(output.toString().contains("KUBERNETES")); assertTrue(output.toString().contains("podUid"));
            assertTrue(output.toString().contains("UNKNOWN"));
            for (var arguments : List.of(new String[]{"runner", "claim", process, job}, new String[]{"runner", "workspace", "../graphs"},
                    new String[]{"runner", "stop-workspace", process, "../repository", "17"}, new String[]{"runner", "resolve-job", process, job, "19", "RETRY"}))
                assertEquals(1, RunnerPlaneCommand.run(arguments, backend, new PrintStream(output), new PrintStream(errors)));
            assertEquals(10, seen.size(), "invalid input must never issue a request");
        } finally { server.stop(0); }
    }
}
