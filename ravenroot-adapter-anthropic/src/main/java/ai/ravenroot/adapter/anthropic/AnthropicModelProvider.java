package ai.ravenroot.adapter.anthropic;

import ai.ravenroot.api.ai.ModelProvider;
import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;
import ai.ravenroot.core.security.egress.EgressHttpClients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A real {@link ModelProvider} over the Anthropic Messages API, on the operator-key path (A3).
 *
 * <h2>Not part of the Ravenroot default distribution</h2>
 * <p>See this module's {@code pom.xml}. Shipping a concrete {@code ModelProvider} inside the default
 * distribution is a release-blocking condition under ADR 0017 and EU AI Act art. 50, enforced by
 * {@code DefaultDistributionModelAdapterBoundaryTest}, {@code ReleaseArtifactModelAdapterBoundaryIT}
 * and {@code ReleaseArtifactBoundaryGate}. This class is installed and registered by an operator who
 * has decided to run it.
 *
 * <h2>The client comes from {@code EgressHttpClients}, and cannot come from anywhere else (SEC-10)</h2>
 * <p>An {@code llm-prompt} node is graph-driven egress: a graph author chooses when it fires and what
 * it says. {@code EgressHttpClients} is <em>"the one way Ravenroot builds an HTTP client for
 * graph-driven egress"</em> — {@code Redirect.NEVER}, no proxy, TLS rules that cannot be switched
 * off — and this adapter uses it rather than resembling it.
 *
 * <p>The shared client prevents redirects and bounds response bodies while streaming. Both
 * properties are required here because a redirect could carry the operator key and rendered prompt
 * to another host, while an unbounded response could exhaust memory before it is refused.
 *
 * <p><b>No constructor accepts an {@link HttpClient}, and that is a deliberate divergence from
 * {@code AnthropicAssistantProvider}.</b> Its client is injected, on the stated grounds that "an
 * adapter that could build its own client is an adapter that can egress outside the SEC-10 gate" — which
 * holds there, because its only caller is an in-tree composition root that is reviewed with it. This
 * adapter has no in-tree composition root: it is constructed by an operator, in code this repository
 * never sees. A client parameter here would not be a gate, it would be the documented way to opt out
 * of one. So the client is built here, from {@code EgressHttpClients}, and there is no seam.
 *
 * <h2>Registering it</h2>
 * <pre>{@code
 * registry.register(new AnthropicModelProvider("anthropic",
 *         new ProviderCredentialResolver(new EnvironmentCredentialResolver())));
 * }</pre>
 * The id passed here is what an author must type into an {@code llm-prompt} node's {@code provider}
 * property. The node's {@code credentialRef} then selects <em>which</em> operator-provisioned key to
 * use, and is resolved through the injected {@link CredentialResolver} — which on the environment
 * backend means {@code RAVENROOT_CREDENTIAL_<hex>}. No secret is ever read from the graph.
 *
 * <h2>Concurrency, and why nothing blocks</h2>
 * <p>Safe for concurrent use, as {@link ModelProvider} and ADR 0024 §3 require. All fields
 * are immutable; {@link HttpClient} is documented as safe for concurrent use; every per-call object —
 * the resolved secret, the key holder, the request and the response — is a local.
 *
 * <p>{@link HttpClient#sendAsync} is used rather than {@code send}, so no caller thread is parked on
 * network I/O and slow completions cannot starve common-pool tasks. There is no executor parameter
 * because the implementation does not submit a blocking call.
 *
 * <h2>One client for the provider's whole life, and no cache keyed by anything</h2>
 * <p>Binding a credential to a client was the vendor SDK's model, and it forced a client per call —
 * real cost, charged deliberately, because {@link ModelProvider} declares no lifecycle method on
 * which a longer-lived one could ever be released. On the Messages API the credential is a
 * <em>request header</em>, so that whole problem dissolves: one client serves every credential
 * reference, and "no client cache" stops being a discipline and becomes a fact about the type — there
 * is no map here to key by graph content, and nothing holds key material between calls. Kept
 * un-closed for the process's lifetime, exactly as {@code HttpRequestNodeBehaviorFactory} keeps its
 * own egress client, which is the in-tree precedent for a client the SPI gives no way to dispose of.
 */
public final class AnthropicModelProvider implements ModelProvider {

    /**
     * Used when the node leaves {@code model} blank. The {@code model} node property is optional by
     * descriptor, so a blank value is an ordinary configuration, not a defect.
     */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    /**
     * Used when the node declares no usable {@code parameter.maxTokens}.
     *
     * <p>The Messages API requires {@code max_tokens} on every request. {@code ModelRequest} has no
     * field for it, so a <em>mandatory</em> vendor parameter has to arrive through
     * {@code parameters}, the untyped {@code Map<String, Object>} the node fills from its
     * {@code parameter.*} properties — unvalidated, unadvertised in the node descriptor, and
     * therefore absent from most graphs. Defaulting is the only alternative to failing every node
     * that does not know to set it. The value matches {@code AssistantConfiguration}'s own default
     * for the same endpoint.
     */
    public static final long DEFAULT_MAX_TOKENS = 16_000L;

    /**
     * The version header the Messages API requires on every request.
     *
     * <p>The same constant exists as {@code AnthropicAssistantProvider.API_VERSION} in
     * {@code ravenroot-server}, and is repeated rather than shared because that module is not on this
     * one's classpath. Two copies of a wire constant is a real cost and it is named in the
     * architecture document as the second candidate for genuine sharing between the two clients.
     */
    static final String API_VERSION = "2023-06-01";

    /** The vendor's own origin, used when the operator supplies no {@code baseUrl}. */
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** The only path this adapter ever calls. */
    static final String MESSAGES_PATH = "/v1/messages";

    /**
     * Budgets for reading a provider response, matching the streaming response boundary above.
     *
     * <p>Deliberately not {@link PayloadLimits#DEFAULTS}: that profile caps a single text value at
     * 32 KiB, which a long model answer exceeds — a control-plane payload budget applied to a model
     * response would turn "the model wrote a lot" into a parse failure.
     *
     * <p>{@link PayloadLimits#maxEncodedBytes()} does double duty: it is also the ceiling the socket
     * is read against, so the transport bound and the parse bound cannot drift apart into a window
     * where bytes are accepted off the wire only to be rejected once they are already resident. One
     * number, read twice.
     */
    static final PayloadLimits RESPONSE_LIMITS =
            new PayloadLimits(8 * 1024 * 1024, 32, 10_000, 200_000, 4 * 1024 * 1024, 256);

    /**
     * The stop reasons this adapter is willing to repeat. Anything else becomes {@code ""}.
     *
     * <p>{@code stop_reason} is a string the far end chooses. Passing it through would put unaudited
     * remote text into a field named as if Ravenroot had authored it. A new vendor stop reason shows
     * up as {@code ""} until this set is extended — a visible gap rather than an invisible
     * passthrough.
     */
    private static final Set<String> KNOWN_STOP_REASONS =
            Set.of("end_turn", "max_tokens", "stop_sequence", "tool_use", "pause_turn", "refusal");

    private final String id;
    private final CredentialResolver credentials;
    private final URI endpoint;
    private final Duration timeout;

    /**
     * SEC-10's client. Built here and never injected — see this class's Javadoc for why this adapter
     * diverges from {@code AnthropicAssistantProvider} on exactly that point.
     */
    private final HttpClient client = EgressHttpClients.create();

    /** Production construction: the vendor's own endpoint. */
    public AnthropicModelProvider(String id, CredentialResolver credentials) {
        this(id, credentials, "", Duration.ofMinutes(10));
    }

    /**
     * @param baseUrl operator-supplied origin, or {@code ""} for the vendor default. Never graph
     *     content: a graph that could choose the host could send an operator credential anywhere.
     * @param timeout applied to the exchange up to the response head. It does not bound the body,
     *     which {@link #RESPONSE_LIMITS} bounds by size instead.
     */
    public AnthropicModelProvider(String id, CredentialResolver credentials, String baseUrl,
                                  Duration timeout) {
        this.id = requireText(id, "id");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.endpoint = messagesEndpoint(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static URI messagesEndpoint(String baseUrl) {
        String origin = baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return URI.create(origin + MESSAGES_PATH);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionStage<ModelResponse> generate(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest httpRequest;
        String model;
        try {
            model = request.model().isBlank() ? DEFAULT_MODEL : request.model();
            httpRequest = buildRequest(request, model);
        } catch (ModelInvocationException refused) {
            // Everything before the socket fails eagerly, without occupying anything.
            return CompletableFuture.failedFuture(refused);
        }
        // The shared handler refuses oversized declarations before reading, cancels an undeclared
        // streaming breach, accepts JSON only, and permits at most one bounded gzip member. Wire,
        // decoded, and parser-input ceilings remain separate even though this profile sets them to
        // the same conservative value.
        return client.sendAsync(httpRequest,
                        BoundedBodyHandlers.withLimits(ExternalIoLimits.compressedHttp(
                                Math.max(1, httpRequest.bodyPublisher().orElseThrow().contentLength()),
                                RESPONSE_LIMITS.maxEncodedBytes(), RESPONSE_LIMITS.maxEncodedBytes(),
                                RESPONSE_LIMITS.maxEncodedBytes(), 100, timeout,
                                Set.of("application/json"))))
                .handle((response, failure) -> translate(response, failure, model));
    }

    // ---------------------------------------------------------------------------------------------
    // Request
    // ---------------------------------------------------------------------------------------------

    private HttpRequest buildRequest(ModelRequest request, String model) {
        String reference = request.credentialReference();
        if (reference.isBlank()) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_REFERENCE_ABSENT);
        }
        Optional<SecretValue> resolved = credentials.resolve(reference);
        if (resolved.isEmpty()) {
            // Deliberately says nothing about WHICH reference failed. The reference is the field the
            // editor exposes as a plain text box, so it is the field most likely to contain a pasted
            // secret rather than a name for one.
            throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_UNRESOLVED);
        }

        byte[] body = writeRequest(request, model).getBytes(StandardCharsets.UTF_8);
        try (SecretValue secret = resolved.get();
             AnthropicOperatorKey key = AnthropicOperatorKey.from(secret)) {
            if (key.isBlank()) {
                throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE);
            }
            try {
                return key.authenticate(HttpRequest.newBuilder(endpoint)
                                .timeout(timeout)
                                .header("content-type", "application/json")
                                .header("accept", "application/json")
                                .header("anthropic-version", API_VERSION))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
            } catch (IllegalArgumentException refused) {
                // The JDK refuses a credential that is not a legal HTTP field value -- a control
                // character, most realistically a newline pasted with the key. Mapped, never
                // rendered: the exception it throws quotes the value it rejected.
                throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE);
            }
        }
        // Both holders are erased on the way out of the block above, so the material is zeroed
        // before the request is sent rather than after it returns.
    }

    /**
     * Writes the Messages API request through {@link PayloadJson}.
     *
     * <p>Built as a {@link PayloadValue} tree and serialised by the audited writer, rather than
     * assembled from string fragments with an escaper — {@code AnthropicAssistantProvider} does the
     * latter because {@code JsonStrings} is a {@code ravenroot-server} class and
     * {@code ravenroot-application-api} is where this adapter's dependency stops. The tree form is
     * the better one anyway: a prompt containing a quote, a backslash or a control character cannot
     * break out of the document, because it is never concatenated into one.
     */
    String writeRequest(ModelRequest request, String model) {
        var textBlock = new LinkedHashMap<String, PayloadValue>();
        textBlock.put("type", PayloadValue.of("text"));
        // Graph content goes in the USER turn and never in `system`. The system turn is the
        // operator's authority channel; putting an author's prompt there would let a graph grant
        // itself standing the deployment never gave it. The prompt arrives already rendered by the
        // node, with {{payload}} substituted.
        textBlock.put("text", PayloadValue.of(request.prompt()));

        var userMessage = new LinkedHashMap<String, PayloadValue>();
        userMessage.put("role", PayloadValue.of("user"));
        userMessage.put("content", PayloadValue.list(PayloadValue.map(textBlock)));

        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("model", PayloadValue.of(model));
        root.put("max_tokens", PayloadValue.of(maxTokens(request)));
        root.put("messages", PayloadValue.list(PayloadValue.map(userMessage)));
        return PayloadJson.write(PayloadValue.map(root));
    }

    /**
     * Reads {@code parameter.maxTokens} off the node, tolerating the fact that GraphML properties
     * arrive as text. A malformed or non-positive value falls back to {@link #DEFAULT_MAX_TOKENS}
     * rather than throwing: the value is graph content, and a credential-adjacent execution path is
     * not where attacker-influenced text should be able to raise a new exception type.
     */
    private static long maxTokens(ModelRequest request) {
        Object declared = request.parameters().get("maxTokens");
        if (declared instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : DEFAULT_MAX_TOKENS;
        }
        if (declared instanceof String text) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : DEFAULT_MAX_TOKENS;
            } catch (NumberFormatException malformed) {
                return DEFAULT_MAX_TOKENS;
            }
        }
        return DEFAULT_MAX_TOKENS;
    }

    // ---------------------------------------------------------------------------------------------
    // Response
    // ---------------------------------------------------------------------------------------------

    private ModelResponse translate(HttpResponse<byte[]> response, Throwable failure, String model) {
        if (failure != null) {
            throw transportFailure(failure);
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            // The client did not follow it, which is the whole SEC-10 point: a redirect is a second
            // destination the policy never saw, and following it is what puts an operator key and a
            // rendered prompt on a host nobody authorised.
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.REDIRECT_REFUSED, status);
        }
        if (status == 401 || status == 403) {
            // ADR 0018 section 4: an authentication rejection is terminal. Never retried here, and
            // never retried by the caller either -- a loop against a revoked key locks the account out.
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.NOT_AUTHORIZED, status);
        }
        if (status == 429) {
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.RATE_LIMITED, status);
        }
        if (status >= 400 && status < 500) {
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.REQUEST_REJECTED, status);
        }
        if (status < 200 || status >= 500) {
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.PROVIDER_UNAVAILABLE, status);
        }
        // The body of every branch above is read and dropped unexamined. It is remote text this
        // adapter cannot audit, and an error body may quote the request back -- including the key.
        return readCompletion(response.body(), model);
    }

    private static ModelInvocationException transportFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (cause == null) {
            cause = failure;
        }
        if (cause instanceof BoundedBodyHandlers.ResponseTooLargeException
                || cause instanceof BoundedBodyHandlers.ResponseMediaTypeException
                || cause instanceof BoundedBodyHandlers.ResponseEncodingException) {
            // Checked before IOException, which it extends. Not a transport failure: the socket did
            // not fail, so telling the operator the provider could not be reached would be false and
            // would advise a retry that reproduces the same response.
            return ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.RESPONSE_UNREADABLE, cause.getClass());
        }
        if (cause instanceof SecurityException) {
            // The SEC-10 resolver guard and the reserved-network filter surface here: the name
            // resolved to an address this deployment refuses to reach. An operator's egress problem,
            // not a provider outage.
            return ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.EGRESS_REFUSED, cause.getClass());
        }
        if (cause instanceof IOException || cause instanceof InterruptedException) {
            return ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.TRANSPORT_FAILURE, cause.getClass());
        }
        // Anything else the client or this adapter can raise, named by class only. The cause itself
        // is dropped: it is the one object here that may carry a message assembled from a response.
        return ModelInvocationException.fromTransport(
                ModelInvocationException.Reason.TRANSPORT_FAILURE, cause.getClass());
    }

    /**
     * A 2xx becomes a completion, or a named failure. It never becomes an empty answer.
     *
     * @param model the model this adapter <em>asked</em> for, never the one the response reports.
     *     {@code ModelResponse.model()} is one of the three fields the {@code llm-prompt} node
     *     actually reads, and it becomes the {@code llm.model} node attribute — so it is written into
     *     the execution record and persists. Echoing the provider's own string into it would persist
     *     unaudited remote text under a Ravenroot-looking name.
     */
    private ModelResponse readCompletion(byte[] body, String model) {
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(body, RESPONSE_LIMITS);
        } catch (RuntimeException unreadable) {
            // PayloadException is itself an IllegalArgumentException, so this one clause covers both
            // a budget rejection and a malformed document -- and, deliberately, anything else the
            // reader can throw. Every one of them means the same thing: this build could not read
            // the response, so nothing was answered.
            throw ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.RESPONSE_UNREADABLE, unreadable.getClass());
        }
        if (!(parsed instanceof PayloadValue.MapValue message)) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.RESPONSE_UNREADABLE);
        }
        Map<String, PayloadValue> entries = message.entries();

        // stop_reason FIRST, before content is touched: a refusal is an HTTP 200 whose content is
        // not an answer, and code that reads content[0] unconditionally hands the graph whatever the
        // refusal block contained as if it were the completion.
        String stopReason = knownStopReason(text(entries.get("stop_reason")));
        if ("refusal".equals(stopReason)) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.COMPLETION_REFUSED);
        }

        StringBuilder answer = new StringBuilder();
        if (entries.get("content") instanceof PayloadValue.ListValue content) {
            for (PayloadValue block : content.values()) {
                if (block instanceof PayloadValue.MapValue typed
                        && "text".equals(text(typed.entries().get("type")))) {
                    String value = text(typed.entries().get("text"));
                    if (value != null) {
                        answer.append(value);
                    }
                }
            }
        }
        if (answer.isEmpty()) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.COMPLETION_EMPTY);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stopReason", stopReason);
        // `stop_reason: max_tokens` is a SUCCESSFUL response carrying a truncated answer.
        //
        // MEASURED, AND IT DOES NOT SURVIVE. ModelResponse has no field for incompleteness, so this
        // is the only place the fact can be put -- but LlmPromptNodeBehaviorFactory reads only
        // payload(), providerId() and model(), and ModelResponse.metadata() has NO reader anywhere in
        // the reactor. So a truncated answer reaches the graph indistinguishable from a complete one,
        // and this entry is written for a consumer that does not exist yet. It is recorded here as a
        // product gap rather than presented as a mitigation; see
        // docs/architecture/model-provider-adapters.md.
        metadata.put("truncated", "max_tokens".equals(stopReason));
        usage(entries).forEach(metadata::put);
        // Nothing from the request is echoed into metadata -- not the credential reference, not the
        // parameters, not the prompt.
        return new ModelResponse(answer.toString(), id, model, Map.copyOf(metadata));
    }

    private static Map<String, Object> usage(Map<String, PayloadValue> entries) {
        var counts = new LinkedHashMap<String, Object>();
        if (entries.get("usage") instanceof PayloadValue.MapValue usage) {
            for (String field : List.of("input_tokens", "output_tokens")) {
                if (usage.entries().get(field) instanceof PayloadValue.IntegerValue count) {
                    counts.put(field.equals("input_tokens") ? "inputTokens" : "outputTokens", count.value());
                }
            }
        }
        return counts;
    }

    /** {@code ""} for anything outside {@link #KNOWN_STOP_REASONS}, {@code null} included. */
    private static String knownStopReason(String reported) {
        return reported != null && KNOWN_STOP_REASONS.contains(reported) ? reported : "";
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue textValue ? textValue.value() : null;
    }
}
