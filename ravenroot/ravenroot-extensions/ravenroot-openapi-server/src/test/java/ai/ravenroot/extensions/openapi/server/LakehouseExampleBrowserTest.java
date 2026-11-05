package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.ingress.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.server.LakehouseExampleHarness;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Executed public graph: real managed HTTP, runtime, SQLite, external effects and optional Chromium. */
class LakehouseExampleBrowserTest {
    @Test void concurrentFilesKeepTheirActualBronzeSparkSilverGoldStoryAcrossSixResults() throws Exception {
        // Load the shipped header-budget bootstrap before the external fixture opens a JDK listener.
        Class.forName("ai.ravenroot.server.RavenrootServer");
        Path repository = Path.of(System.getProperty("basedir"), "../../..").toAbsolutePath().normalize();
        Path examples = repository.resolve("docs/examples/correlation"), ui = repository.resolve("ravenroot/ravenroot-ui");
        Path output = Files.createDirectories(Path.of("target", "lakehouse-example", UUID.randomUUID().toString())).toAbsolutePath();
        try (var external = new ExternalProcessor(output.resolve("lake"))) {
            byte[] specification = Files.readAllBytes(examples.resolve("lakehouse-openapi.json"));
            var profile = new OpenApiServerProfile("lakehouse-landing", specification, OpenApiServerTestSupport.sha256(specification), "/lakehouse-landing",
                    Set.of("landFile"), Set.of("USER"), "idempotency-key", null, 8192, 128, 10000, 16);
            var silverProfile = new OpenApiServerProfile("lakehouse-silver", specification, OpenApiServerTestSupport.sha256(specification), "/lakehouse-silver",
                    Set.of("silverPartition"), Set.of("USER"), "idempotency-key", null, 8192, 128, 10000, 16);
            var configuration = new OpenApiServerConfiguration(new IngressAuthorityDeclaration(OpenApiServerConfiguration.PACKAGE_ID,
                    "main", "/managed/openapi", Set.of(ai.ravenroot.api.security.AuthorizationAction.EXECUTION_START.requiredScope()), 8, 32, 16384, 4096, Duration.ofSeconds(15)),
                    new IngressRequestProjectionPolicy(OpenApiServerConfiguration.PACKAGE_ID, Set.of("content-type", "idempotency-key", "x-business-operation"),
                            "idempotency-key", 1024, 32, 2048, 8, 2048, 256), Map.of("lakehouse-landing", profile, "lakehouse-silver", silverProfile));
            var nodePackage = new OpenApiServerNodePackage(() -> Optional.of(configuration));
            String graph = Files.readString(examples.resolve("lakehouse-six-partitions.graphml"))
                    .replace("http://localhost:19090", external.origin());
            try (var host = new LakehouseExampleHarness(nodePackage, graph.getBytes(StandardCharsets.UTF_8), output, ui, external.port())) {
                external.host.set(host);
                var scope = new BusinessScope("local", "lakehouse-example", host.version());
                assertTrue(host.store.initialStart("local", "lakehouse-example").toCompletableFuture().get().isEmpty(), "Deploy never runs START");
                @SuppressWarnings("unchecked") var inputs = (List<Map<String, Object>>) (List<?>) PayloadJson.read(Files.readAllBytes(examples.resolve("lakehouse-events.json")), PayloadLimits.DEFAULTS).toJava();
                var submissions = new ArrayList<CompletableFuture<Void>>();
                for (var input : inputs) submissions.add(CompletableFuture.runAsync(() -> external.land(input)));
                CompletableFuture.allOf(submissions.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                assertTrue(external.sparkReady.await(20, TimeUnit.SECONDS), "actual bronze/Spark submission did not execute");
                for (int part : new int[]{4, 2, 1, 3}) for (var input : inputs) external.silver(input, part);
                for (int delivery = 0; delivery < 5; delivery++) external.silver(inputs.getFirst(), 1);
                for (var input : inputs) {
                    String id = (String) input.get("ingestionId");
                    await(() -> join(host, scope, id).map(value -> value.contributions().size() == 4).orElse(false));
                    var partial = join(host, scope, id).orElseThrow();
                    var missing = new HashSet<>(partial.expectedIds()); missing.removeAll(partial.contributions().keySet());
                    assertEquals(Set.of("part-5", "part-6"), missing);
                    assertEquals(5, host.store.correlatedTraversals(scope, id, Optional.empty(), 10).toCompletableFuture().get().items().size(), "transport retries must not invent traversals");
                    assertFalse(Files.exists(external.lake.resolve(id).resolve("gold.txt")));
                }
                assertEquals(3, external.jobs.size());
                if (Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest")) {
                    assertTrue(Files.isDirectory(ui.resolve("dist")), "browser proof requires built UI");
                    var process = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(), "test", "lakehouse-example.spec.js", "--config=playwright.deployment-persistence.config.js")
                            .directory(ui.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("playwright.log").toFile());
                    process.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN", host.uri("").toString());
                    process.environment().put("RAVENROOT_LAKEHOUSE_EXTERNAL", external.origin());
                    process.environment().put("RAVENROOT_HISTORY_EVIDENCE_DIR", output.toString());
                    process.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR", output.resolve("browser-results").toString());
                    var browser = process.start();
                    try { assertTrue(browser.waitFor(120, TimeUnit.SECONDS), "Chromium timeout: " + output);
                        assertEquals(0, browser.exitValue(), () -> read(output.resolve("playwright.log"))); }
                    finally { if (browser.isAlive()) browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
                } else external.completeAfterReconnect();
                for (var input : inputs) {
                    String id = (String) input.get("ingestionId");
                    await(() -> Files.exists(external.lake.resolve(id).resolve("gold.txt")));
                    await(() -> {
                        try { return host.store.businessHistory(scope, id).toCompletableFuture().get().orElseThrow().state() == BusinessHistory.State.COMPLETED; }
                        catch (Exception failure) { throw new CompletionException(failure); }
                    });
                    var record = join(host, scope, id).orElseThrow(); assertEquals(6, record.contributions().size());
                    assertEquals(CorrelatedJoinRecord.Phase.DISPATCHED, record.phase());
                    assertEquals(1, host.store.correlatedTraversals(scope, id, Optional.empty(), 20).toCompletableFuture().get().items().stream()
                            .filter(value -> value.cause() == CorrelatedTraversal.Cause.JOIN_CONTINUATION).count());
                    assertEquals(1, external.goldCounts.get(id).intValue());
                }
                Files.writeString(output.resolve("execution-evidence.json"), "{\"concurrentFiles\":3,\"duplicateFilenameIngestions\":2,\"partitions\":6,\"initialPartial\":4,\"duplicateDeliveries\":5,\"sourceReconnect\":true,\"storeSeededEffects\":false,\"browser\":" + Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest") + "}");
                assertTrue(host.store.initialStart("local", "lakehouse-example").toCompletableFuture().get().isEmpty(), "source callbacks never consume initial START");
            }
        }
        System.out.println("lakehouse executed example evidence=" + output);
    }
    private static Optional<CorrelatedJoinRecord> join(LakehouseExampleHarness host, BusinessScope scope, String id) {
        return host.store.correlatedJoin(new CorrelatedJoinKey(scope, id, "six-partitions", "round-1")).toCompletableFuture().join();
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(ready.getAsBoolean(), "bounded example wait expired");
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return failure.toString(); } }

    /** Bounded cooperating external processor; it writes real files, without claiming Apache Spark. */
    private static final class ExternalProcessor implements AutoCloseable {
        final HttpServer service; final Path lake; final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicReference<LakehouseExampleHarness> host = new AtomicReference<>();
        final ConcurrentMap<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Integer> goldCounts = new ConcurrentHashMap<>();
        final CountDownLatch sparkReady = new CountDownLatch(3);
        ExternalProcessor(Path lake) throws Exception {
            this.lake = Files.createDirectories(lake); service = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0); service.setExecutor(executor);
            service.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath(); byte[] body = exchange.getRequestBody().readNBytes(8193);
                    if (body.length > 8192) { exchange.sendResponseHeaders(413, -1); return; }
                    if (path.equals("/release")) { completeAfterReconnect(); byte[] result = "released".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result); return; }
                    String id = exchange.getRequestHeaders().getFirst("X-Business-Operation");
                    if (id == null || !id.matches("ingest-[a-z0-9-]{1,50}")) { exchange.sendResponseHeaders(400, -1); return; }
                    Path operation = Files.createDirectories(lake.resolve(id));
                    if (path.equals("/gold")) {
                        for (int part = 1; part <= 6; part++) if (!Files.exists(operation.resolve("part-" + part + ".json"))) throw new IllegalStateException("missing actual silver file");
                        goldCounts.merge(id, 1, Integer::sum); Files.write(operation.resolve("gold.txt"), body);
                    } else {
                        @SuppressWarnings("unchecked") var input = (Map<String, Object>) PayloadJson.read(body, PayloadLimits.DEFAULTS).toJava();
                        if (!id.equals(input.get("ingestionId"))) throw new IllegalStateException("business header/body conflict");
                        switch (path) {
                            case "/bronze" -> Files.write(operation.resolve("bronze.json"), body);
                            case "/spark" -> { if (jobs.putIfAbsent(id, Map.copyOf(input)) == null) sparkReady.countDown(); }
                            case "/silver" -> {
                                String part = Objects.toString(input.get("partition")); if (!part.matches("part-[1-6]")) throw new IllegalStateException("invalid part");
                                if (!part.equals(exchange.getRequestHeaders().getFirst("X-Business-Part"))) throw new IllegalStateException("contribution header lost");
                                Files.write(operation.resolve(part + ".json"), body);
                            }
                            default -> throw new IllegalStateException("unknown external effect");
                        }
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Throwable failed) { byte[] message = failed.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(500, message.length); exchange.getResponseBody().write(message); }
                finally { exchange.close(); }
            }); service.start();
        }
        int port() { return service.getAddress().getPort(); }
        String origin() { return "http://localhost:" + port(); }
        void land(Map<String, Object> input) { post("/landing", input, "land-" + input.get("ingestionId")); }
        void silver(Map<String, Object> input, int part) {
            var callback = new LinkedHashMap<>(input); callback.put("stage", "silver"); callback.put("partition", "part-" + part);
            post("/silver", callback, "silver-" + input.get("ingestionId") + "-" + part);
        }
        synchronized void completeAfterReconnect() throws Exception {
            host.get().reconnectSources();
            for (int part : new int[]{6, 5}) for (var job : jobs.values()) {
                var input = new LinkedHashMap<>(job); input.put("stage", "bronze"); silver(input, part);
            }
            // The external test-control reply means the asynchronous effects actually settled,
            // not merely that their source receipts were returned. The browser then performs one
            // explicit bounded refresh instead of relying on an accidental scheduling race.
            for (String id : jobs.keySet()) await(() -> {
                var current = host.get();
                var scope = new BusinessScope("local", "lakehouse-example", current.version());
                return Files.exists(lake.resolve(id).resolve("gold.txt"))
                        && current.store.businessHistory(scope, id).toCompletableFuture().join()
                        .map(history -> history.state() == BusinessHistory.State.COMPLETED).orElse(false);
            });
        }
        void post(String path, Map<String, Object> body, String key) {
            try {
                String json = PayloadJson.write(PayloadValue.fromJava(body, PayloadLimits.DEFAULTS));
                var request = HttpRequest.newBuilder(host.get().uri("/managed/openapi/" + (path.equals("/landing") ? "lakehouse-landing" : "lakehouse-silver") + path)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json").header("Idempotency-Key", key).header("X-Business-Operation", (String) body.get("ingestionId"))
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
                var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 202) throw new IllegalStateException("source receipt=" + response.statusCode() + " " + response.body());
            } catch (Exception failure) { throw new CompletionException(failure); }
        }
        @Override public void close() { service.stop(0); executor.close(); }
    }
}
