package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

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

/**
 * {@code llm-prompt}: transforms the incoming payload with a prompt, through an operator-owned
 * OpenAI-compatible endpoint reached over the managed HTTP channel.
 *
 * <h2>What makes the output marked, and why nothing here does the marking</h2>
 * <p>{@link #descriptor()} declares the capability {@code "ai"}. That capability is a member of
 * {@code SyntheticProvenance.GENERATIVE_CAPABILITIES}, which the runtime consults on every successful
 * node completion, reading the <em>registered catalog descriptor</em> rather than anything the node
 * did. So this bundle's output carries {@code ravenroot.provenance.synthetic} without the runtime
 * knowing this class exists, and without this class writing a marker. That mechanism stayed in the
 * core when the AI nodes left it, as part of the generative-provenance contract, precisely so that a node
 * supplied from outside is marked identically to one that used to be inside.</p>
 *
 * <p>The direct consequence, and the discipline it imposes: <b>a refusal must never be a
 * {@link NodeResult}, for any outcome.</b> A successful result returned from a refusal would stamp a
 * machine-readable assertion that a model generated content no model produced -- a wrong marker,
 * which {@code SyntheticProvenance} states is worse than a missing one. Every refusal below is a
 * failed future. <b>And never by throwing synchronously:</b> the runner invokes the action inside
 * {@code RavenNode#onMessage} while the traversal's terminal bookkeeping lives inside the returned
 * stage, so a synchronous throw can leave a traversal non-terminal with no failure event recorded --
 * and whether it does depends on which engine adapter is installed. Both rules are carried over from
 * the core's departed {@code UnconfiguredAdapterRefusal} and restated here rather than summarised
 * away, because they are properties of the refusal and not of that class.</p>
 *
 * <h2>The credential is never in this process's reach</h2>
 * <p>This behavior requires {@link NodePackageCapability#OUTBOUND_HTTP} and deliberately <b>not</b>
 * {@code CREDENTIAL_RESOLUTION}. A profile names an {@code OutboundCredentialBinding}; the runtime
 * resolves it and places it on the request. This bundle never holds a secret, never logs one, and has
 * no code path that could return one -- a stronger statement than "it is careful with it".</p>
 */
public final class LlmPromptNodeBehavior implements NodeBehavior {
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    };

    /** The catalog name, unchanged from the one the core used to publish. */
    public static final String BEHAVIOR = "llm-prompt";

    private final LlmProfileResolver profiles;
    /** Per (tenant, profile) admission, shared by every node of this type. */
    private final Admission profileAdmission = new Admission();

    public LlmPromptNodeBehavior() {
        this(new EnvironmentLlmProfileResolver());
    }

    LlmPromptNodeBehavior(LlmProfileResolver profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "LLM prompt", "AI",
                "Sends the incoming payload to an operator-configured OpenAI-compatible model, "
                        + "following a prompt, and continues with the model's answer.",
                "agent", false, List.of(
                // An adapter binding, not a plain required property. Leaving it blank makes this an
                // unconfigured node rather than a defective graph (CORE-07), and the emptiness test
                // must stay adapterIdOf(...) -- the same call BehaviorPropertySchema uses to grant
                // the exemption. Respelling it as isBlank(), trim() or strip() reopens the
                // differential: a value the two sides judge differently is admitted by the schema
                // and not refused here, and the node then neither validates nor refuses.
                NodePropertyDescriptor.adapterId("provider", "Provider", NodePropertyType.STRING,
                        "Name of a model profile this deployment declared in its environment "
                                + "(RAVENROOT_LLM_PROFILE_<hex(name)>)."),
                NodePropertyDescriptor.required("prompt", "Prompt", NodePropertyType.TEXT,
                        "Instruction template. Supports {{payload}}, {{payload.a.b}} and "
                                + "{{attributes.x}}."),
                NodePropertyDescriptor.optional("model", "Model", NodePropertyType.STRING,
                        "Overrides the profile's model. The endpoint is not overridable.", ""),
                NodePropertyDescriptor.optional("timeoutMs", "Deadline", NodePropertyType.INTEGER,
                        "May only tighten the profile deadline.", ""),
                NodePropertyDescriptor.optional("maxTokens", "Max tokens", NodePropertyType.INTEGER,
                        "Upper bound on the generated answer, when the endpoint honours it.", ""),
                NodePropertyDescriptor.optional("temperature", "Temperature", NodePropertyType.STRING,
                        "Sampling temperature forwarded verbatim.", ""),
                NodePropertyDescriptor.optional("topP", "Top-p", NodePropertyType.STRING,
                        "Nucleus sampling parameter forwarded verbatim.", ""),
                NodePropertyDescriptor.optional("seed", "Seed", NodePropertyType.INTEGER,
                        "Sampling seed, when the endpoint honours it.", "")),
                // "ai" is not decoration: it is the whole reason this node's output is marked. The
                // other three describe what the node does; removing "ai" would silently unmark it.
                Set.of("ai", "external-provider", "network", "credential-reference"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The endpoint returned a completion, which becomes the outgoing payload. The "
                                + "only outcome this node produces: anything else fails the node."));
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        // SDK /1 path, and the deny-only view is the correct authority for it: a package activated
        // without a grant has no egress, and the node says so when reached rather than at build time.
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override
    public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        // The adapter id is read FIRST, before any other property. Otherwise a node with neither a
        // provider nor a prompt would fail on the prompt, and the CORE-07 reversal would only appear
        // to work.
        String profileName = NodePropertyDescriptor.adapterIdOf(configuration.properties().get("provider"));
        if (profileName.isEmpty()) {
            return refuse(LlmPromptException.Code.PROFILE_UNKNOWN, "");
        }
        // Resolved exactly once, here, and captured: an execution's capability set is fixed at
        // admission, so a profile written into the environment while a graph runs cannot arm a node
        // that already refused.
        Optional<LlmProfile> resolved = profiles.resolve(profileName);
        if (resolved.isEmpty()) {
            return refuse(LlmPromptException.Code.PROFILE_UNKNOWN, profileName);
        }
        Settings settings = Settings.compile(configuration, resolved.get());
        return new LlmPromptAction(services, settings);
    }

    private static NodeAction refuse(LlmPromptException.Code code, String hint) {
        return message -> CompletableFuture.failedFuture(new LlmPromptException(code, hint));
    }

    /** Everything derived once per node. Immutable, because one action serves concurrent traversals. */
    record Settings(LlmProfile profile, String prompt, String model, int timeoutMs,
                    OpenAiCompatibleChat.Tuning tuning) {

        static Settings compile(NodeConfiguration configuration, LlmProfile profile) {
            String prompt = configuration.requiredProperty("prompt");
            String model = configuration.property("model", "").strip();
            int requested = positiveInt(configuration, "timeoutMs").orElse(profile.timeoutMs());
            var tuning = new OpenAiCompatibleChat.Tuning(
                    positiveLong(configuration, "maxTokens"),
                    finiteDouble(configuration, "temperature"),
                    finiteDouble(configuration, "topP"),
                    longValue(configuration, "seed"));
            return new Settings(profile, prompt, model.isEmpty() ? profile.model() : model,
                    Math.max(1, Math.min(profile.timeoutMs(), requested)), tuning);
        }

        // GraphML properties arrive as text, so a declared value may be a Number or a String. A
        // malformed one is OMITTED rather than thrown on: the value is graph content, and a
        // credential-adjacent execution path is not where attacker-influenced text should be able to
        // raise a new exception type. Carried over from the adapter's own parameter reader.
        private static Optional<Long> longValue(NodeConfiguration configuration, String name) {
            String raw = configuration.property(name, "").strip();
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(raw));
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }

        private static Optional<Long> positiveLong(NodeConfiguration configuration, String name) {
            return longValue(configuration, name).filter(value -> value > 0);
        }

        private static Optional<Integer> positiveInt(NodeConfiguration configuration, String name) {
            return positiveLong(configuration, name)
                    .filter(value -> value <= Integer.MAX_VALUE)
                    .map(Long::intValue);
        }

        private static Optional<Double> finiteDouble(NodeConfiguration configuration, String name) {
            String raw = configuration.property(name, "").strip();
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            try {
                double value = Double.parseDouble(raw);
                // PayloadValue.DecimalValue refuses NaN and the infinities at construction, so
                // letting one through would turn a graph property into a rejection thrown from
                // inside the writer.
                return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }
    }

    private final class LlmPromptAction implements NodeAction {
        private final NodePackageServices services;
        private final Settings settings;

        LlmPromptAction(NodePackageServices services, Settings settings) {
            this.services = Objects.requireNonNull(services, "services");
            this.settings = settings;
        }

        @Override
        public CompletionStage<NodeResult> handle(NodeMessage message) {
            return invoke(message, services, settings, NEVER_CANCELLED);
        }

        @Override
        public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
            return invoke(message, services, settings, Objects.requireNonNull(cancellation, "cancellation"));
        }
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services,
                                               Settings settings, CancellationSignal cancellation) {
        if (cancellation.cancelled()) {
            return CompletableFuture.failedFuture(
                    new LlmPromptException(LlmPromptException.Code.DEADLINE_EXCEEDED));
        }
        // The key pairs the tenant with the profile, and the separator is a character neither can
        // contain: a profile name is masked to [A-Za-z0-9._-] before it is ever resolved.
        Admission.Lease lease = profileAdmission.tryAcquire(
                message.tenantId() + " " + settings.profile().name(), settings.profile().maxConcurrency());
        if (lease == null) {
            return CompletableFuture.failedFuture(
                    new LlmPromptException(LlmPromptException.Code.CAPACITY_UNAVAILABLE));
        }
        OutboundCall<OutboundHttpResponse> call;
        ModelInputProvenance provenance = new ModelInputProvenance();
        try {
            String prompt = PromptTemplate.render(settings.prompt(), message.payload(),
                    message.attributes(), Map.of());
            provenance.add(ModelInputProvenance.Kind.RENDERED_PROMPT,
                    "node:" + message.nodeId(), prompt);
            provenance.add(ModelInputProvenance.Kind.INBOUND_PAYLOAD,
                    "invocation:" + message.invocationId(), message.payload());
            provenance.add(ModelInputProvenance.Kind.INBOUND_ATTRIBUTES,
                    "invocation:" + message.invocationId(), message.attributes());
            byte[] body = OpenAiCompatibleChat.writeRequest(settings.model(), prompt, settings.tuning());
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(
                    settings.profile().endpoint(), "POST",
                    Map.of("content-type", List.of("application/json")), body,
                    Duration.ofMillis(settings.timeoutMs()),
                    settings.profile().credentialBinding().orElse(null), null,
                    ExternalIoLimits.compressedHttp(Math.max(1, body.length),
                            settings.profile().maxResponseBytes(), settings.profile().maxResponseBytes(),
                            settings.profile().maxResponseBytes(), 100,
                            Duration.ofMillis(settings.timeoutMs()), Set.of("application/json")),
                    ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy.SUCCESS_ONLY));
        } catch (RuntimeException failure) {
            lease.close();
            return CompletableFuture.failedFuture(sanitize(failure));
        }
        var result = new CompletableFuture<NodeResult>();
        cancellation.onCancel(call::cancel);
        call.completion().whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(sanitize(failure));
                } else {
                    result.complete(answer(settings, response, message, provenance));
                }
            } catch (RuntimeException invalid) {
                result.completeExceptionally(sanitize(invalid));
            } finally {
                lease.close();
            }
        });
        return result;
    }

    private static NodeResult answer(Settings settings, OutboundHttpResponse response, NodeMessage message,
                                     ModelInputProvenance provenance) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // The body is NOT read into the failure. An endpoint's error document is remote text and
            // may quote the prompt back; the status is the operator-actionable part.
            throw new LlmPromptException(LlmPromptException.Code.ENDPOINT_REJECTED);
        }
        OpenAiCompatibleChat.Completion completion =
                OpenAiCompatibleChat.readCompletion(response.body(), settings.profile().maxResponseBytes());
        var attributes = new LinkedHashMap<String, Object>(message.attributes());
        // The model this bundle ASKED for, never the one the response reports: an endpoint resolves a
        // name to whatever tag it currently has installed and reports that back, so echoing it would
        // persist unaudited remote text under a Ravenroot-looking name in the execution record.
        attributes.put("llm.provider", settings.profile().name());
        attributes.put("llm.model", settings.model());
        attributes.put("llm.finishReason", completion.finishReason());
        attributes.put("llm.truncated", completion.truncated());
        var modelOutput = new LinkedHashMap<String, Object>();
        modelOutput.put("text", completion.text());
        modelOutput.put("finishReason", completion.finishReason());
        completion.promptTokens().ifPresent(value -> modelOutput.put("promptTokens", value));
        completion.completionTokens().ifPresent(value -> modelOutput.put("completionTokens", value));
        provenance.add(ModelInputProvenance.Kind.MODEL_OUTPUT, "completion", Map.copyOf(modelOutput));
        attributes.put(ModelInputProvenance.PROMPT_ATTRIBUTE, provenance.snapshot());
        completion.promptTokens().ifPresent(count -> attributes.put("llm.promptTokens", count));
        completion.completionTokens().ifPresent(count -> attributes.put("llm.completionTokens", count));
        try {
            ExternalIoLimits.compressedHttp(1, settings.profile().maxResponseBytes(),
                    settings.profile().maxResponseBytes(), settings.profile().maxResponseBytes(), 100,
                    Duration.ofMillis(settings.timeoutMs()), Set.of("application/json"))
                    .requireOutputBytes(completion.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        } catch (RuntimeException oversized) {
            throw new LlmPromptException(LlmPromptException.Code.RESPONSE_TOO_LARGE);
        }
        return new NodeResult("continue", completion.text(), Map.copyOf(attributes));
    }

    /**
     * Every failure leaves as an {@link LlmPromptException} carrying a closed code.
     *
     * <p>{@link NodePackageServiceException} is already sanitized by the managed channel; mapping it
     * here keeps this bundle's vocabulary the only one a graph author or an execution record sees.</p>
     */
    private static RuntimeException sanitize(Throwable raw) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof LlmPromptException known) {
            return known;
        }
        if (failure instanceof CancellationException) {
            return new LlmPromptException(LlmPromptException.Code.DEADLINE_EXCEEDED);
        }
        if (failure instanceof NodePackageServiceException service) {
            return new LlmPromptException(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> LlmPromptException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED, TLS_REFUSED ->
                        LlmPromptException.Code.DESTINATION_REFUSED;
                case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> LlmPromptException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> LlmPromptException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> LlmPromptException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED, EFFECT_OUTCOME_INDETERMINATE, BUDGET_EXHAUSTED ->
                        LlmPromptException.Code.TRANSPORT_UNAVAILABLE;
            });
        }
        return new LlmPromptException(LlmPromptException.Code.TRANSPORT_UNAVAILABLE);
    }

    /**
     * Test seam: how many (tenant, profile) admission entries are held right now.
     *
     * <p>Zero when nothing is in flight, which is the property worth pinning — a map keyed by tenant
     * that never shrinks is a slow leak in exactly the deployments that have many tenants.</p>
     */
    int admissionEntries() {
        return profileAdmission.size();
    }
}
