package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.payload.PayloadValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One live conversation with one MCP server, over the managed HTTP channel.
 *
 * <h2>The credential is never in this process's reach, and that is why there is no client here</h2>
 * <p>Every byte leaves through {@link ai.ravenroot.api.node.service.OutboundHttpService}, exactly as
 * the model turns do. This class holds a {@link ai.ravenroot.api.node.service.OutboundCredentialBinding}
 * — a <em>name</em> the operator chose — and never a secret; the runtime resolves the name and places
 * the value on the request after this code has finished with it. The bundle therefore has no code
 * path that could return a credential, log one or put one in a failure, because it never has one.
 * This is also why {@link ai.ravenroot.api.node.service.NodePackageCapability#CREDENTIAL_RESOLUTION}
 * is <b>not</b> among {@link AgentNodeBehavior#requiredServices()}: a bundle that could ask for the
 * value would have to be trusted not to, and one that cannot ask does not.</p>
 *
 * <h2>Bounded twice, and the tighter bound wins</h2>
 * <p>Every exchange asks the channel for {@code min(profile deadline, what is left of the run)}. The
 * second half is what stops an agent with a generous per-server deadline and eight tools from
 * outliving the deadline its author set for the whole node: a per-call ceiling multiplied by an
 * unknown number of calls is not a ceiling.</p>
 */
final class McpSession {

    private record ExchangeResult(PayloadValue.MapValue value, long maximumOutputBytes) { }

    private final McpProfile profile;
    private final NodePackageServices services;
    private final NodeMessage message;
    /** What is left of the whole run, asked freshly on every exchange rather than captured. */
    private final LongSupplier remainingRunMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    /**
     * What the server announced, written once when {@code tools/list} answers.
     *
     * <p>Not final, and that is the whole point of the shape: an earlier version performed the
     * handshake on one object and returned a <b>new</b> one carrying the catalogue, which silently
     * reset both {@link #sessionId} and {@link #nextId}. Against a stateful server that made every
     * {@code tools/call} arrive with no session handle and with an id already spent — a defect that
     * discovery could not see, because discovery had already succeeded on the object that was thrown
     * away. One object holds the whole conversation now, which is what a session is.</p>
     *
     * <p>Volatile because it is written on the completion thread of the last discovery exchange and
     * read on whichever thread the loop is running on afterwards.</p>
     */
    private volatile List<McpProtocol.Announced> announced = List.of();
    /** Effective managed ceiling attached to the catalogue response that produced {@link #announced}. */
    private volatile long announcedMaximumOutputBytes = Long.MAX_VALUE;
    /**
     * The server's session handle, when it issued one.
     *
     * <p>Streamable HTTP servers may be stateful and identify a session with {@code Mcp-Session-Id};
     * a stateless one issues none and this stays empty. Echoed on every later request, because a
     * stateful server that does not receive it back answers the second request as if the handshake
     * had never happened.</p>
     */
    private volatile String sessionId = "";

    private McpSession(McpProfile profile, NodePackageServices services, NodeMessage message,
                       LongSupplier remainingRunMillis) {
        this.profile = profile;
        this.services = services;
        this.message = message;
        this.remainingRunMillis = remainingRunMillis;
    }

    /**
     * Performs the handshake and reads the server's catalogue.
     *
     * <p>Three exchanges — {@code initialize}, the {@code initialized} notification,
     * {@code tools/list} — and the notification is not skipped even though nothing reads its answer.
     * A server is entitled to reject work before it, and a client that skips it works against the
     * implementations that are lenient and fails against the ones that are correct.</p>
     *
     * @return a session that knows what the server announced, or a stage failed with an
     *     {@link McpRefusal}
     */
    static CompletionStage<McpSession> open(McpProfile profile, NodePackageServices services,
                                            NodeMessage message, LongSupplier remainingRunMillis) {
        var session = new McpSession(profile, services, message, remainingRunMillis);
        return session.exchange(McpProtocol.initialize(session.nextId.getAndIncrement()), true)
                .thenCompose(ignored -> session.exchange(McpProtocol.initialized(), false))
                .thenCompose(ignored -> session.exchange(
                        McpProtocol.listTools(session.nextId.getAndIncrement()), true))
                .thenApply(exchange -> {
                    // The SAME object all the way through, deliberately: it is carrying the session
                    // handle and the id counter, and a second object would start both from scratch.
                    session.announced = McpProtocol.readTools(
                            exchange.value(), exchange.maximumOutputBytes());
                    session.announcedMaximumOutputBytes = exchange.maximumOutputBytes();
                    return session;
                });
    }

    McpProfile profile() {
        return profile;
    }

    /** What the server said it has. Filtering it against the operator's list is not this class's job. */
    List<McpProtocol.Announced> announced() {
        return announced;
    }

    /** Ceiling that must still hold after server names are added to the model-facing catalogue. */
    long announcedMaximumOutputBytes() {
        return announcedMaximumOutputBytes;
    }

    /**
     * Calls one tool and returns the text of its result.
     *
     * @param toolName the name as the server knows it, never the name the model was given
     * @param argumentsJson the bounded canonical argument object returned by the reference monitor
     * @return a stage carrying model-facing text and the server's independent success signal, or
     *     failed with an {@link McpRefusal}
     */
    CompletionStage<AgentTool.Result> call(String toolName, String argumentsJson) {
        PayloadValue.MapValue arguments;
        try {
            arguments = McpProtocol.readArguments(argumentsJson);
        } catch (McpRefusal refused) {
            return CompletableFuture.failedFuture(refused);
        }
        return exchange(McpProtocol.callTool(nextId.getAndIncrement(), toolName, arguments), true)
                .thenApply(exchange -> new AgentTool.Result(McpProtocol.readToolContent(
                                exchange.value(), exchange.maximumOutputBytes()),
                        McpProtocol.isToolError(exchange.value())
                                ? AgentTool.Outcome.FAILED : AgentTool.Outcome.SUCCEEDED));
    }

    /**
     * One POST, and the reading of what came back.
     *
     * @param expectsResult {@code false} for the {@code initialized} notification, which a server
     *     answers with 202 and an empty body — parsing that as a JSON-RPC response would refuse a
     *     correct server
     */
    private CompletionStage<ExchangeResult> exchange(byte[] body, boolean expectsResult) {
        long remaining = Math.min(profile.timeoutMs(), remainingRunMillis.getAsLong());
        if (remaining <= 0) {
            return CompletableFuture.failedFuture(new McpRefusal(McpRefusal.Reason.SERVER_TIMED_OUT));
        }
        OutboundCall<OutboundHttpResponse> call;
        try {
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(
                    profile.endpoint(), "POST", headers(), body, Duration.ofMillis(remaining),
                    profile.credentialBinding().orElse(null), null,
                    ExternalIoLimits.compressedHttp(Math.max(1, body.length),
                            profile.maxResponseBytes(), profile.maxResponseBytes(),
                            profile.maxResponseBytes(), 100, Duration.ofMillis(remaining),
                            expectsResult ? Set.of("application/json", "text/event-stream") : Set.of()),
                    ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy.SUCCESS_ONLY));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(refusalFor(failure));
        }
        return call.completion().handle((response, failure) -> {
            if (failure != null) {
                throw new CompletionException(refusalFor(failure));
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // The body is NOT read into the refusal. A server's error document is remote text and
                // may quote the arguments back; the fact the model can act on is that it failed.
                throw new CompletionException(new McpRefusal(McpRefusal.Reason.SERVER_UNREACHABLE));
            }
            String issued = header(response.headers(), "mcp-session-id");
            if (!issued.isEmpty()) {
                sessionId = issued;
            }
            if (!expectsResult) {
                return new ExchangeResult(new PayloadValue.MapValue(Map.of()),
                        response.effectiveMaximumOutputBytes());
            }
            int outputLimit = Math.toIntExact(Math.min(profile.maxResponseBytes(),
                    response.effectiveMaximumOutputBytes()));
            return new ExchangeResult(McpProtocol.readResult(response.body(),
                    header(response.headers(), "content-type"), outputLimit), outputLimit);
        });
    }

    private Map<String, List<String>> headers() {
        var headers = new java.util.LinkedHashMap<String, List<String>>();
        headers.put("content-type", List.of("application/json"));
        // Both encodings are declared because Streamable HTTP lets the server pick, and McpProtocol
        // reads either. Declaring only JSON would make a spec-compliant server's choice look like a
        // malformed response.
        headers.put("accept", List.of("application/json, text/event-stream"));
        headers.put("mcp-protocol-version", List.of(McpProtocol.PROTOCOL_VERSION));
        String current = sessionId;
        if (!current.isEmpty()) {
            headers.put("mcp-session-id", List.of(current));
        }
        return Map.copyOf(headers);
    }

    /** Case-insensitive, because header names are and this map preserves whatever the channel used. */
    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(name)
                    && !entry.getValue().isEmpty()) {
                String value = entry.getValue().get(0);
                return value == null ? "" : value;
            }
        }
        return "";
    }

    /**
     * What a model is told when a call did not produce a result.
     *
     * <p>Placed here rather than at the call site because the unwrapping and the mapping are the same
     * ones {@link #refusalFor(Throwable)} already does, and a second, slightly different copy of that
     * logic is how one path starts reporting "unreachable" for a timeout.</p>
     */
    static String describeForModel(Throwable failure) {
        return refusalFor(failure).forModel();
    }

    /**
     * Turns anything the channel or this code can raise into one of the named refusals.
     *
     * <p>The three the contract requirements name — did not answer, answered too slowly, answered too
     * much — are separated here and stay separated all the way out, because an operator reading
     * "the server failed" cannot tell which of their three settings to change.</p>
     */
    private static McpRefusal refusalFor(Throwable raw) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof McpRefusal known) {
            return known;
        }
        if (failure instanceof CancellationException) {
            return new McpRefusal(McpRefusal.Reason.SERVER_TIMED_OUT);
        }
        // The managed channel rejects a request intent it will not carry -- an ungranted header, a
        // method outside the grant -- with a plain IllegalArgumentException rather than its own
        // exception type. Reading that as "unreachable" would send an operator to look at a server
        // that is running perfectly.
        if (failure instanceof IllegalArgumentException) {
            return new McpRefusal(McpRefusal.Reason.SERVER_REQUEST_REFUSED);
        }
        if (failure instanceof NodePackageServiceException service) {
            // Exhaustive rather than defaulted, so a reason added to the channel later cannot fall
            // silently into "unreachable" -- the bucket that sends an operator to inspect a healthy
            // server.
            return new McpRefusal(switch (service.reason()) {
                case DEADLINE_EXCEEDED, CANCELLED -> McpRefusal.Reason.SERVER_TIMED_OUT;
                case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE ->
                        McpRefusal.Reason.SERVER_RESPONSE_TOO_LARGE;
                // The operator's grant said no. PROTOCOL_REFUSED is what an ungranted header arrives
                // as -- the likeliest of these in practice, because a grant written for llm-prompt
                // names only content-type and MCP needs three more.
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED, TLS_REFUSED ->
                        McpRefusal.Reason.SERVER_REQUEST_REFUSED;
                case CREDENTIAL_UNAVAILABLE, ADMISSION_REFUSED, SERVICE_UNAVAILABLE, TRANSPORT_FAILED,
                        EFFECT_OUTCOME_INDETERMINATE ->
                        McpRefusal.Reason.SERVER_UNREACHABLE;
                case BUDGET_EXHAUSTED -> McpRefusal.Reason.SERVER_REQUEST_REFUSED;
            });
        }
        return new McpRefusal(McpRefusal.Reason.SERVER_UNREACHABLE);
    }
}
