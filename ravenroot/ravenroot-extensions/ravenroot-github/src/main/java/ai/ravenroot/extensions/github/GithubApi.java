package ai.ravenroot.extensions.github;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal GitHub wire adapter; every request still passes through Ravenroot's managed HTTP authority. */
final class GithubApi {
    static final String API_VERSION = "2022-11-28";
    private final NodePackageServices services;
    private final NodeMessage message;
    private final GithubProfile profile;
    private final CallControl control;
    private final Runnable fence;

    GithubApi(NodePackageServices services, NodeMessage message, GithubProfile profile, CallControl control,
              Runnable fence) {
        this.services = services; this.message = message; this.profile = profile; this.control = control;
        this.fence = fence;
    }

    Response get(String path) { return request("GET", path, new byte[0]); }
    Response delete(String path) { return request("DELETE", path, new byte[0]); }
    Response post(String path, Map<String, ?> body) { return request("POST", path, GithubValues.jsonBytes(body)); }
    Response graphql(String query, Map<String, ?> variables) {
        return post("/graphql", Map.of("query", query, "variables", variables));
    }

    private Response request(String method, String path, byte[] body) {
        control.check(); fence.run(); control.check();
        Map<String, List<String>> headers = body.length == 0
                ? Map.of("accept", List.of("application/vnd.github+json"),
                         "x-github-api-version", List.of(API_VERSION), "user-agent", List.of("ravenroot-github/1"))
                : Map.of("accept", List.of("application/vnd.github+json"),
                         "content-type", List.of("application/json"),
                         "x-github-api-version", List.of(API_VERSION), "user-agent", List.of("ravenroot-github/1"));
        OutboundCall<OutboundHttpResponse> call;
        try {
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(profile.rest(path), method,
                    headers, body, Duration.ofMillis(profile.timeoutMs()), profile.credential()));
            control.attach(call);
        } catch (RuntimeException failure) { throw sanitize(failure); }
        try {
            OutboundHttpResponse response = call.completion().toCompletableFuture()
                    .get(profile.timeoutMs(), TimeUnit.MILLISECONDS);
            control.detach(call); control.check(); fence.run(); control.check();
            if (response.body().length > profile.maxResponseBytes()) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); call.cancel(); throw new GithubException(GithubException.Code.CANCELLED);
        } catch (TimeoutException timeout) {
            call.cancel(); throw new GithubException(GithubException.Code.TRANSPORT);
        } catch (ExecutionException failure) { throw sanitize(failure.getCause()); }
        finally { control.detach(call); }
    }

    static GithubException sanitize(Throwable raw) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof GithubException safe) return safe;
        if (failure instanceof CancellationException) return new GithubException(GithubException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) {
            return new GithubException(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> GithubException.Code.AUTHENTICATION_FAILED;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED, TLS_REFUSED -> GithubException.Code.FORBIDDEN;
                case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> GithubException.Code.RESPONSE_INVALID;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> GithubException.Code.CAPACITY;
                case CANCELLED -> GithubException.Code.CANCELLED;
                case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> GithubException.Code.TRANSPORT;
            });
        }
        return new GithubException(GithubException.Code.TRANSPORT);
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        Object value() {
            try { return ai.ravenroot.api.payload.PayloadJson.read(body, GithubValues.LIMITS).toJava(); }
            catch (RuntimeException invalid) { throw new GithubException(GithubException.Code.RESPONSE_INVALID); }
        }
        Map<String, Object> object() {
            return GithubValues.object(value());
        }

        long retryAfterEpochMs() {
            long now = System.currentTimeMillis();
            long earliest = now + 250;
            long latest = now + 300_000;
            String seconds = header("retry-after");
            if (!seconds.isEmpty()) {
                try { return Math.max(earliest, Math.min(latest,
                        Math.addExact(now, Math.multiplyExact(Long.parseLong(seconds), 1_000)))); }
                catch (ArithmeticException | NumberFormatException ignored) { }
            }
            String reset = header("x-ratelimit-reset");
            if (!reset.isEmpty()) {
                try { return Math.max(earliest, Math.min(latest, Math.multiplyExact(Long.parseLong(reset), 1_000))); }
                catch (ArithmeticException | NumberFormatException ignored) { }
            }
            return earliest;
        }

        boolean rateLimited() {
            return status == 429 || status == 403
                    && ("0".equals(header("x-ratelimit-remaining")) || !header("retry-after").isEmpty());
        }

        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        }
    }

    static final class CallControl {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<OutboundCall<?>> active = new AtomicReference<>();
        private final AtomicReference<GithubException> failure = new AtomicReference<>();
        void attach(OutboundCall<?> call) { check(); active.set(call); if (cancelled.get()) call.cancel(); }
        void detach(OutboundCall<?> call) { active.compareAndSet(call, null); }
        void check() {
            GithubException failed = failure.get(); if (failed != null) throw failed;
            if (cancelled.get()) throw new GithubException(GithubException.Code.CANCELLED);
        }
        void fail(GithubException cause) {
            if (failure.compareAndSet(null, cause)) {
                OutboundCall<?> call = active.get(); if (call != null) try { call.cancel(); } catch (RuntimeException ignored) { }
            }
        }
        boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            OutboundCall<?> call = active.get(); if (call != null) try { call.cancel(); } catch (RuntimeException ignored) { }
            return true;
        }
    }
}
