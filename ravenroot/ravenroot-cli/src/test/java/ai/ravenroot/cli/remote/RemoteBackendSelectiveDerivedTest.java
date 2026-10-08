package ai.ravenroot.cli.remote;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
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
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteBackendSelectiveDerivedTest {
    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
    private static final String PREDECESSOR = "00000000-0000-0000-0000-000000000002";

    @Test
    void usesThePublishedDiscoveryPreviewAndStartRoutesWithTheSameBoundedBody() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var backend = new RemoteBackend(URI.create("http://runtime.example/"), "token", Duration.ofSeconds(1),
                request -> {
                    requests.add(request);
                    String path = request.uri().getPath();
                    if (path.endsWith("/boundaries")) return response(200, "{\"boundaries\":[]}", request);
                    if (path.endsWith("/preview")) return response(200, "{\"admissible\":true,\"refusalCodes\":[],\"inheritedInvocationIds\":[],\"possibleScopeNodeIds\":[\"B\"],\"missingInputs\":[],\"externalEffectNodes\":[],\"sourceOutcomeAmbiguous\":false,\"graphContentId\":\"graph\",\"manifestDigest\":\"manifest\"}", request);
                    return response(202, "{\"processInstanceId\":\"p\",\"traversalId\":\"t\",\"graphVersion\":\"graph\"}", request);
                });

        backend.derivedBoundaries(SOURCE);
        var preview = backend.previewDerived(SOURCE, "B", PREDECESSOR, "key", "reason", "reviewed", true);
        assertFalse(preview.sourceOutcomeAmbiguous());
        backend.startDerived(SOURCE, "B", PREDECESSOR, "key", "reason", "reviewed", true);

        assertEquals(List.of("GET", "POST", "POST"), requests.stream().map(HttpRequest::method).toList());
        assertEquals(List.of("/v1/executions/" + SOURCE + "/derived/boundaries",
                        "/v1/executions/" + SOURCE + "/derived/preview",
                        "/v1/executions/" + SOURCE + "/derived"),
                requests.stream().map(request -> request.uri().getPath()).toList());
        String previewBody = body(requests.get(1));
        assertEquals(previewBody, body(requests.get(2)));
        assertTrue(previewBody.contains("\"predecessorInvocationIds\":[\"" + PREDECESSOR + "\"]"));
        assertTrue(previewBody.contains("\"authorizeExternalEffects\":true"));
    }

    private static String body(HttpRequest request) {
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer item) {
                var chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() { }
        });
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
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
