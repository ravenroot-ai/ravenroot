package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RunnerWorkerTest {
    @Test void designatedWorkerRunsIndependentProcessesConcurrentlyWithinFourSlots() throws Exception {
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(5), 64_000_000, 1, 1_000_000, 4096, 1024, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "reader", 1), "Read only", "reference", "none",
                Map.of("read", new AgentCommand("read", true, policy, Set.of("answered"))), Set.of(), Set.of(), policy, Duration.ZERO, "object");
        var registration = new RunnerRegistration(1, "tenant", "worker", "sandboxed", Set.of(), policy);
        var assignments = new ConcurrentHashMap<UUID, RunnerAssignment>();
        var futures = new LinkedHashMap<UUID, CompletableFuture<RunnerResult>>();
        var claims = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            var id = new RunnerJobIdentity(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var now = Instant.now();
            var job = RunnerJob.accept(id, definition, "read", policy, registration, OpaquePayload.of("{}".getBytes(), "application/json"), now, now.plusSeconds(300));
            assignments.put(id.runnerJobId(), new RunnerAssignment(1, UUID.randomUUID(), job));
        }
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/runner-plane/", exchange -> {
            try {
                if (!"Bearer test-workload".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1); return;
                }
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath().substring("/v1/runner-plane/".length());
                byte[] response;
                if (path.equals("assignments")) {
                    response = RunnerJson.write(Map.of("items", assignments.values().stream()
                            .filter(value -> !value.job().state().terminal()).map(value -> RunnerJson.job(value.job())).toList()));
                } else {
                    String[] parts = path.split("/"); UUID id = UUID.fromString(parts[3]);
                    var assignment = assignments.get(id); var job = assignment.job();
                    if (path.endsWith("/claim")) { claims.incrementAndGet(); job = job.claim("worker", Instant.now(), Duration.ofSeconds(30)); }
                    if (path.endsWith("/complete")) job = job.complete("worker", job.fence(),
                            new RunnerResult("answered", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), id), Instant.now());
                    assignment = new RunnerAssignment(1, assignment.workspaceId(), job); assignments.put(id, assignment);
                    response = RunnerCodec.assignment(assignment);
                }
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        var driver = new RunnerDriver() {
            public RunnerRegistration registration() { return registration; }
            public CompletionStage<RunnerResult> execute(RunnerAssignment assignment) {
                var future = new CompletableFuture<RunnerResult>(); futures.put(assignment.job().identity().runnerJobId(), future); return future;
            }
            public CompletionStage<Void> cancel(RunnerAssignment assignment) { return CompletableFuture.completedFuture(null); }
            public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) { throw new AssertionError("queued jobs execute once"); }
            public void close() { }
        };
        try (var worker = new RunnerWorker(new RemoteRunnerClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), () -> "test-workload"), driver)) {
            worker.tick(); assertEquals(4, worker.activeJobs()); assertEquals(4, futures.size());
            assertEquals(4, futures.keySet().stream().map(id -> assignments.get(id).workspaceId()).distinct().count());
            worker.tick(); assertEquals(4, claims.get(), "full capacity does not claim another execution");
            var first = futures.entrySet().iterator().next();
            first.getValue().complete(new RunnerResult("answered", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), first.getKey()));
            assertEquals(3, worker.activeJobs());
            worker.tick(); assertEquals(4, worker.activeJobs()); assertEquals(5, futures.size()); assertEquals(5, claims.get());
            assertEquals(0, worker.protocolFailures());
        } finally { server.stop(0); }
    }
}
