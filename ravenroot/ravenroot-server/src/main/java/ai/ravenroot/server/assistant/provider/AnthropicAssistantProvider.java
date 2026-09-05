package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.AssistantOutcome;
import ai.ravenroot.server.audit.JsonStrings;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Anthropic Messages API translator. <b>The one component in the assistant that holds an
 * HTTP client</b> — and it does not build it: the composition root passes in a client constructed by
 * {@code EgressHttpClients}, so SEC-10's redirect, proxy and TLS rules apply to the product's most
 * data-laden outbound call exactly as they apply to a graph's own HTTP node.
 *
 * <h2>Wire facts that are easy to get wrong, recorded because getting them wrong is silent</h2>
 * <ul>
 *   <li><b>A refusal is an HTTP 200.</b> The safety classifiers decline with
 *       {@code stop_reason: "refusal"} and a normal success status, so {@link #readTurn} branches on
 *       {@code stop_reason} <em>before</em> it reads {@code content}. Code that indexes
 *       {@code content[0]} unconditionally breaks here, and code that branches on
 *       {@code stop_details} breaks too, because that object is null on some genuine refusals.</li>
 *   <li><b>{@code temperature}, {@code top_p} and {@code top_k} are rejected</b> by the current
 *       models with a 400. None is sent, and none should be added to steer output — the instruction
 *       pack is the steering surface.</li>
 *   <li><b>{@code max_tokens} bounds reasoning and visible text together</b> on models where thinking
 *       is on by default, which is why a ceiling sized for the answer alone truncates the answer. See
 *       {@code AssistantConfiguration#DEFAULT_MAX_OUTPUT_TOKENS}.</li>
 *   <li><b>Server-side {@code fallbacks} are deliberately not enabled.</b> They would silently re-run
 *       a declined request on a different model, billed to the same subscription, with the author
 *       never told the model changed. A refusal here is reported as a refusal.</li>
 * </ul>
 *
 * <h2>Why the JSON is hand-written and hand-read</h2>
 * <p>Writing uses {@link JsonStrings#escape}, the one shared escaper SEC-14 established. Reading uses
 * {@link PayloadJson}, the bounded reader API-01 established — so a deeply nested or otherwise
 * hostile document is refused <em>while</em> it is parsed rather than after a whole tree exists, and
 * an unparseable body becomes {@link AssistantOutcome.Reason#PROVIDER_UNREADABLE} instead of an empty
 * answer. No JSON dependency is added to reach a single endpoint.</p>
 * <p>A parse-tree budget alone cannot bound a merely enormous response; the transport budget below
 * supplies that separate guarantee.</p>
 *
 * <h2>The response is bounded at transport, decoding, and tree projection</h2>
 * <p>{@link BoundedBodyHandlers} checks the declared and streamed wire bytes, JSON media type,
 * content encoding, decoded bytes, and gzip expansion before a body is returned. {@link PayloadJson}
 * then applies the independent tree, nesting, collection, and string limits. The same finite
 * {@link ExternalIoLimits} deadline and output values are therefore visible at the provider boundary
 * instead of being re-created after an unbounded allocation.</p>
 */
public final class AnthropicAssistantProvider implements AssistantProvider {

    /** The version header the Messages API requires on every request. */
    static final String API_VERSION = "2023-06-01";

    /**
     * The beta flag {@code /v1/messages} requires alongside an OAuth bearer token.
     *
     * <p>Endpoint-dependent in general — some endpoints accept a bearer token without it — but not
     * for this one, which is the only endpoint this adapter calls. Pinned as a constant for the same
     * reason {@link #API_VERSION} is: a wire contract must not drift with the calendar without an
     * explicit code change.</p>
     */
    static final String OAUTH_BETA = "oauth-2025-04-20";

    /**
     * Budgets for reading a provider response.
     *
     * <p>Deliberately not {@link PayloadLimits#DEFAULTS}: that profile caps a single text value at
     * 32 KiB, which a long model answer exceeds — a control-plane payload budget applied to a model
     * response would turn "the model wrote a lot" into a parse failure. These are still bounded, and
     * bounded is the property that matters: a provider that streamed megabytes at us is refused.</p>
     *
     * <p>{@link PayloadLimits#maxEncodedBytes()} does double duty: it is also the budget
     * {@link #complete} reads the socket against, so the transport bound and the parse bound cannot
     * drift apart into a window where bytes are accepted off the wire only to be rejected once they
     * are already resident. One number, read twice.</p>
     */
    static final PayloadLimits RESPONSE_LIMITS =
            new PayloadLimits(8 * 1024 * 1024, 32, 10_000, 200_000, 4 * 1024 * 1024, 256);

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;
    private final AssistantCredential credential;
    private final Duration timeout;

    /**
     * @param httpClient <b>must</b> come from {@code EgressHttpClients}. This class does not construct
     *                   one, and must not grow a no-client constructor: an adapter that could build
     *                   its own client is an adapter that can egress outside the controlled-client gate.
     */
    public AnthropicAssistantProvider(HttpClient httpClient, URI endpoint, String model,
                                      AssistantCredential credential, Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Security blockers that prevent this adapter from being connected to a real provider.
     *
     * <p>The composition root refuses to build the adapter while this list is non-empty, making the
     * gate executable rather than advisory. The list is empty because all required boundaries are
     * enforced in code: {@code AssistantCredential.ofNullable} rejects control characters before a
     * credential reaches a request builder; {@link #complete} bounds the response while reading it and
     * reports an oversized body as {@link AssistantOutcome.Reason#PROVIDER_UNREADABLE}; and the outer
     * {@code catch (RuntimeException)} classifies otherwise unnamed adapter failures as
     * {@link AssistantOutcome.Reason#ADAPTER_DEFECT}.</p>
     *
     * <p>The consent gate ({@code AssistantService#requireConsent}) is independent and runs first. It
     * still refuses an egressing adapter when no consent store is configured; see
     * {@code AssistantCompositionRootTest}.</p>
     */
    public static final java.util.List<String> OPEN_CONNECTION_BLOCKERS = java.util.List.of();

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    /** This adapter opens a socket. See {@link AssistantProvider#egresses()} for what that gates. */
    @Override
    public boolean egresses() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <h4>The outer boundary: no unchecked exception leaves this method</h4>
     * <p>Everything below runs inside one outer {@code try}, closed by a single
     * {@code catch (RuntimeException)} at the bottom. It exists because three spots in this method
     * had no guard at all: {@link #writeRequest}, which runs before any inner {@code try}; the bare
     * unchecked exceptions {@link HttpClient#send} can throw past its {@code InterruptedException} /
     * {@code SecurityException} / {@code IOException} catches — including {@link
     * java.io.UncheckedIOException}, which is a {@code RuntimeException} and therefore <em>not</em>
     * caught by {@code catch (IOException)}; and {@link PayloadJson#write} inside {@link #readTurn}'s
     * {@code tool_use} loop, which re-encodes a block's input outside that method's own
     * {@code PayloadJson.read} guard. Every one of those is this adapter's own defect, not something
     * the provider did, which is exactly what {@link AssistantOutcome.Reason#ADAPTER_DEFECT} means.</p>
     *
     * <p><b>Why one outer catch rather than three local ones.</b> Every named catch already in this
     * method — {@code IllegalArgumentException} on the request build, {@code InterruptedException} /
     * {@code SecurityException} / {@code IOException} on the send, {@code IOException} on the bounded
     * read — is more specific and sits in its own inner {@code try}, and each already ends by throwing
     * the checked {@link AssistantProviderException} rather than a {@code RuntimeException}. A checked
     * exception is never caught by {@code catch (RuntimeException)}, so none of those distinctions the
     * taxonomy already draws is swallowed here: this boundary only ever sees a {@code RuntimeException}
     * that escaped past all of them.</p>
     *
     * <p><b>Not {@code UNCLASSIFIED}.</b> See {@link AssistantOutcome.Reason#ADAPTER_DEFECT}'s own
     * Javadoc for why that name was proposed and rejected for this case.</p>
     *
     * <p>The cause is retained on the thrown {@link AssistantProviderException} for the server log, and
     * is never interpolated into the message this method authors — the same sanitization discipline
     * already applied to every provider-originated failure above.</p>
     */
    @Override
    public Turn complete(Request request) throws AssistantProviderException {
        try {
            byte[] body = writeRequest(request).getBytes(StandardCharsets.UTF_8);
            HttpRequest httpRequest;
            try {
                var builder = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("anthropic-version", API_VERSION);
                // The one place the credential is exposed, and the reason
                // AssistantCredential#expose is named to stand out in a diff. Which header it goes
                // into is the credential's own answer: an OAuth access token is not a
                // drop-in value for an API key, and putting one in x-api-key yields a 401 this
                // build would report as PROVIDER_REJECTED -- an operator hunting a revoked key that
                // was never issued.
                //
                // A switch expression, and the distinction is the guarantee rather than a style
                // preference. Java does not check statement switches for exhaustiveness, so a third
                // enum constant could compile with no error or warning, take no branch, and send the
                // request with no authentication header at all. The provider answers 401, this
                // adapter reports PROVIDER_REJECTED,
                // and the operator hunts the revoked key described two paragraphs up: the exact
                // defect this structure prevents. As an expression the compiler refuses to build a
                // third constant without a branch.
                //
                // Deliberately not a `default -> throw`: that also covers the enum, but moves the
                // failure from compile time to the first request a deployment makes in production.
                var authenticated = switch (credential.scheme()) {
                    case API_KEY -> builder.header("x-api-key", credential.expose());
                    case OAUTH_BEARER -> builder
                            .header("authorization", "Bearer " + credential.expose())
                            // Not optional: /v1/messages rejects a bearer token without it.
                            .header("anthropic-beta", OAUTH_BETA);
                };
                httpRequest = authenticated
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
            } catch (IllegalArgumentException rejected) {
                throw new AssistantProviderException(AssistantOutcome.Reason.EGRESS_REFUSED,
                        "the provider destination was refused", rejected);
            }

            HttpResponse<byte[]> response;
            ExternalIoLimits limits = ExternalIoLimits.compressedHttp(Math.max(1, body.length),
                    RESPONSE_LIMITS.maxEncodedBytes(), RESPONSE_LIMITS.maxEncodedBytes(),
                    AssistantOutputLimit.ceiling(RESPONSE_LIMITS.maxEncodedBytes()), 100, timeout,
                    java.util.Set.of("application/json"));
            try {
                response = httpClient.send(httpRequest, BoundedBodyHandlers.withLimitsForSuccess(limits));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                        "the provider call was interrupted", interrupted);
            } catch (SecurityException refused) {
                // The SEC-10 resolver guard and the reserved-network filter both surface here: the
                // name resolved to an address this deployment refuses to reach. An operator's egress
                // problem, not a provider outage, and the panel must say so rather than advise a retry.
                throw new AssistantProviderException(AssistantOutcome.Reason.EGRESS_REFUSED,
                        "outbound policy refused the provider destination", refused);
            } catch (IOException transportFailure) {
                if (boundedResponseFailure(transportFailure)) {
                    throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                            "the provider response exceeded its response budget", null);
                }
                throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                        "the provider could not be reached", transportFailure);
            }

            int status = response.statusCode();
            byte[] responseBody;
            if (status == 401 || status == 403) {
                    // ADR 0018 section 4: an authentication rejection is terminal. Never retried here,
                    // and never retried by the caller either -- a loop against a revoked key locks the
                    // account out.
                    throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_REJECTED,
                            "the provider rejected the credential", null);
            }
            if (status >= 400 && status < 500) {
                    throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_REJECTED,
                            "the provider rejected the request with status " + status, null);
            }
            if (status >= 500 || status < 200) {
                    throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                            "the provider answered with status " + status, null);
            }
            responseBody = response.body();
            return AssistantOutputLimit.requireWithin(readTurn(responseBody), limits);
        } catch (RuntimeException unexpected) {
            // The outer boundary. Reached only by a RuntimeException none of the catches above was
            // written for -- see this method's own Javadoc for the three concrete sources.
            // A RuntimeException raised inside the try-with-resources above arrives here with any
            // close()-time IOException attached as a SUPPRESSED exception, not as this one's cause;
            // that suppressed detail travels with `unexpected` into the server log along with it.
            throw new AssistantProviderException(AssistantOutcome.Reason.ADAPTER_DEFECT,
                    "the adapter failed with an unexpected internal error", unexpected);
        }
    }

    private static boolean boundedResponseFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof BoundedBodyHandlers.ResponseTooLargeException
                    || current instanceof BoundedBodyHandlers.ResponseMediaTypeException
                    || current instanceof BoundedBodyHandlers.ResponseEncodingException) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Response
    // ---------------------------------------------------------------------------------------------

    /**
     * A 2xx becomes a turn, or a named failure. It never becomes empty text.
     *
     * <p>Every branch below that cannot produce words throws {@link AssistantOutcome.Reason#PROVIDER_UNREADABLE}
     * rather than returning {@code Answer("")}. That is the whole point of this method: the panel's own
     * client already refuses to fabricate on the HTTP layer, and this is the same refusal one layer
     * further out, where the fabricated words would be attributed to the model.</p>
     */
    Turn readTurn(byte[] body) throws AssistantProviderException {
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(body, RESPONSE_LIMITS);
        } catch (RuntimeException unreadable) {
            // PayloadException is itself an IllegalArgumentException, so this one clause covers both a
            // budget rejection and a malformed document -- and, deliberately, anything else the reader
            // can throw. Every one of them means the same thing to the author: this build could not
            // read the response, so nothing was answered.
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response could not be parsed", unreadable);
        }
        if (!(parsed instanceof PayloadValue.MapValue message)) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response was not an object", null);
        }
        Map<String, PayloadValue> entries = message.entries();

        // stop_reason FIRST, before content is touched. A refusal is a 200 with a stop_reason.
        String stopReason = text(entries.get("stop_reason"));
        if ("refusal".equals(stopReason)) {
            // stop_details is deliberately not consulted for the branch: it can be null on a genuine
            // refusal, so branching on it would misclassify a refusal as an unreadable response.
            String category = null;
            if (entries.get("stop_details") instanceof PayloadValue.MapValue details) {
                category = text(details.entries().get("category"));
            }
            return new Turn.Refused(category);
        }

        String reportedModel = text(entries.get("model"));
        if (!(entries.get("content") instanceof PayloadValue.ListValue content)) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response carried no content array", null);
        }

        var toolCalls = new ArrayList<Content.ToolUse>();
        var assistantContent = new ArrayList<Content>();
        var answer = new StringBuilder();
        for (PayloadValue block : content.values()) {
            if (!(block instanceof PayloadValue.MapValue typed)) {
                continue;
            }
            Map<String, PayloadValue> fields = typed.entries();
            String type = text(fields.get("type"));
            if ("text".equals(type)) {
                String value = text(fields.get("text"));
                if (value != null && !value.isEmpty()) {
                    answer.append(value);
                    assistantContent.add(new Content.Text(value));
                }
            } else if ("tool_use".equals(type)) {
                String id = text(fields.get("id"));
                String name = text(fields.get("name"));
                PayloadValue input = fields.get("input");
                if (id == null || name == null) {
                    throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                            "a tool_use block was missing its id or name", null);
                }
                // The arguments are carried opaquely as the JSON the provider sent, re-encoded through
                // the same bounded writer that read them. Nothing between here and the tool interprets
                // them, so no intermediate layer can widen what a tool receives.
                String inputJson = input == null ? "{}" : PayloadJson.write(input);
                var use = new Content.ToolUse(id, name, inputJson);
                toolCalls.add(use);
                assistantContent.add(use);
            }
            // Any other block type (thinking, server tool use) is not replayed: this adapter sends no
            // thinking configuration, and a block we do not understand must not be echoed back as if we
            // did. Dropping it is safe here precisely because we never claim to have replayed the turn
            // verbatim -- see the ToolCalls record's own note.
        }

        if (!toolCalls.isEmpty()) {
            return new Turn.ToolCalls(toolCalls, reportedModel, assistantContent);
        }

        boolean truncated = "max_tokens".equals(stopReason);
        String text = answer.toString();
        if (text.isBlank()) {
            // A 2xx with no words is not an empty answer -- it is a response this build cannot turn
            // into one. Reported as unreadable so the panel says so, rather than rendering silence.
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response carried no text and no tool call", null);
        }
        return new Turn.Answer(text, reportedModel, truncated);
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue textValue ? textValue.value() : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Request
    // ---------------------------------------------------------------------------------------------

    String writeRequest(Request request) {
        var json = new StringBuilder(512);
        json.append("{\"model\":\"").append(JsonStrings.escape(request.model())).append('"');
        json.append(",\"max_tokens\":").append(request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            json.append(",\"system\":\"").append(JsonStrings.escape(request.system())).append('"');
        }
        if (!request.tools().isEmpty()) {
            json.append(",\"tools\":[");
            for (int index = 0; index < request.tools().size(); index++) {
                ToolSpec tool = request.tools().get(index);
                if (index > 0) {
                    json.append(',');
                }
                json.append("{\"name\":\"").append(JsonStrings.escape(tool.name()))
                        .append("\",\"description\":\"").append(JsonStrings.escape(tool.description()))
                        .append("\",\"input_schema\":").append(tool.inputSchemaJson()).append('}');
            }
            json.append(']');
        }
        json.append(",\"messages\":[");
        for (int index = 0; index < request.messages().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            writeMessage(json, request.messages().get(index));
        }
        json.append("]}");
        return json.toString();
    }

    private static void writeMessage(StringBuilder json, Message message) {
        json.append("{\"role\":\"")
                .append(message.role() == Role.AUTHOR ? "user" : "assistant")
                .append("\",\"content\":[");
        List<Content> blocks = message.content();
        for (int index = 0; index < blocks.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            // Exhaustive over the sealed Content hierarchy: a new block type is a compile error here
            // rather than a block silently dropped on the way to the provider.
            switch (blocks.get(index)) {
                case Content.Text text -> json.append("{\"type\":\"text\",\"text\":\"")
                        .append(JsonStrings.escape(text.text())).append("\"}");
                case Content.ToolUse use -> json.append("{\"type\":\"tool_use\",\"id\":\"")
                        .append(JsonStrings.escape(use.id())).append("\",\"name\":\"")
                        .append(JsonStrings.escape(use.name())).append("\",\"input\":")
                        .append(use.inputJson()).append('}');
                case Content.ToolResult result -> json.append("{\"type\":\"tool_result\",\"tool_use_id\":\"")
                        .append(JsonStrings.escape(result.toolUseId())).append("\",\"is_error\":")
                        .append(result.isError()).append(",\"content\":\"")
                        .append(JsonStrings.escape(result.content())).append("\"}");
            }
        }
        json.append("]}");
    }
}
