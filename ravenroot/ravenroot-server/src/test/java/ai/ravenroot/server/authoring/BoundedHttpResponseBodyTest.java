package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedHttpResponseBodyTest {
    @Test
    void requestDeadlineIncludesAResponseBodyThatStallsAfterHeaders() throws Exception {
        var releaseBody = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory()));
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 8);
                exchange.getResponseBody().flush();
                headersSent.countDown();
                if (releaseBody.await(5, TimeUnit.SECONDS)) exchange.getResponseBody().write(new byte[8]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"))
                    .timeout(Duration.ofMillis(200)).GET().build();
            long started = System.nanoTime();
            assertThrows(IOException.class,
                    () -> HttpClient.newHttpClient().send(request,
                            BoundedHttpResponseBody.upTo(64, Duration.ofMillis(200))));
            assertTrue(headersSent.await(1, TimeUnit.SECONDS), "fixture must prove the stall happened after headers");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0,
                    "the response body must not outlive the request deadline");
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }
}
