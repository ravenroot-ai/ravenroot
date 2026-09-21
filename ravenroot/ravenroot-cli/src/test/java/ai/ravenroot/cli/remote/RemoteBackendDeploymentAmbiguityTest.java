package ai.ravenroot.cli.remote;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteBackendDeploymentAmbiguityTest {
    private static final String BEFORE = """
            {"deploymentId":"orders","state":"READY","sourceCount":0,"scope":"LOCAL_PROCESS",
             "diagnostic":null,"deploymentGeneration":7}
            """;
    private static final String AFTER = """
            {"deploymentId":"orders","state":"STOPPED","sourceCount":0,"scope":"LOCAL_PROCESS",
             "diagnostic":null,"deploymentGeneration":8}
            """;

    @Test
    void twoLostCommandResponsesReconcileStateWithoutAThirdIntent() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var backend = new RemoteBackend(URI.create("http://runtime.example/"), "token",
                Duration.ofSeconds(1), request -> {
                    requests.add(request);
                    if ("GET".equals(request.method())) {
                        return response(200, requests.size() == 1 ? BEFORE : AFTER, request);
                    }
                    throw new IOException("response lost after apply");
                });

        var reconciled = backend.stopDeployment("orders", "maintenance");

        assertNull(reconciled.commandOutcome());
        assertEquals("STOPPED", reconciled.state());
        assertEquals(8L, reconciled.deploymentGeneration());
        assertEquals("delivery=AMBIGUOUS,reconciliation=AUTHORITATIVE_STATE",
                reconciled.commandDetail());
        assertEquals(List.of("GET", "POST", "POST", "GET"),
                requests.stream().map(HttpRequest::method).toList());
        assertEquals(requests.get(1).headers().firstValue("Idempotency-Key"),
                requests.get(2).headers().firstValue("Idempotency-Key"));
    }

    @Test
    void twoLostUndeployResponsesReconcileAuthoritativeNotFound() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var backend = new RemoteBackend(URI.create("http://runtime.example/"), "token",
                Duration.ofSeconds(1), request -> {
                    requests.add(request);
                    if ("GET".equals(request.method())) {
                        return requests.size() == 1 ? response(200, BEFORE, request)
                                : response(404, "{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}", request);
                    }
                    throw new IOException("response lost after apply");
                });

        var reconciled = backend.undeployDeployment("orders", "CANCEL_IN_FLIGHT", "retired");

        assertNull(reconciled.commandOutcome());
        assertEquals("REMOVED", reconciled.state());
        assertEquals("delivery=AMBIGUOUS,reconciliation=AUTHORITATIVE_NOT_FOUND",
                reconciled.commandDetail());
        assertEquals(List.of("GET", "DELETE", "DELETE", "GET"),
                requests.stream().map(HttpRequest::method).toList());
        assertEquals(requests.get(1).headers().firstValue("Idempotency-Key"),
                requests.get(2).headers().firstValue("Idempotency-Key"));
    }

    private static HttpResponse<String> response(int status, String body, HttpRequest request) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (left, right) -> true); }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return request.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
