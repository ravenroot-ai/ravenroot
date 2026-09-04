package ai.ravenroot.adapter.openaicompatible;

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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A real {@link ModelProvider} over an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>One implementation covers a local Ollama, LM Studio, vLLM and the hosted providers that
 * advertise the same wire shape, because what varies between them is configuration — an endpoint, a
 * model name, whether a key is read — and not the document. A typical local target is a
 * {@code qwen3} served by Ollama on {@code http://127.0.0.1:11434}.
 *
 * <h2>Not part of the Ravenroot default distribution</h2>
 * <p>See this module's {@code pom.xml}. Shipping a concrete {@code ModelProvider} inside the default
 * distribution is a release-blocking condition under ADR 0017 and EU AI Act art. 50, enforced by
 * {@code DefaultDistributionModelAdapterBoundaryTest}, {@code ReleaseArtifactModelAdapterBoundaryIT}
 * and {@code ReleaseArtifactBoundaryGate}. This class is installed and registered by an operator who
 * has decided to run it.
 *
 * <p><b>And "registered by an operator" is, today, the whole story — there is no other way in.</b>
 * {@code RavenrootServerMain} builds an empty {@code ModelProviderRegistry} and nothing in the
 * shipped server or CLI ever calls {@code register}; there is no {@code ServiceLoader} for this SPI,
 * and {@code NodePackage} — the operator-named extension point that <em>does</em> load classes from
 * outside the artifact — contributes {@code NodeBehavior}s and is handed no
 * {@code BehaviorEnvironment}, so it cannot reach the registry either. Registering this provider
 * therefore requires an <em>embedding</em> composition root: code that builds its own
 * {@link ai.ravenroot.core.runtime.BehaviorEnvironment} and hands it a populated registry. That is
 * not an oversight to be fixed here — P3 of {@code ReleaseArtifactBoundaryChecks} makes a call to
 * {@code ModelProviderRegistry#register} reachable from {@code RavenrootServerMain#main} <em>the</em>
 * release violation, so any in-artifact loading mechanism is precisely what the gate exists to stop.
 * A supported dynamic-installation mechanism would belong to the server composition layer, not to
 * this adapter.
 *
 * <h2>The client comes from {@code EgressHttpClients}, and cannot come from anywhere else (SEC-10)</h2>
 * <p>An {@code llm-prompt} node is graph-driven egress: a graph author chooses when it fires and what
 * it says. {@code EgressHttpClients} is <em>"the one way Ravenroot builds an HTTP client for
 * graph-driven egress"</em> — {@code Redirect.NEVER}, no proxy, TLS rules that cannot be switched
 * off — and this adapter uses it rather than resembling it.
 *
 * <p><b>No constructor accepts an {@link HttpClient}</b>, for the reason
 * {@code ravenroot-adapter-anthropic} states and this module inherits: this adapter has no in-tree
 * composition root, so a client parameter would not be a gate, it would be the documented way to opt
 * out of one. Pinned by {@code OpenAiCompatibleModelProviderEgressTest#noConstructorAcceptsAnHttpClient}.
 *
 * <h2>{@code OutboundHttpPolicy} is deliberately not consulted, and that is a divergence worth reading</h2>
 * <p>{@code OutboundHttpPolicy} is the SSRF boundary for URLs that come out of <em>graph content</em>
 * — the {@code http-request} node's {@code url} property. This adapter's endpoint is operator
 * configuration, fixed at construction and never influenced by a graph, a payload or a request. A
 * policy instance built from the same operator input it would be checking cannot refuse anything, so
 * consulting one here would be the appearance of a control rather than a control; and consulting the
 * <em>node's</em> policy instance would couple this adapter's reach to the {@code http-request}
 * node's host allowlist, so allowlisting a local model endpoint would also hand every graph's HTTP
 * node a route to it. The sibling adapter reaches the same conclusion by not consulting it either.
 *
 * <p>What actually governs reach here, and does so without being asked: {@code EgressHttpClients}
 * (no redirect, no proxy, TLS that cannot be relaxed), the JVM-wide reserved-network filter in
 * {@code EgressAddressGuard} for any endpoint written as a <em>name</em>, {@link BoundedBodyHandlers}
 * on the way back, and this class's own scheme and user-info validation at construction.
 *
 * <p><b>Operational consequence.</b> A literal IP
 * address never reaches the resolver SPI — the JDK parses {@code 127.0.0.1} without consulting it —
 * so {@code http://127.0.0.1:11434/v1/chat/completions} needs no egress configuration at all.
 * {@code http://localhost:11434/...} goes through the resolver and is permitted by
 * {@code ReservedNetworkPolicy}'s shipped default, which exempts exactly the name {@code localhost}
 * for {@code LOOPBACK}. Both work; a third loopback name would not, until an operator adds it to
 * {@code RAVENROOT_EGRESS_RESERVED_EXCEPTIONS}.
 *
 * <h2>Registering it</h2>
 * <pre>{@code
 * // Local Ollama, no credential:
 * registry.register(new OpenAiCompatibleModelProvider("ollama-local",
 *         new ProviderCredentialResolver(new EnvironmentCredentialResolver()),
 *         "http://127.0.0.1:11434/v1/chat/completions", "qwen3",
 *         CredentialRequirement.NONE, Duration.ofMinutes(10)));
 *
 * // A hosted OpenAI-compatible endpoint, operator key:
 * registry.register(new OpenAiCompatibleModelProvider("openai-main",
 *         new ProviderCredentialResolver(new EnvironmentCredentialResolver()),
 *         "https://api.example.com/v1/chat/completions", "gpt-4o-mini",
 *         CredentialRequirement.REQUIRED, Duration.ofMinutes(10)));
 * }</pre>
 * The id passed here is what an author must type into an {@code llm-prompt} node's {@code provider}
 * property. The node's {@code credentialRef} then selects <em>which</em> operator-provisioned key to
 * use, and is resolved through the injected {@link CredentialResolver} — which on the environment
 * backend means {@code RAVENROOT_CREDENTIAL_<hex>}. No secret is ever read from the graph.
 *
 * <h2>Concurrency, and why nothing blocks</h2>
 * <p>Safe for concurrent use, as {@link ModelProvider} and ADR 0024 §3 require. All fields
 * are immutable; {@link HttpClient} is documented as safe for concurrent use; every per-call object —
 * the resolved secret, the credential holder, the request and the response — is a local. There is no
 * cache of any kind, so nothing holds key material between calls and the rotation
 * {@code SecretProvider} documents stays observable.
 *
 * <p>{@link HttpClient#sendAsync} is used rather than {@code send}, so no caller thread is parked on
 * network I/O. One client is held for the process's lifetime and never closed, exactly as
 * {@code HttpRequestNodeBehaviorFactory} keeps its own egress client — {@link ModelProvider} declares
 * no lifecycle method and {@code ModelProviderRegistry} has no {@code unregister}, so there is no
 * moment at which a longer-lived resource could be released.
 */
public final class OpenAiCompatibleModelProvider implements ModelProvider {

    /**
     * The request member every endpoint in this family understands, written explicitly rather than
     * left to the far end's default.
     *
     * <p>A server whose default is to stream answers with {@code text/event-stream}, and at least one
     * in this family can be configured that way, would hand back a document
     * {@link #readCompletion} cannot read — and the failure would be
     * {@code RESPONSE_UNREADABLE}, which points an operator at the response rather than at the
     * setting. Stating {@code false} is what makes the assumption a request rather than a hope. The
     * SPI expresses no streaming, so buffering is not a choice this adapter is making.
     */
    static final boolean STREAM = false;

    /**
     * Budgets for reading a response, on the same reasoning as the sibling adapter's.
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
     * The finish reasons this adapter is willing to repeat. Anything else becomes {@code ""}.
     *
     * <p>{@code finish_reason} is a string the far end chooses. Passing it through would put unaudited
     * remote text into a field named as if Ravenroot had authored it. A new vendor value shows up as
     * {@code ""} until this set is extended — a visible gap rather than an invisible passthrough.
     */
    private static final Set<String> KNOWN_FINISH_REASONS =
            Set.of("stop", "length", "content_filter", "tool_calls", "function_call");

    /**
     * The {@code parameter.*} entries this adapter forwards, and the wire member each becomes.
     *
     * <p><b>A closed set, and the closure is the control.</b> {@code ModelRequest.parameters()} is an
     * untyped {@code Map<String, Object>} filled verbatim from a node's {@code parameter.*}
     * properties — graph content, unvalidated, and not declared by the node descriptor. Forwarding it
     * wholesale would let a graph author write arbitrary members into an operator's request: most
     * cheaply {@code stream}, which breaks the reader, but equally anything the far end bills for or
     * behaves differently on. Naming the four that are forwarded means a graph can tune a
     * completion and cannot reshape a request.
     *
     * <p><b>What it costs, stated rather than mitigated.</b> An author who writes
     * {@code parameter.topK} gets it silently dropped, and there is today no channel to tell them:
     * {@link ModelResponse#metadata()} has no reader anywhere in the reactor, and
     * {@code LlmPromptNodeBehaviorFactory} propagates only {@code payload}, {@code providerId} and
     * {@code model}. Dropping is still the right branch over refusing — a credential-adjacent
     * execution path is not where attacker-influenced text should be able to raise a new exception
     * type, which is the rule the sibling adapter states on its own parameter reader — but it is a
     * gap, not a design.
     */
    private static final Map<String, String> FORWARDED_PARAMETERS = Map.of(
            "maxTokens", "max_tokens",
            "temperature", "temperature",
            "topP", "top_p",
            "seed", "seed");

    /** The two of {@link #FORWARDED_PARAMETERS} that must be written as JSON integers. */
    private static final Set<String> INTEGER_PARAMETERS = Set.of("maxTokens", "seed");

    private final String id;
    private final CredentialResolver credentials;
    private final URI endpoint;
    private final String defaultModel;
    private final CredentialRequirement credentialRequirement;
    private final Duration timeout;

    /**
     * SEC-10's client. Built here and never injected — see this class's Javadoc for why this adapter
     * diverges from {@code AnthropicAssistantProvider} on exactly that point.
     */
    private final HttpClient client = EgressHttpClients.create();

    /**
     * @param id what an author types into an {@code llm-prompt} node's {@code provider} property
     * @param credentials resolves the node's {@code credentialRef}; consulted per request, never
     *     cached
     * @param endpoint the <b>complete</b> chat-completions URI, for example
     *     {@code http://127.0.0.1:11434/v1/chat/completions}. Operator configuration, never graph
     *     content: a graph that could choose the host could send an operator credential anywhere.
     *     There is deliberately no default and no path synthesis — see
     *     {@link #chatCompletionsEndpoint}.
     * @param defaultModel used when the node leaves {@code model} blank, which the descriptor permits.
     *     Required, because "OpenAI-compatible" names a wire shape and not a vendor, so there is no
     *     model this adapter could pick that would be right for the endpoint the operator chose.
     * @param credentialRequirement whether this endpoint takes a key at all; see
     *     {@link CredentialRequirement} for why this is declared rather than inferred
     * @param timeout applied to the exchange up to the response head. It does not bound the body,
     *     which {@link #RESPONSE_LIMITS} bounds by size instead. A local model on modest hardware can
     *     take minutes on a long prompt, so a value in that range is ordinary rather than generous.
     */
    public OpenAiCompatibleModelProvider(String id, CredentialResolver credentials, String endpoint,
                                         String defaultModel, CredentialRequirement credentialRequirement,
                                         Duration timeout) {
        this.id = requireText(id, "id");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.endpoint = chatCompletionsEndpoint(requireText(endpoint, "endpoint"));
        this.defaultModel = requireText(defaultModel, "defaultModel");
        this.credentialRequirement =
                Objects.requireNonNull(credentialRequirement, "credentialRequirement");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Validates the operator's endpoint, and builds nothing.
     *
     * <p><b>Why the whole URI is required instead of an origin.</b> The sibling adapter takes an
     * origin and appends {@code /v1/messages}, which is correct because Anthropic has exactly one
     * path. This family does not: Ollama and LM Studio serve {@code /v1/chat/completions} at the
     * root, vLLM can be mounted under a prefix, and hosted gateways commonly sit under
     * {@code /api/v1}. Any rule that decides where {@code /v1} goes is a guess, and a guess that is
     * wrong produces a 404 the operator has to reverse-engineer. Asking for the full URI is one more
     * line of configuration, once, and removes the class of bug entirely.
     *
     * <p>Three things are refused, at construction, so a misconfiguration fails when the operator
     * writes it rather than when a traversal first reaches a node:
     * <ul>
     *   <li>a scheme other than {@code http} or {@code https} — the same restriction
     *   {@code OutboundHttpPolicy} applies to a graph-authored URL, for the same reason;</li>
     *   <li><b>user info in the URI</b> ({@code https://key@host/...}). {@code OutboundHttpPolicy}
     *   refuses this with "Credentials are not allowed in node URLs; use credentialRef", and the
     *   hazard is worse here: a credential in the endpoint would be held for the life of the
     *   provider, outside {@link BearerCredential}, and rendered by anything that printed the URI;</li>
     *   <li>a URI with no host.</li>
     * </ul>
     */
    private static URI chatCompletionsEndpoint(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException malformed) {
            // The operator's own string, echoed deliberately: this is construction-time configuration
            // written by the person reading the message, not graph content and not a secret.
            throw new IllegalArgumentException("endpoint is not a valid URI: " + endpoint.trim());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("endpoint must be http or https: " + uri);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("endpoint declares no host: " + uri);
        }
        if (uri.getUserInfo() != null) {
            // Not echoed: the user info IS the credential in this one case.
            throw new IllegalArgumentException(
                    "Credentials are not allowed in the endpoint URI; use a credential reference");
        }
        return uri;
    }

    @Override
    public String id() {
        return id;
    }

    /** The endpoint this provider was configured with. Diagnostics only; never a decision input. */
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public CompletionStage<ModelResponse> generate(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest httpRequest;
        String model;
        try {
            model = request.model().isBlank() ? defaultModel : request.model();
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
        byte[] body = writeRequest(request, model).getBytes(StandardCharsets.UTF_8);

        if (credentialRequirement == CredentialRequirement.NONE) {
            if (!reference.isBlank()) {
                // Refused, not ignored. See CredentialRequirement.NONE for why neither of the other
                // two branches is true.
                throw ModelInvocationException.of(
                        ModelInvocationException.Reason.CREDENTIAL_NOT_ACCEPTED);
            }
            return unauthenticated(body);
        }

        if (reference.isBlank()) {
            throw ModelInvocationException.of(
                    ModelInvocationException.Reason.CREDENTIAL_REFERENCE_ABSENT);
        }
        Optional<SecretValue> resolved = credentials.resolve(reference);
        if (resolved.isEmpty()) {
            // Deliberately says nothing about WHICH reference failed. The reference is the field the
            // editor exposes as a plain text box, so it is the field most likely to contain a pasted
            // secret rather than a name for one.
            throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_UNRESOLVED);
        }
        try (SecretValue secret = resolved.get();
             BearerCredential credential = BearerCredential.from(secret)) {
            if (credential.isBlank()) {
                throw ModelInvocationException.of(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE);
            }
            try {
                return credential.authenticate(baseBuilder())
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

    private HttpRequest unauthenticated(byte[] body) {
        return baseBuilder().POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private HttpRequest.Builder baseBuilder() {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("accept", "application/json");
    }

    /**
     * Writes the chat-completions request through {@link PayloadJson}.
     *
     * <p>Built as a {@link PayloadValue} tree and serialised by the audited writer, rather than
     * assembled from string fragments with an escaper. The tree form is the one that matters here: a
     * prompt is graph content, and a hand-written writer is exactly what an author with a quote, a
     * backslash or a lone surrogate in their prompt would put to the test.
     */
    String writeRequest(ModelRequest request, String model) {
        var userMessage = new LinkedHashMap<String, PayloadValue>();
        userMessage.put("role", PayloadValue.of("user"));
        // Graph content goes in the USER turn and never in a `system` message. The system turn is the
        // deployment's authority channel; putting an author's prompt there would let a graph grant
        // itself standing the operator never gave it -- the same line SEC-09 draws. This adapter
        // sends no system message at all, so there is no branch to forget. The prompt arrives already
        // rendered by the node, with {{payload}} substituted.
        userMessage.put("content", PayloadValue.of(request.prompt()));

        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("model", PayloadValue.of(model));
        root.put("stream", PayloadValue.of(STREAM));
        root.put("messages", PayloadValue.list(PayloadValue.map(userMessage)));
        forwardedParameters(request).forEach(root::put);
        return PayloadJson.write(PayloadValue.map(root));
    }

    /**
     * The subset of {@code parameter.*} this adapter forwards, coerced to JSON numbers.
     *
     * <p>GraphML properties arrive as text, so a declared value may be a {@link Number} or a
     * {@link String}. A malformed one is <b>omitted</b> rather than thrown on, for the reason given
     * on {@link #FORWARDED_PARAMETERS}: the value is graph content, and a credential-adjacent path is
     * not where attacker-influenced text raises new exception types. A non-finite decimal is omitted
     * too — {@code PayloadValue.DecimalValue} refuses NaN and the infinities at construction, so
     * letting one through would turn a graph property into a rejection thrown from inside the writer.
     */
    private static Map<String, PayloadValue> forwardedParameters(ModelRequest request) {
        var forwarded = new LinkedHashMap<String, PayloadValue>();
        for (Map.Entry<String, String> known : FORWARDED_PARAMETERS.entrySet()) {
            Object declared = request.parameters().get(known.getKey());
            if (declared == null) {
                continue;
            }
            PayloadValue value = INTEGER_PARAMETERS.contains(known.getKey())
                    ? integer(declared)
                    : decimal(declared);
            if (value != null) {
                forwarded.put(known.getValue(), value);
            }
        }
        return forwarded;
    }

    private static PayloadValue integer(Object declared) {
        if (declared instanceof Number number) {
            return PayloadValue.of(number.longValue());
        }
        if (declared instanceof String text) {
            try {
                return PayloadValue.of(Long.parseLong(text.trim()));
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }

    private static PayloadValue decimal(Object declared) {
        double value;
        if (declared instanceof Number number) {
            value = number.doubleValue();
        } else if (declared instanceof String text) {
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException malformed) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(value) ? PayloadValue.of(value) : null;
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
            throw ModelInvocationException.fromStatus(
                    ModelInvocationException.Reason.REDIRECT_REFUSED, status);
        }
        if (status == 401 || status == 403) {
            // ADR 0018 section 4: an authentication rejection is terminal. Never retried here, and
            // never retried by the caller either -- a loop against a revoked key locks the account out.
            throw ModelInvocationException.fromStatus(
                    ModelInvocationException.Reason.NOT_AUTHORIZED, status);
        }
        if (status == 429) {
            throw ModelInvocationException.fromStatus(ModelInvocationException.Reason.RATE_LIMITED, status);
        }
        if (status >= 400 && status < 500) {
            throw ModelInvocationException.fromStatus(
                    ModelInvocationException.Reason.REQUEST_REJECTED, status);
        }
        if (status < 200 || status >= 500) {
            throw ModelInvocationException.fromStatus(
                    ModelInvocationException.Reason.PROVIDER_UNAVAILABLE, status);
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
            // not fail, so telling the operator the endpoint could not be reached would be false and
            // would advise a retry that reproduces the same response.
            return ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.RESPONSE_UNREADABLE, cause.getClass());
        }
        if (cause instanceof SecurityException) {
            // The SEC-10 resolver guard and the reserved-network filter surface here: the name
            // resolved to an address this deployment refuses to reach. An operator's egress problem,
            // not an endpoint outage.
            return ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.EGRESS_REFUSED, cause.getClass());
        }
        // IOException and InterruptedException are the ordinary transport failures, and anything else
        // the client or this adapter can raise lands here too. They share one branch on purpose: the
        // distinction the vocabulary draws is between "the socket did not fail" (RESPONSE_UNREADABLE,
        // EGRESS_REFUSED, both above) and "the call did not complete", and every remaining case is
        // the second. The class name is what distinguishes them in the message, and it is a
        // compile-time artefact. The cause object itself is dropped: it is the one thing here that
        // may carry a message assembled from a response.
        return ModelInvocationException.fromTransport(
                ModelInvocationException.Reason.TRANSPORT_FAILURE, cause.getClass());
    }

    /**
     * A 2xx becomes a completion, or a named failure. It never becomes an empty answer.
     *
     * @param model the model this adapter <em>asked</em> for, never the one the response reports.
     *     {@code ModelResponse.model()} is one of the three fields the {@code llm-prompt} node
     *     actually reads, and it becomes the {@code llm.model} node attribute — so it is written into
     *     the execution record and persists. Echoing the endpoint's own string into it would persist
     *     unaudited remote text under a Ravenroot-looking name. This matters more here than on a
     *     single-vendor endpoint: an Ollama deployment resolves {@code qwen3} to whatever tag it
     *     currently has installed and reports that back, so the response's {@code model} is a
     *     deployment fact rather than the request's.
     */
    private ModelResponse readCompletion(byte[] body, String model) {
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(body, RESPONSE_LIMITS);
        } catch (RuntimeException unreadable) {
            // PayloadException is itself an IllegalArgumentException, so this one clause covers both
            // a budget rejection and a malformed document -- and, deliberately, anything else the
            // reader can throw. Every one of them means the same thing: this build could not read the
            // response, so nothing was answered. A streaming (text/event-stream) body lands here too.
            throw ModelInvocationException.fromTransport(
                    ModelInvocationException.Reason.RESPONSE_UNREADABLE, unreadable.getClass());
        }
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.RESPONSE_UNREADABLE);
        }
        Map<String, PayloadValue> entries = root.entries();

        if (!(entries.get("choices") instanceof PayloadValue.ListValue choices)
                || choices.values().isEmpty()
                || !(choices.values().get(0) instanceof PayloadValue.MapValue choice)) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.RESPONSE_UNREADABLE);
        }
        Map<String, PayloadValue> firstChoice = choice.entries();

        // finish_reason FIRST, before content is touched, for the reason the sibling adapter states
        // about stop_reason: a safety refusal arrives as HTTP 200, and code that reads the content
        // unconditionally hands the graph whatever the refusal left there as if it were the answer.
        String finishReason = knownFinishReason(text(firstChoice.get("finish_reason")));
        if ("content_filter".equals(finishReason)) {
            throw ModelInvocationException.of(ModelInvocationException.Reason.COMPLETION_REFUSED);
        }

        String answer = firstChoice.get("message") instanceof PayloadValue.MapValue message
                ? text(message.entries().get("content"))
                : null;
        if (answer == null || answer.isEmpty()) {
            // Covers three real shapes at once: no message object, `"content": null` (which several
            // endpoints send when the turn carried only tool calls -- which this adapter does not
            // support and must not silently render as an empty answer), and an empty string.
            throw ModelInvocationException.of(ModelInvocationException.Reason.COMPLETION_EMPTY);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("finishReason", finishReason);
        // `finish_reason: length` is a SUCCESSFUL response carrying a truncated answer.
        //
        // MEASURED, AND IT DOES NOT SURVIVE -- the same product gap the sibling adapter records.
        // ModelResponse has no field for incompleteness, so this is the only place the fact can be
        // put, and LlmPromptNodeBehaviorFactory reads only payload(), providerId() and model() while
        // ModelResponse.metadata() has NO reader anywhere in the reactor. A truncated answer
        // therefore reaches the graph indistinguishable from a complete one, and this entry is
        // written for a consumer that does not exist yet. Recorded as a gap, not offered as a
        // mitigation; see docs/architecture/model-provider-adapters.md.
        metadata.put("truncated", "length".equals(finishReason));
        usage(entries).forEach(metadata::put);
        // Nothing from the request is echoed into metadata -- not the credential reference, not the
        // parameters, not the prompt.
        return new ModelResponse(answer, id, model, Map.copyOf(metadata));
    }

    private static Map<String, Object> usage(Map<String, PayloadValue> entries) {
        var counts = new LinkedHashMap<String, Object>();
        if (entries.get("usage") instanceof PayloadValue.MapValue usage) {
            if (usage.entries().get("prompt_tokens") instanceof PayloadValue.IntegerValue count) {
                counts.put("promptTokens", count.value());
            }
            if (usage.entries().get("completion_tokens") instanceof PayloadValue.IntegerValue count) {
                counts.put("completionTokens", count.value());
            }
        }
        return counts;
    }

    /** {@code ""} for anything outside {@link #KNOWN_FINISH_REASONS}, {@code null} included. */
    private static String knownFinishReason(String reported) {
        return reported != null && KNOWN_FINISH_REASONS.contains(reported) ? reported : "";
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue textValue ? textValue.value() : null;
    }
}
