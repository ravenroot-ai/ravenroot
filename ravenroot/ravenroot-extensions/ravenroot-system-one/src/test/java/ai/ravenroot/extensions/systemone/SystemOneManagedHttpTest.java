package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Exercises the bundle through the same managed socket boundary used by an activated package. */
class SystemOneManagedHttpTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = SystemOneTestSupport.fixture("jev-response.json");
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mixedRequestCrossesTheGrantedManagedChannelOnce() throws Exception {
        NodeAction action = action(services(server.getAddress().getPort()));

        var result = action.handle(SystemOneTestSupport.message(Map.of("body", "synthetic email")))
                .toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertEquals("continue", result.outcome());
        assertEquals(1, calls.get());
        Map<?, ?> request = assertInstanceOf(Map.class, ai.ravenroot.api.payload.PayloadJson.read(
                body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava());
        assertEquals(SystemOneProfile.PORTABLE_TYPES.size(), ((Map<?, ?>) request.get("questions")).size());
        assertEquals("fixture-model", request.get("model"));
    }

    @Test
    void originOutsideTheOperatorGrantIsRefusedBeforeTheListenerIsReached() {
        NodeAction action = action(services(server.getAddress().getPort() + 1));

        ExecutionException raised = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> action.handle(SystemOneTestSupport.message(Map.of("body", "synthetic email")))
                        .toCompletableFuture().get());

        assertEquals(SystemOneException.Code.DESTINATION_REFUSED,
                assertInstanceOf(SystemOneException.class, raised.getCause()).code());
        assertEquals(0, calls.get());
    }

    private NodeAction action(ai.ravenroot.api.node.service.NodePackageServices services) {
        SystemOneProfile defaults = SystemOneTestSupport.profile("fixture-model");
        SystemOneProfile local = new SystemOneProfile(defaults.name(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/systemone"),
                defaults.model(), Optional.empty(), defaults.timeoutMs(), defaults.maxRequestBytes(),
                defaults.maxResponseBytes(), defaults.maxConcurrency(), defaults.maxStateBytes(),
                defaults.maxQuestions(), defaults.maxInstructionBytes(), defaults.maxChoiceOptions(),
                defaults.maxScoreLevels(), defaults.supportedTypes(), defaults.probabilityTolerance(), null);
        return new SystemOneEvaluateNodeBehavior(SystemOneTestSupport.resolving(local))
                .create(SystemOneTestSupport.configuration(SystemOneTestSupport.SPECIFICATION), services);
    }

    private ai.ravenroot.api.node.service.NodePackageServices services(int grantedPort) {
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", "localhost", grantedPort)
                .allowHttpMethod("POST")
                .allowRequestHeader("content-type")
                .allowResponseHeader("content-type")
                .byteLimits(1024 * 1024, 1024 * 1024, 1024 * 1024)
                .concurrencyLimits(4, 2)
                .maximumDeadline(Duration.ofSeconds(10))
                .build();
        return ManagedNodePackageServices.builder(SystemOneNodePackage.ID, policy,
                        (packageId, tenant, reference) -> Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .build();
    }
}
