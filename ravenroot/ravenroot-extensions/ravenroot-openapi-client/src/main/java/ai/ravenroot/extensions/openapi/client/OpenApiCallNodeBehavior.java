package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Profiled OpenAPI 3.0.3 operation invocation through the managed HTTP capability. */
public final class OpenApiCallNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "openapi.call";
    private static final Set<String> INPUT_FIELDS = Set.of("version", "path", "query", "headers", "body");
    private final OpenApiClientProfileResolver profiles;
    private final AdmissionRegistry<String> profileAdmission = new AdmissionRegistry<>();

    public OpenApiCallNodeBehavior() { this(new EnvironmentOpenApiClientProfileResolver()); }
    OpenApiCallNodeBehavior(OpenApiClientProfileResolver profiles) { this.profiles = java.util.Objects.requireNonNull(profiles); }

    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Call OpenAPI operation", "OpenAPI",
                "Calls one operation from an immutable operator-owned OpenAPI 3.0.3 profile.", "actor", false,
                List.of(NodePropertyDescriptor.required("apiProfile", "API profile", NodePropertyType.STRING,
                                "Opaque operator profile; it fixes the specification and HTTPS origin."),
                        NodePropertyDescriptor.required("operationId", "Operation", NodePropertyType.STRING,
                                "One operation id allowed by the operator profile."),
                        optionalInt("timeoutMs", "Deadline", "May only tighten the profile deadline."),
                        optionalInt("maxRequestBytes", "Request bytes", "May only tighten the request ceiling."),
                        optionalInt("maxResponseBytes", "Response bytes", "May only tighten the response ceiling."),
                        optionalInt("maxConcurrency", "Concurrency", "May only tighten the profile concurrency.")),
                Set.of("network", "credential-reference", "side-effect"));
    }

    private static NodePropertyDescriptor optionalInt(String name, String label, String description) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.INTEGER, description, "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        final Settings settings = Settings.compile(configuration, profiles);
        return new OpenApiAction(services, settings);
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services, Settings settings,
                                                AdmissionRegistry<String> actionAdmission) {
        AdmissionLease lease;
        try {
            String profileKey = message.tenantId() + "\u0000" + settings.profile.name();
            GateLease profileLease = profileAdmission.tryAcquire(profileKey, settings.profile.maxConcurrency());
            if (profileLease == null) return capacity();
            GateLease actionLease = actionAdmission.tryAcquire(message.tenantId(), settings.maxConcurrency);
            if (actionLease == null) {
                profileLease.close();
                return capacity();
            }
            lease = new AdmissionLease(profileLease, actionLease);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(sanitize(failure, false));
        }
        final Prepared prepared;
        try { prepared = Prepared.from(message.payload(), settings); }
        catch (RuntimeException failure) { lease.close(); return CompletableFuture.failedFuture(sanitize(failure, false)); }
        long deadline = System.nanoTime() + Duration.ofMillis(settings.timeoutMs).toNanos();
        OutboundCall<OutboundHttpResponse> call;
        try {
            Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(prepared.destination,
                    settings.operation.method(), prepared.headers, prepared.body, remaining,
                    settings.operation.authenticated() ? settings.profile.credential().orElse(null) : null));
        } catch (RuntimeException failure) {
            lease.close();
            return CompletableFuture.failedFuture(sanitize(failure, false));
        }
        ContractFuture result = new ContractFuture(call, !settings.operation.idempotent());
        call.completion().whenComplete((response, failure) -> {
            try {
                if (failure != null) result.completeExceptionally(sanitize(failure, !settings.operation.idempotent()));
                else result.complete(response(settings, response));
            } catch (RuntimeException invalid) {
                result.completeExceptionally(invalid);
            } finally {
                lease.close();
            }
        });
        return result;
    }

    int profileAdmissionEntries() { return profileAdmission.size(); }

    final class OpenApiAction implements NodeAction {
        private final NodePackageServices services;
        private final Settings settings;
        private final AdmissionRegistry<String> tenantAdmission = new AdmissionRegistry<>();

        OpenApiAction(NodePackageServices services, Settings settings) {
            this.services = services;
            this.settings = settings;
        }

        @Override public CompletionStage<NodeResult> handle(NodeMessage message) {
            return invoke(message, services, settings, tenantAdmission);
        }

        int tenantAdmissionEntries() { return tenantAdmission.size(); }
    }

    private static CompletionStage<NodeResult> capacity() {
        return CompletableFuture.failedFuture(
                new OpenApiClientException(OpenApiClientException.Code.CAPACITY_UNAVAILABLE));
    }

    private static NodeResult response(Settings settings, OutboundHttpResponse response) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) throw new OpenApiClientException(OpenApiClientException.Code.REDIRECT_REFUSED);
        if (!settings.operation.accepts(status)) throw new OpenApiClientException(OpenApiClientException.Code.RESPONSE_INVALID);
        byte[] bytes = response.body();
        if (bytes.length > settings.maxResponseBytes) throw new OpenApiClientException(OpenApiClientException.Code.RESPONSE_TOO_LARGE);
        OpenApiCallPlan.Schema schema = settings.operation.response(status);
        Object body = null;
        if (schema == null) {
            if (bytes.length != 0) throw new OpenApiClientException(OpenApiClientException.Code.RESPONSE_INVALID);
        } else {
            try {
                PayloadLimits limits = payloadLimits(settings.maxResponseBytes);
                body = PayloadJson.read(bytes, limits).toJava();
                schema.validate(body);
            } catch (RuntimeException invalid) {
                throw new OpenApiClientException(OpenApiClientException.Code.RESPONSE_INVALID);
            }
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        response.headers().entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .filter(entry -> settings.profile.projectedResponseHeaders().contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .forEach(entry -> projected.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(entry.getValue())));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", "openapi.call.result.v1"); output.put("operationId", settings.operation.id());
        output.put("status", (long) status); output.put("headers", Map.copyOf(projected)); output.put("body", body);
        return NodeResult.continueWith(java.util.Collections.unmodifiableMap(output));
    }

    private static RuntimeException sanitize(Throwable raw, boolean ambiguous) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof OpenApiClientException safe) return safe;
        if (failure instanceof java.util.concurrent.CancellationException) {
            return new OpenApiClientException(ambiguous ? OpenApiClientException.Code.AMBIGUOUS
                    : OpenApiClientException.Code.DEADLINE_EXCEEDED);
        }
        if (failure instanceof NodePackageServiceException service) {
            if (ambiguous && Set.of(NodePackageServiceException.Reason.DEADLINE_EXCEEDED,
                    NodePackageServiceException.Reason.CANCELLED, NodePackageServiceException.Reason.TRANSPORT_FAILED)
                    .contains(service.reason())) return new OpenApiClientException(OpenApiClientException.Code.AMBIGUOUS);
            return new OpenApiClientException(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> OpenApiClientException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED -> OpenApiClientException.Code.DESTINATION_REFUSED;
                case TLS_REFUSED -> OpenApiClientException.Code.TLS_REFUSED;
                case REQUEST_TOO_LARGE -> OpenApiClientException.Code.REQUEST_TOO_LARGE;
                case RESPONSE_TOO_LARGE -> OpenApiClientException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> OpenApiClientException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> OpenApiClientException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED -> OpenApiClientException.Code.TRANSPORT_UNAVAILABLE;
            });
        }
        return new OpenApiClientException(ambiguous ? OpenApiClientException.Code.AMBIGUOUS
                : OpenApiClientException.Code.TRANSPORT_UNAVAILABLE);
    }

    /** One concurrency gate. Reference count changes only while its registry key is linearized. */
    private static final class AdmissionGate {
        private final int maximum;
        private final Semaphore permits;
        private int references;

        AdmissionGate(int maximum) {
            this.maximum = maximum;
            this.permits = new Semaphore(maximum, true);
            this.references = 1;
        }

        AdmissionGate retain(int expectedMaximum) {
            if (maximum != expectedMaximum) {
                throw new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION);
            }
            references++;
            return this;
        }

        boolean releaseReference() { return --references == 0; }
        boolean tryAcquire() { return permits.tryAcquire(); }
        void release() { permits.release(); }
    }

    /**
     * Active-only gate registry. Both acquisition and last-reference removal use the map's per-key
     * compute linearization, so a same-key reacquisition either retains the old gate or starts after
     * its last user has completely detached; it can never overlap a replacement gate.
     */
    static final class AdmissionRegistry<K> {
        private final ConcurrentHashMap<K, AdmissionGate> gates = new ConcurrentHashMap<>();
        private final Runnable beforeReferenceRelease;

        AdmissionRegistry() { this(() -> { }); }
        AdmissionRegistry(Runnable beforeReferenceRelease) {
            this.beforeReferenceRelease = java.util.Objects.requireNonNull(beforeReferenceRelease);
        }

        GateLease tryAcquire(K key, int maximum) {
            AdmissionGate gate = gates.compute(key, (ignored, current) -> current == null
                    ? new AdmissionGate(maximum) : current.retain(maximum));
            if (!gate.tryAcquire()) {
                releaseReference(key, gate);
                return null;
            }
            return new GateLease(() -> {
                gate.release();
                try { beforeReferenceRelease.run(); }
                finally { releaseReference(key, gate); }
            });
        }

        int size() { return gates.size(); }

        private void releaseReference(K key, AdmissionGate gate) {
            gates.compute(key, (ignored, current) -> {
                if (current != gate) throw new IllegalStateException("admission gate identity lost");
                return gate.releaseReference() ? null : gate;
            });
        }
    }

    static final class GateLease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        GateLease(Runnable release) { this.release = release; }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }

    private static final class AdmissionLease implements AutoCloseable {
        private final GateLease profile;
        private final GateLease action;
        private final AtomicBoolean closed = new AtomicBoolean();

        AdmissionLease(GateLease profile, GateLease action) {
            this.profile = profile;
            this.action = action;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                try { action.close(); }
                finally { profile.close(); }
            }
        }
    }

    /** Propagates cancellation without exposing CompletableFuture's raw CancellationException contract. */
    private static final class ContractFuture extends CompletableFuture<NodeResult> {
        private final OutboundCall<?> call;
        private final boolean ambiguous;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        ContractFuture(OutboundCall<?> call, boolean ambiguous) {
            this.call = call;
            this.ambiguous = ambiguous;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || !cancellationRequested.compareAndSet(false, true)) return false;
            boolean accepted = completeExceptionally(new OpenApiClientException(
                    ambiguous ? OpenApiClientException.Code.AMBIGUOUS
                            : OpenApiClientException.Code.DEADLINE_EXCEEDED));
            if (!accepted) return false;
            try { call.cancel(); } catch (RuntimeException ignored) { }
            return true;
        }
    }

    private record Settings(OpenApiClientProfile profile, OpenApiCallPlan.Operation operation,
                            int timeoutMs, int maxRequestBytes, int maxResponseBytes, int maxConcurrency) {
        static Settings compile(NodeConfiguration configuration, OpenApiClientProfileResolver resolver) {
            try {
                String name = configuration.requiredProperty("apiProfile");
                OpenApiClientProfile profile = resolver.resolve(name).orElseThrow();
                if (!profile.name().equals(name)) throw new IllegalArgumentException();
                OpenApiCallPlan plan = OpenApiCallPlan.compile(profile);
                OpenApiCallPlan.Operation operation = plan.operation(configuration.requiredProperty("operationId"));
                return new Settings(profile, operation,
                        tighten(configuration, "timeoutMs", profile.timeoutMs()),
                        tighten(configuration, "maxRequestBytes", profile.maxRequestBytes()),
                        tighten(configuration, "maxResponseBytes", profile.maxResponseBytes()),
                        tighten(configuration, "maxConcurrency", profile.maxConcurrency()));
            } catch (OpenApiClientException failure) { throw failure; }
            catch (RuntimeException invalid) { throw new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION); }
        }

        private static int tighten(NodeConfiguration configuration, String name, int ceiling) {
            String raw = configuration.property(name, ""); if (raw.isEmpty()) return ceiling;
            try { int value = Integer.parseInt(raw); if (value < 1 || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException invalid) { throw new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION); }
        }
    }

    private record Prepared(URI destination, Map<String, List<String>> headers, byte[] body) {
        static Prepared from(Object raw, Settings settings) {
            Map<String, Object> input;
            try { input = OpenApiValues.object(raw, "input"); }
            catch (RuntimeException invalid) { throw new OpenApiClientException(OpenApiClientException.Code.INVALID_INPUT); }
            if (!INPUT_FIELDS.containsAll(input.keySet()) || !"openapi.call.v1".equals(input.get("version"))) invalid();
            Map<String, Object> path = inputMap(input.get("path"));
            Map<String, Object> query = inputMap(input.get("query"));
            Map<String, Object> submittedHeaders = inputMap(input.get("headers"));
            Map<String, Object> normalizedHeaders = new LinkedHashMap<>();
            submittedHeaders.forEach((name, value) -> {
                String normalized = OpenApiClientProfile.header(name);
                if (normalizedHeaders.putIfAbsent(normalized, value) != null) invalid();
            });
            Map<OpenApiCallPlan.Location, Map<String, Object>> supplied = Map.of(
                    OpenApiCallPlan.Location.PATH, path, OpenApiCallPlan.Location.QUERY, query,
                    OpenApiCallPlan.Location.HEADER, normalizedHeaders);
            Map<OpenApiCallPlan.Location, Set<String>> expected = new java.util.EnumMap<>(OpenApiCallPlan.Location.class);
            for (OpenApiCallPlan.Location location : List.of(OpenApiCallPlan.Location.PATH,
                    OpenApiCallPlan.Location.QUERY, OpenApiCallPlan.Location.HEADER)) expected.put(location, new java.util.HashSet<>());
            for (OpenApiCallPlan.Parameter parameter : settings.operation.parameters()) {
                String key = parameter.location() == OpenApiCallPlan.Location.HEADER
                        ? parameter.name().toLowerCase(Locale.ROOT) : parameter.name();
                expected.get(parameter.location()).add(key);
                Object value = supplied.get(parameter.location()).get(key);
                if (value == null && parameter.required()) invalid();
                if (value != null) parameter.schema().validate(value);
                if (parameter.location() == OpenApiCallPlan.Location.HEADER
                        && !settings.profile.allowedInputHeaders().contains(key)) invalid();
            }
            expected.forEach((location, names) -> { if (!names.containsAll(supplied.get(location).keySet())) invalid(); });
            String pathText = expand(settings.operation.pathTemplate(), path);
            String queryText = query(settings.operation.parameters(), query);
            URI destination;
            try { destination = settings.profile.origin().resolve(pathText + queryText); }
            catch (RuntimeException bad) { throw new OpenApiClientException(OpenApiClientException.Code.INVALID_INPUT); }
            if (!sameAuthority(settings.profile.origin(), destination)) invalid();
            Map<String, List<String>> headers = new LinkedHashMap<>(settings.profile.fixedHeaders());
            normalizedHeaders.forEach((name, value) -> {
                if (headers.containsKey(name)) invalid();
                headers.put(name, List.of(headerValue(value)));
            });
            byte[] body = new byte[0];
            if (settings.operation.requestBody() != null) {
                if (!input.containsKey("body")) invalid();
                settings.operation.requestBody().validate(input.get("body"));
                body = PayloadJson.write(PayloadValue.fromJava(input.get("body"), payloadLimits(settings.maxRequestBytes)))
                        .getBytes(StandardCharsets.UTF_8);
                headers.put("content-type", List.of("application/json"));
            } else if (input.containsKey("body") && input.get("body") != null) invalid();
            if (body.length > settings.maxRequestBytes) throw new OpenApiClientException(OpenApiClientException.Code.REQUEST_TOO_LARGE);
            return new Prepared(destination, Map.copyOf(headers), body);
        }

        private static Map<String, Object> inputMap(Object value) {
            if (value == null) return Map.of();
            try { return OpenApiValues.object(value, "input"); }
            catch (RuntimeException invalid) { throw new OpenApiClientException(OpenApiClientException.Code.INVALID_INPUT); }
        }

        private static String expand(String template, Map<String, Object> path) {
            String result = template;
            for (Map.Entry<String, Object> entry : path.entrySet()) {
                String raw = scalar(entry.getValue());
                if (raw.isEmpty() || raw.equals(".") || raw.equals("..") || raw.contains("/") || raw.contains("%")) invalid();
                result = result.replace("{" + entry.getKey() + "}", percent(raw));
            }
            if (result.contains("{") || result.contains("}")) invalid();
            return result;
        }

        private static String query(List<OpenApiCallPlan.Parameter> parameters, Map<String, Object> values) {
            List<String> parts = new ArrayList<>();
            parameters.stream().filter(value -> value.location() == OpenApiCallPlan.Location.QUERY)
                    .sorted(Comparator.comparing(OpenApiCallPlan.Parameter::name)).forEach(parameter -> {
                        Object value = values.get(parameter.name());
                        if (value != null) parts.add(percent(parameter.name()) + "=" + percent(scalar(value)));
                    });
            return parts.isEmpty() ? "" : "?" + String.join("&", parts);
        }

        private static String headerValue(Object value) {
            String text = scalar(value);
            if (text.length() > 512 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) invalid();
            return text;
        }

        private static String scalar(Object value) {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString();
            invalid(); return "";
        }

        private static String percent(String value) {
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
            return encoded.replace("%7E", "~");
        }

        private static boolean sameAuthority(URI expected, URI actual) {
            return "https".equals(actual.getScheme()) && expected.getHost().equalsIgnoreCase(actual.getHost())
                    && effectivePort(expected) == effectivePort(actual) && actual.getUserInfo() == null;
        }
        private static int effectivePort(URI value) { return value.getPort() == -1 ? 443 : value.getPort(); }
        private static void invalid() { throw new OpenApiClientException(OpenApiClientException.Code.INVALID_INPUT); }
    }

    private static PayloadLimits payloadLimits(int bytes) {
        return new PayloadLimits(bytes, 32, Math.min(10_000, Math.max(1, bytes)),
                Math.min(50_000, Math.max(1, bytes)), Math.max(1, bytes), 256);
    }
}
