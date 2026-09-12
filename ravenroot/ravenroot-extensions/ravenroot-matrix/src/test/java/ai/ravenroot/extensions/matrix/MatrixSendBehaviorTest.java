package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MatrixSendBehaviorTest {
    @TempDir Path directory;
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void putUsesStableTransactionPathManagedCredentialAndBoundedResult() {
        MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness().reply(200,
                Map.of("event_id", "$sent:example.org"));
        NodeAction action = action(http);
        var result = action.handle(MatrixTestSupport.message(message("hello"))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status"));
        assertFalse(result.payload().toString().contains("hello"));
        var request = http.requests.getFirst();
        assertEquals("PUT", request.method());
        assertTrue(request.destination().toString().startsWith(
                "https://matrix.example.org/_matrix/client/v3/rooms/%21operations%3Aexample.org/send/m.room.message/rr_"));
        assertEquals("matrix-bearer", request.credential().orElseThrow().bindingId());
        assertFalse(request.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("authorization")));
        assertEquals(Map.of("msgtype", "m.text", "body", "hello"), MatrixValues.json(request.body()));
    }

    @Test void samePayloadProducesSameTransactionAndChangedBodyProducesAnother() {
        MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness()
                .reply(200, Map.of("event_id", "$one:example.org"))
                .reply(200, Map.of("event_id", "$two:example.org"))
                .reply(200, Map.of("event_id", "$three:example.org"));
        NodeAction action = action(http);
        action.handle(MatrixTestSupport.message(message("same"))).toCompletableFuture().join();
        action.handle(MatrixTestSupport.message(message("same"))).toCompletableFuture().join();
        action.handle(MatrixTestSupport.message(message("changed"))).toCompletableFuture().join();
        assertEquals(http.requests.get(0).destination(), http.requests.get(1).destination());
        assertNotEquals(http.requests.get(1).destination(), http.requests.get(2).destination());
    }

    @Test void actualManagedServiceInjectsOperatorBoundBearerForModuleRequest() throws Exception {
        MatrixTestSupport.HttpHarness capture = new MatrixTestSupport.HttpHarness().reply(200,
                Map.of("event_id", "$captured:example.org"));
        action(capture).handle(MatrixTestSupport.message(message("managed"))).toCompletableFuture().join();
        var moduleRequest = capture.requests.getFirst();
        assertFalse(moduleRequest.headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("authorization")));

        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/send", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = MatrixValues.jsonBytes(Map.of("event_id", "$managed:example.org"));
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start(); int port = server.getAddress().getPort();
        var origin = new NodePackageEgressPolicy.Origin("http", "localhost", port);
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("PUT").allowRequestHeader("accept").allowRequestHeader("content-type")
                .allowRequestHeader("user-agent").allowResponseHeader("content-type")
                .bindCredential("matrix-bearer", origin, "Authorization", "Bearer ")
                .byteLimits(1_048_576, 1_048_576, 1_048_576).build();
        var services = ManagedNodePackageServices.builder(MatrixConfiguration.PACKAGE_ID, policy,
                        (packageId, tenant, reference) -> Optional.of(new SecretValue(
                                (tenant + ":" + reference).toCharArray())))
                .grant(ai.ravenroot.api.node.service.NodePackageCapability.OUTBOUND_HTTP).build();
        var local = new ai.ravenroot.api.node.service.OutboundHttpRequest(
                URI.create("http://localhost:" + port + "/send"), moduleRequest.method(),
                moduleRequest.headers(), moduleRequest.body(), moduleRequest.deadline(),
                moduleRequest.credential().orElseThrow(), null, moduleRequest.limits(),
                moduleRequest.representationPolicy());
        var response = services.outboundHttp().execute(MatrixTestSupport.message(Map.of()), local)
                .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("Bearer " + MatrixTestSupport.TENANT + ":matrix-access-token", authorization.get());
    }

    @Test void authorityAndAmbiguousTransportFailClosedWithoutRetry() {
        MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness();
        Map<String, Object> forbidden = new java.util.LinkedHashMap<>(message("hello"));
        forbidden.put("roomId", "!other:example.org");
        Throwable denied = assertThrows(CompletionException.class,
                () -> action(http).handle(MatrixTestSupport.message(forbidden)).toCompletableFuture().join()).getCause();
        assertEquals(MatrixException.Code.FORBIDDEN, ((MatrixException) denied).code());
        http.pending = OutboundCall.failed(
                new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        Throwable failed = assertThrows(CompletionException.class,
                () -> action(http).handle(MatrixTestSupport.message(message("hello"))).toCompletableFuture().join()).getCause();
        assertEquals(MatrixException.Code.INDETERMINATE, ((MatrixException) failed).code());
        assertEquals(1, http.requests.size());
    }

    private NodeAction action(MatrixTestSupport.HttpHarness http) {
        MatrixNodePackage nodePackage = MatrixTestSupport.nodePackage(directory.resolve("sync.db"));
        return MatrixTestSupport.behavior(nodePackage, MatrixBehaviorDescriptors.SEND).create(
                new NodeConfiguration("matrix", MatrixBehaviorDescriptors.SEND,
                        Map.of("matrixProfile", MatrixTestSupport.PROFILE)), http);
    }
    private static Map<String, Object> message(String text) {
        return Map.of("version", "matrix.message.v1", "roomId", MatrixTestSupport.ROOM,
                "text", text, "correlationId", "correlation-1");
    }
}
