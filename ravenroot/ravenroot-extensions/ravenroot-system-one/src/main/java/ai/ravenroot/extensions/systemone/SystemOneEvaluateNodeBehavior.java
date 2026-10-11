package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;
import ai.ravenroot.api.payload.PayloadLimits;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Provider-neutral typed evaluation over an operator-owned TypeSafe-compatible endpoint. */
public final class SystemOneEvaluateNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "system-one-evaluate";
    private static final int SPECIFICATION_BYTES = 1024 * 1024;
    private static final int DECOMPRESSION_RATIO = 20;
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    };

    private final SystemOneProfileResolver profiles;
    private final SystemOneAdmission admission = new SystemOneAdmission();

    public SystemOneEvaluateNodeBehavior() { this(new EnvironmentSystemOneProfileResolver()); }
    SystemOneEvaluateNodeBehavior(SystemOneProfileResolver profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "System One evaluate", "AI",
                "Evaluates typed Choice, Noul, and Score questions against the incoming payload "
                        + "through an operator-owned TypeSafe-compatible service.",
                "decision", false, List.of(
                NodePropertyDescriptor.adapterId("profile", "Profile", NodePropertyType.STRING,
                        "Name of an operator-owned System One profile "
                                + "(RAVENROOT_SYSTEM_ONE_PROFILE_<hex(name)>)."),
                NodePropertyDescriptor.boundedText("specification", "Evaluation specification",
                        NodePropertyType.TEXT, true,
                        "Strict ravenroot.system-one/specification/1 JSON containing an id and typed questions.",
                        "", SPECIFICATION_BYTES, 0, 0),
                NodePropertyDescriptor.optionalBounded("timeoutMs", "Deadline",
                        NodePropertyType.INTEGER, "May only tighten the profile deadline.", "", 1, 600_000)),
                // This is probabilistic evidence, not generated prose. Omitting ai/agentic prevents
                // SyntheticProvenance from minting an inaccurate generative-content marker.
                Set.of("probabilistic-evaluation", "external-provider", "network", "credential-reference"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The service returned a validated typed evidence envelope. Routing remains "
                                + "the responsibility of a deterministic downstream decision node."));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = NodePropertyDescriptor.adapterIdOf(configuration.properties().get("profile"));
        if (profileName.isEmpty()) return refuse(SystemOneException.Code.PROFILE_UNKNOWN, "");
        Optional<SystemOneProfile> resolved = profiles.resolve(profileName);
        if (resolved.isEmpty()) return refuse(SystemOneException.Code.PROFILE_UNKNOWN, profileName);
        try {
            SystemOneSpecification specification = SystemOneSpecification.parse(
                    configuration.requiredProperty("specification"), resolved.get());
            int requested = timeout(configuration.property("timeoutMs", ""), resolved.get().timeoutMs());
            Settings settings = new Settings(resolved.get(), specification,
                    Math.min(resolved.get().timeoutMs(), requested));
            return new Action(Objects.requireNonNull(services, "services"), settings);
        } catch (SystemOneException invalid) {
            return refuse(invalid.code(), invalid.hint());
        }
    }

    private static int timeout(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 1 || value > 600_000) {
                throw new SystemOneException(SystemOneException.Code.CONFIGURATION_INVALID, "timeoutMs");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new SystemOneException(SystemOneException.Code.CONFIGURATION_INVALID, "timeoutMs");
        }
    }

    private static NodeAction refuse(SystemOneException.Code code, String hint) {
        return message -> CompletableFuture.failedFuture(new SystemOneException(code, hint));
    }

    private record Settings(SystemOneProfile profile, SystemOneSpecification specification,
                            int timeoutMs) { }

    private final class Action implements NodeAction {
        private final NodePackageServices services;
        private final Settings settings;
        Action(NodePackageServices services, Settings settings) {
            this.services = services;
            this.settings = settings;
        }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message) {
            return invoke(message, services, settings, NEVER_CANCELLED);
        }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message,
                                                             CancellationSignal cancellation) {
            return invoke(message, services, settings, Objects.requireNonNull(cancellation, "cancellation"));
        }
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services,
                                               Settings settings, CancellationSignal cancellation) {
        if (cancellation.cancelled()) {
            return CompletableFuture.failedFuture(
                    new SystemOneException(SystemOneException.Code.DEADLINE_EXCEEDED));
        }
        SystemOneAdmission.Lease lease = admission.tryAcquire(message.tenantId() + " "
                + settings.profile().name(), settings.profile().maxConcurrency());
        if (lease == null) {
            return CompletableFuture.failedFuture(
                    new SystemOneException(SystemOneException.Code.CAPACITY_UNAVAILABLE));
        }

        OutboundCall<OutboundHttpResponse> call;
        String stateDigest;
        try {
            byte[] body = settings.specification().request(message.payload(), settings.profile());
            stateDigest = SystemOneSpecification.stateDigest(message.payload(),
                    settings.profile().maxStateBytes());
            Duration deadline = Duration.ofMillis(settings.timeoutMs());
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(
                    settings.profile().endpoint(), "POST",
                    Map.of("content-type", List.of("application/json")), body, deadline,
                    settings.profile().credentialBinding().orElse(null), null,
                    ExternalIoLimits.compressedHttp(settings.profile().maxRequestBytes(),
                            settings.profile().maxResponseBytes(), settings.profile().maxResponseBytes(),
                            settings.profile().maxResponseBytes(), DECOMPRESSION_RATIO, deadline,
                            Set.of("application/json")),
                    OutboundHttpRepresentationPolicy.SUCCESS_ONLY));
        } catch (RuntimeException failure) {
            lease.close();
            return CompletableFuture.failedFuture(sanitize(failure));
        }

        var result = new CompletableFuture<NodeResult>();
        cancellation.onCancel(call::cancel);
        final String digest = stateDigest;
        call.completion().whenComplete((response, failure) -> {
            try {
                if (failure != null) result.completeExceptionally(sanitize(failure));
                else result.complete(answer(settings, response, message, digest));
            } catch (RuntimeException invalid) {
                result.completeExceptionally(sanitize(invalid));
            } finally {
                lease.close();
            }
        });
        return result;
    }

    private static NodeResult answer(Settings settings, OutboundHttpResponse response,
                                     NodeMessage message, String stateDigest) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SystemOneException(SystemOneException.Code.ENDPOINT_REJECTED);
        }
        SystemOneSpecification.ValidatedResponse validated = settings.specification()
                .validateResponse(response.body(), settings.profile());
        Map<String, Object> payload = settings.specification().result(
                message.payload(), settings.profile(), validated);
        var attributes = new LinkedHashMap<String, Object>(message.attributes());
        attributes.put("systemOne.profile", settings.profile().name());
        attributes.put("systemOne.requestedModel", settings.profile().model());
        attributes.put("systemOne.reportedModel", validated.reportedModel());
        attributes.put("systemOne.specificationId", settings.specification().id());
        attributes.put("systemOne.specificationDigest", settings.specification().digest());
        attributes.put("systemOne.stateDigest", stateDigest);
        try {
            int outputLimit = Math.toIntExact(Math.min(settings.profile().maxResponseBytes(),
                    response.effectiveMaximumOutputBytes()));
            new PayloadLimits(outputLimit, 32, 100_000, 500_000, outputLimit, 4_096)
                    .enforceAndMeasure(Map.of("payload", payload, "attributes", Map.copyOf(attributes)));
        } catch (RuntimeException oversized) {
            throw new SystemOneException(SystemOneException.Code.RESPONSE_TOO_LARGE);
        }
        return new NodeResult("continue", payload, Map.copyOf(attributes));
    }

    private static RuntimeException sanitize(Throwable raw) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof SystemOneException known) return known;
        if (failure instanceof CancellationException) {
            return new SystemOneException(SystemOneException.Code.DEADLINE_EXCEEDED);
        }
        if (failure instanceof NodePackageServiceException service) {
            return new SystemOneException(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> SystemOneException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED, TLS_REFUSED ->
                        SystemOneException.Code.DESTINATION_REFUSED;
                case REQUEST_TOO_LARGE -> SystemOneException.Code.REQUEST_TOO_LARGE;
                case RESPONSE_TOO_LARGE -> SystemOneException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> SystemOneException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> SystemOneException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED, EFFECT_OUTCOME_INDETERMINATE, BUDGET_EXHAUSTED ->
                        SystemOneException.Code.TRANSPORT_UNAVAILABLE;
            });
        }
        return new SystemOneException(SystemOneException.Code.TRANSPORT_UNAVAILABLE);
    }

    int admissionEntries() { return admission.size(); }
}
