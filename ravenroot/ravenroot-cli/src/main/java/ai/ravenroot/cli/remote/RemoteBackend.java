package ai.ravenroot.cli.remote;

import ai.ravenroot.cli.CliBackend;

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

/**
 * The remote adapter: exactly the operations {@link CliBackend} declares, over
 * {@code java.net.http.HttpClient} against {@code RavenrootServer}'s own routes (API-05).
 *
 * <h2>TLS: accepted, never bypassed</h2>
 * <p>The server itself performs no TLS (a reverse proxy is expected to terminate it), but this client
 * still accepts an {@code https://} base URL for exactly that deployment shape, and does so with no
 * custom {@code SSLContext} or {@code TrustManager} anywhere in this class — the platform default trust
 * store is what validates the certificate. There is deliberately no {@code --insecure} flag or
 * equivalent constructor parameter: a client that can be told to skip certificate validation is a
 * client that will be told to, in exactly the deployment where it matters most.</p>
 *
 * <h2>Timeout</h2>
 * <p>One {@link Duration}, applied to both connection establishment ({@link HttpClient.Builder#connectTimeout})
 * and each individual request ({@link HttpRequest.Builder#timeout}) — a single caller-controlled bound
 * rather than two independent ones a caller would have to reason about together.</p>
 *
 * <h2>Compatibility with an older server</h2>
 * <p>{@link #inspect} reads the {@code "valid"} and {@code "violations"} response fields; see the
 * note there. This client and {@code RavenrootServer}
 * are versioned and released together, in the same distribution artifact, and there is today no
 * declared policy for running a newer CLI against an older server. Absent such a policy the safe
 * default is to fail loudly on a mismatch rather than degrade silently — a client that invented
 * {@code valid=true} for a server too old to have computed it would misreport an uninspected document
 * as sound, reintroducing the defect this same field prevents for {@code POST /v1/graphs/inspect}.
 * A default of {@code false} is not less invented than {@code true}: both are a
 * guess this client has no basis for, since {@link CliBackend.InspectView#valid()} is a primitive
 * {@code boolean} with no third value to spend on "unknown". A genuinely distinguishable absence was
 * possible, at a real cost: {@code RavenrootCli}'s {@code valid=%b} output line is the contract every
 * script parsing {@code ravenroot inspect} already depends on, and widening it to a tri-state is not
 * free for those callers either. Failing loudly avoids spending that cost for a policy nobody has
 * asked for yet.</p>
 *
 * <p>No cross-version compatibility policy is declared for this HTTP/CLI wire contract. If one is
 * introduced, this method's tolerance should be revisited deliberately.</p>
 *
 * <p>Four more reads follow the same rule and for the same reason — {@code executionPolicy}
 * on {@link #run}, and {@code bypassedNodes}, {@code handledFailure} and {@code handledFailureNodes}
 * on {@link #result}. A server too old to send them makes this client fail with a parse error rather
 * than silently report a clean run, which is the correct direction here more sharply than it was for
 * {@code valid}: every one of those four exists precisely to contradict a {@code COMPLETED},
 * {@code degraded=false} body, so inventing an absent value would mean inventing the reassurance.</p>
 *
 * <p><b>The operational cost is not the same as {@code inspect}'s, and the difference is worth stating
 * because the precedent above does not carry it.</b> A failed {@code inspect} creates nothing — the
 * caller retries and has lost only the answer. {@code executionPolicy} is read <em>after</em> the
 * server has answered {@code 202}, so on a mismatch <strong>the submission has already happened and
 * the traversal is already running</strong>; what the operator loses is the four identifier lines
 * {@link #run} would have printed, leaving them with an execution in flight that they cannot name.
 * It is recoverable — {@code GET /v1/events} carries the traversal — but it is a worse failure than a
 * refused read, and it is the reason this paragraph exists rather than a pointer to the one above.
 * It stays the right direction anyway: the alternative is printing identifiers for a run whose policy
 * this client could not establish, which would provide false reassurance.</p>
 */
public final class RemoteBackend implements CliBackend {
    private final HttpClient client;
    private final URI baseUri;
    private final String token;
    private final Duration timeout;

    public RemoteBackend(URI baseUri, String token, Duration timeout) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.token = Objects.requireNonNull(token, "token");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public StatusView status() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(get("/v1/status")));
        return new StatusView(MinimalJson.asString(body.get("state")),
                MinimalJson.asString(body.get("executionEngine")),
                MinimalJson.asArray(body.get("capabilities")).stream().map(MinimalJson::asString).toList());
    }

    @Override
    public RuntimeView runtime() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(get("/v1/runtime")));
        var nodes = new LinkedHashMap<String, Integer>();
        MinimalJson.asObject(body.get("activeNodeInstances"))
                .forEach((key, value) -> nodes.put(key, (int) MinimalJson.asLong(value)));
        return new RuntimeView((int) MinimalJson.asLong(body.get("activeExecutions")), nodes);
    }

    @Override
    public List<NodeTypeView> nodeTypes() throws IOException {
        return MinimalJson.asArray(MinimalJson.parse(get("/v1/node-types"))).stream().map(entry -> {
            var type = MinimalJson.asObject(entry);
            boolean agentic = Boolean.TRUE.equals(type.get("agentic"));
            return new NodeTypeView(MinimalJson.asString(type.get("behavior")),
                    MinimalJson.asString(type.get("category")),
                    agentic ? "agentic" : MinimalJson.asString(type.get("visualType")),
                    MinimalJson.asString(type.get("description")));
        }).toList();
    }

    @Override
    public InspectView inspect(byte[] graphMl) throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(
                post("/v1/graphs/inspect", graphMl, "application/xml")));
        // "valid"/"violations" are the same two keys RavenrootServer#inspectGraph places in the
        // response body, closing the gap between the server's verdict and this client view.
        List<String> violations = MinimalJson.asArray(body.get("violations")).stream()
                .map(MinimalJson::asString).toList();
        return new InspectView((int) MinimalJson.asLong(body.get("nodes")), (int) MinimalJson.asLong(body.get("edges")),
                (int) MinimalJson.asLong(body.get("startNodes")), (int) MinimalJson.asLong(body.get("endNodes")),
                MinimalJson.asBoolean(body.get("valid")), violations);
    }

    /**
     * {@code mode=run} is sent explicitly so this client never inherits the server's editor-oriented
     * default.
     *
     * <p><b>What was wrong.</b> This method used to build the query from the payload alone, so a
     * submission carried no {@code mode} at all and {@code RavenrootServer#startExecution} applied its
     * documented default — {@code mode=test}, {@link ai.ravenroot.api.application.ExecutionPolicy}
     * {@code .TEST_PASSTHROUGH}, in which no behavior implementation is constructed. Every field of
     * the result then matched a correct run except the payload, and on any graph containing a decision
     * node it was worse than that: an unconstructed {@code cel-decision} evaluates no expression, so
     * no outcome edge is taken and the traversal simply stops — three nodes of eight on the shipped
     * {@code ravenroot-programmable.graphml}, no payload, and still {@code COMPLETED} with
     * {@code degraded=false}. {@code ravenroot run} is a verb, and an operator typing it against
     * {@code --server} was told the run succeeded when nothing had run.</p>
     *
     * <p><b>Why the client changed and not the server.</b> The server's default is deliberate and
     * documented (ADR 0023's Play boundary): a half-built graph must stay submittable from the editor.
     * Changing it would break the caller that wants it. The defect was that one client depended on the
     * default without saying so, so the client is where the mode is now stated.</p>
     *
     * <p><b>That is a fact about the call, not about the API, and the difference matters to whoever
     * reads this next.</b> {@code AuthorizedRavenrootApplication.startGraphMl(RequestContext,
     * InputStream, Object)} — the overload {@link ai.ravenroot.cli.EmbeddedBackend#run} calls — takes
     * no policy parameter and fixes {@code ExecutionPolicy.STANDARD} internally. A policy <em>is</em>
     * expressible from the embedded path: the five-argument {@code startGraphMl(RequestContext,
     * InputStream, PayloadEnvelope, PayloadLimits, ExecutionPolicy)} overload is public, and
     * {@code PayloadEnvelope.legacyText(String)} is documented as stating exactly what the
     * {@code Object} call is equivalent to. So the obstacle is not reachability.</p>
     *
     * <p><b>The real obstacle is that moving there would not preserve the payload contract.</b> The
     * envelope overload enforces {@code PayloadLimits} and performs a <em>tree-walk</em> reserved-key
     * check. Previously, the {@code Object} overload performed a top-level scan and enforced no limits
     * at all (see that class's own Javadoc on the pair); it now closes that gap, so the two overloads
     * now agree on the budget itself.</p>
     *
     * <p><b>The two transports are not indistinguishable below 32 KiB for the strings this CLI
     * passes.</b> {@code docs/architecture/payload-and-error-contract.md} §5.1 documents the 4 KiB
     * boundary. This method places the payload in the URL query string, and
     * {@code RateLimiter#checkRequestShape} caps the raw, percent-encoded query at
     * {@code RAVENROOT_RATELIMIT_MAX_QUERY_BYTES} (4 KiB by default) before authentication and before
     * {@code PayloadLimits} is ever consulted -- a guard that bounds pre-auth request cost for every
     * route, not payload content specifically, and that is tighter than the 32 KiB payload budget by
     * a factor of eight.</p>
     *
     * <p><b>"4 KiB" is the encoded query, not the operator's text, and percent-encoding is not
     * uniform.</b> Direct measurements are more informative than a value computed from the limit,
     * because {@code URLEncoder.encode} above expands every byte outside a narrow unreserved
     * set to three: plain ASCII text passes through 4 000 characters and is refused at 4 100;
     * structured (JSON) content, whose braces, quotes and commas all encode, has no single
     * boundary: compact JSON passes at 1 560 and is refused at 1 570, while sparser JSON --
     * longer keys and values, so less punctuation per character -- survives to 2 400 and is
     * refused at 2 600. The spread is the point: any single number is wrong for half the JSON
     * in existence, so the worst case is the only figure an operator can act on. Accented text
     * encodes worse still and passes at 679, refused at 680 -- so a
     * payload in Italian, or any language that is not plain ASCII, hits the wall around 680
     * characters, not 4 KiB. An operator reading only "4 KiB" would overestimate what this transport
     * actually carries for anything but plain ASCII.</p>
     *
     * <p>Past a few hundred KiB the failure stops being a clean rejection at all: a large enough query
     * string breaks the HTTP request line itself, observed as the underlying {@code HttpClient}
     * reporting "HTTP/1.1 header parser received no bytes" rather than any {@code PayloadException}
     * code. The boundary is between 350 000 and 400 000 characters and comes from a
     * request-line/protocol ceiling on the server side; raising that ceiling alone is not a
     * payload-contract change and is not addressed here.</p>
     *
     * <p>The embedded side previously had no ceiling and now enforces the payload budget. This
     * method's ceiling remains where it was, because raising it means moving the payload off the
     * query string and onto a request body — the same kind of wire-representation change as a Test
     * verb. Such a move requires its own design and evidence rather than being a side effect of a
     * limits fix. See the documented contract for the full account and the measured numbers above.</p>
     *
     * <p><b>Why {@code run} rather than a {@code --mode} flag on the CLI.</b> {@link CliBackend}'s
     * contract is that a command means the same thing whichever transport serves it — {@code --server}
     * selects where the work happens, never what the work is. A flag is therefore only worth adding if
     * both backends can honour it, and today only one of them does: the payload budget is now the one
     * exception settled, the execution policy is not. So a Test verb for the CLI is a design that
     * belongs on <em>both</em> backends at once, made deliberately and on its own evidence. It is not
     * this fix, and nothing here forecloses it. The web editor already offers the choice as two
     * distinct user actions ({@code RuntimeClient.start} behind the button labelled
     * {@code ▶ Test}, {@code RuntimeClient.run} behind {@code ▶ Run}), and it can because both of
     * its buttons speak to a server.</p>
     *
     * <p>Named after the buttons rather than after the command ids, because that is what a reader
     * checks this against: the editor's own command is still {@code run.play}, but nothing on screen
     * says "Play", and {@code docs/getting-started/visual-tutorial.md} sends readers here under the
     * name they can actually see. ADR 0023's own vocabulary for the <em>policy</em> is untouched --
     * "the Play boundary" above is that concept, not a control anyone presses.</p>
     */
    @Override
    public RunView run(byte[] graphMl, String payload) throws IOException {
        String path = "/v1/executions?mode=run" + (payload == null || payload.isEmpty() ? ""
                : "&payload=" + java.net.URLEncoder.encode(payload, StandardCharsets.UTF_8));
        var body = MinimalJson.asObject(MinimalJson.parse(post(path, graphMl, "application/xml")));
        // The server states which policy it applied, and until now this client threw
        // that statement away. Reading it is what keeps the line above honest: if a future server
        // changed its default, or a proxy dropped the query, the operator sees the policy that really
        // ran instead of inferring one from a request nobody can see.
        return new RunView(MinimalJson.asString(body.get("processInstanceId")),
                MinimalJson.asString(body.get("traversalId")), MinimalJson.asString(body.get("executionId")),
                MinimalJson.asString(body.get("graphVersion")),
                MinimalJson.asString(body.get("executionPolicy")));
    }

    @Override
    public ResultView result(String executionId) throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(
                get("/v1/executions/" + java.net.URLEncoder.encode(executionId, StandardCharsets.UTF_8))));
        Object payloadValue = body.get("payload");
        String payload = payloadValue == null ? null : MinimalJson.write(payloadValue);
        return new ResultView(MinimalJson.asString(body.get("executionId")), MinimalJson.asString(body.get("status")),
                Boolean.TRUE.equals(body.get("degraded")),
                MinimalJson.asArray(body.get("visitedNodes")).stream().map(MinimalJson::asString).sorted().toList(),
                MinimalJson.asArray(body.get("defaultedNodes")).stream().map(MinimalJson::asString).sorted().toList(),
                // These three were in the response body all along and this method
                // dropped them on the floor: bypassedNodes names the nodes the server traversed without
                // executing, handledFailureNodes the ones that failed inside a run that completed
                // anyway. Each is, on its own, the only thing in a COMPLETED, degraded=false body that
                // distinguishes it from a clean run, so a client that reads neither cannot show what it
                // was told. That reasoning is RavenrootServer#executionOutcomeJson's own, and it is
                // written there for handledFailure/handledFailureNodes specifically -- that
                // Javadoc does not mention bypassedNodes at all, so the argument is carried over here
                // rather than cited, because it holds for bypassing for the same reason.
                MinimalJson.asArray(body.get("bypassedNodes")).stream().map(MinimalJson::asString).sorted().toList(),
                Boolean.TRUE.equals(body.get("handledFailure")),
                MinimalJson.asArray(body.get("handledFailureNodes")).stream().map(MinimalJson::asString)
                        .sorted().toList(),
                // UntakenEdges is in the response body (RavenrootServer#executionOutcomeJson)
                // for the same reason bypassedNodes and handledFailureNodes are, so this projection
                // carries it as well instead of opening the same gap.
                MinimalJson.asArray(body.get("untakenEdges")).stream().map(MinimalJson::asString)
                        .sorted().toList(),
                payload);
    }

    /** Reads {@code GET /v1/executions/live}: the server resolves the tenant from the
     * bearer token already carried by every request this class sends, so nothing here needs to
     * express a tenant explicitly -- see {@link CliBackend#live}'s own Javadoc. */
    @Override
    public List<LiveView> live() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(get("/v1/executions/live")));
        return MinimalJson.asArray(body.get("executions")).stream().map(entry -> {
            var execution = MinimalJson.asObject(entry);
            return new LiveView(MinimalJson.asString(execution.get("processInstanceId")),
                    MinimalJson.asString(execution.get("traversalId")),
                    MinimalJson.asString(execution.get("executionId")),
                    MinimalJson.asString(execution.get("graphVersion")),
                    MinimalJson.asString(execution.get("startedAt")));
        }).toList();
    }

    @Override
    public CancelView cancel(String traversalId) throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(
                post("/v1/executions/" + traversalId + "/cancel", new byte[0], "application/json")));
        return new CancelView(MinimalJson.asString(body.get("outcome")),
                MinimalJson.asString(body.get("traversalId")), MinimalJson.asString(body.get("note")));
    }

    @Override
    public DrainView drain() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(post("/v1/drain", new byte[0], "application/json")));
        return new DrainView(MinimalJson.asString(body.get("outcome")));
    }

    /** Reads {@code GET /v1/deployments}: the caller's own tenant's registrations, in the order
     * the server lists them. */
    @Override
    public List<DeploymentView> deployments() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(get("/v1/deployments")));
        return MinimalJson.asArray(body.get("deployments")).stream()
                .map(entry -> deploymentFrom(MinimalJson.asObject(entry))).toList();
    }

    /** {@code POST /v1/deployments?id=...} with the GraphML body -- the same request shape
     * {@link #run} and {@link #result}'s sibling {@code startSourceSession} route already use for a
     * graph document, so this method is the deployment lifecycle's one wire-shape decision, not a new
     * one. */
    @Override
    public DeploymentView registerDeployment(String deploymentId, byte[] graphMl) throws IOException {
        String path = "/v1/deployments?id=" + java.net.URLEncoder.encode(deploymentId, StandardCharsets.UTF_8);
        return deploymentFrom(MinimalJson.asObject(MinimalJson.parse(post(path, graphMl, "application/xml"))));
    }

    @Override
    public DeploymentView deployment(String deploymentId) throws IOException {
        return deploymentFrom(MinimalJson.asObject(MinimalJson.parse(
                get("/v1/deployments/" + java.net.URLEncoder.encode(deploymentId, StandardCharsets.UTF_8)))));
    }

    @Override
    public DeploymentView startDeployment(String deploymentId) throws IOException {
        return deploymentCommand(deploymentId, "start");
    }

    @Override
    public DeploymentView stopDeployment(String deploymentId) throws IOException {
        return deploymentCommand(deploymentId, "stop");
    }

    @Override
    public DeploymentView restartDeployment(String deploymentId) throws IOException {
        return deploymentCommand(deploymentId, "restart");
    }

    /** The three lifecycle commands share one path shape and one empty {@code POST} body -- exactly
     * {@link #cancel}'s own request shape, reused rather than restated. */
    private DeploymentView deploymentCommand(String deploymentId, String command) throws IOException {
        String path = "/v1/deployments/" + java.net.URLEncoder.encode(deploymentId, StandardCharsets.UTF_8)
                + "/" + command;
        return deploymentFrom(MinimalJson.asObject(MinimalJson.parse(
                post(path, new byte[0], "application/json"))));
    }

    /** {@code DELETE /v1/deployments/{id}}: stops the deployment and then removes its
     * registration -- distinct from {@link #stopDeployment}, which leaves it registered. */
    @Override
    public DeploymentView undeployDeployment(String deploymentId) throws IOException {
        return deploymentFrom(MinimalJson.asObject(MinimalJson.parse(
                delete("/v1/deployments/" + java.net.URLEncoder.encode(deploymentId, StandardCharsets.UTF_8)))));
    }

    /** {@code diagnostic} is the one field on this response that can be {@code null}
     * ({@code LocalDeploymentStatus} only carries one for a degraded or failed deployment), so it is
     * read directly from the parsed map rather than through {@link MinimalJson#asString}, which
     * rejects {@code null}. */
    private static DeploymentView deploymentFrom(Map<String, Object> body) {
        Object diagnostic = body.get("diagnostic");
        return new DeploymentView(MinimalJson.asString(body.get("deploymentId")),
                MinimalJson.asString(body.get("state")), (int) MinimalJson.asLong(body.get("sourceCount")),
                MinimalJson.asString(body.get("scope")),
                diagnostic == null ? null : MinimalJson.asString(diagnostic));
    }

    /**
     * Reads {@code GET /v1/credentials}: the caller's own stored credentials, never a
     * value in any form -- matching {@code ai.ravenroot.server.credential.UserCredentialWire#writeCredential}'s
     * own guarantee that the response has no branch that could emit one.
     */
    @Override
    public List<CredentialView> credentials() throws IOException {
        var body = MinimalJson.asObject(MinimalJson.parse(get("/v1/credentials")));
        return MinimalJson.asArray(body.get("credentials")).stream()
                .map(entry -> credentialFrom(MinimalJson.asObject(entry))).toList();
    }

    /**
     * Runs {@code POST /v1/credentials}. The request body carries exactly the four fields
     * {@code ai.ravenroot.server.credential.UserCredentialWire#KNOWN_FIELDS} accepts -- {@code label},
     * {@code scheme}, {@code username}, {@code value} -- and nothing that route's closed field set would
     * refuse: in particular, no {@code reference}/{@code id}/{@code credentialRef} member, since the
     * server mints the reference and a caller proposing one is refused (the server-minted-reference contract).
     *
     * <p>{@code value} reaches this method as an ordinary parameter and this request body as an
     * ordinary JSON string field -- it travels once, in the POST body, over whatever transport secures
     * this connection (see this class's own TLS note above), never as a URL query parameter and never
     * on this process's own command line. See {@code ai.ravenroot.cli.CredentialAddArgs} for where the
     * command line is kept clear of it before this method is ever called.</p>
     */
    @Override
    public CredentialView addCredential(String label, String scheme, String username, String value)
            throws IOException {
        var request = new LinkedHashMap<String, Object>();
        request.put("label", label);
        request.put("scheme", scheme);
        request.put("username", username);
        request.put("value", value);
        var body = MinimalJson.asObject(MinimalJson.parse(post("/v1/credentials",
                MinimalJson.write(request).getBytes(StandardCharsets.UTF_8), "application/json")));
        return credentialFrom(body);
    }

    private static CredentialView credentialFrom(Map<String, Object> body) {
        return new CredentialView(MinimalJson.asString(body.get("reference")),
                MinimalJson.asString(body.get("label")), MinimalJson.asString(body.get("scheme")),
                MinimalJson.asString(body.get("username")), MinimalJson.asString(body.get("createdAt")));
    }

    private String get(String path) throws IOException {
        return send(baseRequest(path).GET().build());
    }

    private String post(String path, byte[] body, String contentType) throws IOException {
        return send(baseRequest(path).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    /** The first route on this client that needs {@code DELETE} -- undeploy is the one
     * deployment command that removes the registration rather than merely changing its state. */
    private String delete(String path) throws IOException {
        return send(baseRequest(path).DELETE().build());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                .header("Authorization", "Bearer " + token);
    }

    private String send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + baseUri, interrupted);
        }
        if (response.statusCode() >= 400) {
            throw renderError(response);
        }
        return response.body();
    }

    /**
     * Renders {@code ErrorEnvelope}'s wire shape ({@code {"code":..., "message":...}}) as the
     * exception message a caller's own error path (already routed through
     * {@code RavenrootCli#sanitizeForConsole}) will print. Never includes the raw response body
     * verbatim -- only the two fields the server's own vocabulary defines -- so a caller cannot be
     * shown more than the server chose to say.
     */
    private static IOException renderError(HttpResponse<String> response) {
        try {
            Map<String, Object> envelope = MinimalJson.asObject(MinimalJson.parse(response.body()));
            String code = String.valueOf(envelope.getOrDefault("code", "UNKNOWN"));
            String message = String.valueOf(envelope.getOrDefault("message", "request failed"));
            return new IOException(response.statusCode() + " " + code + ": " + message);
        } catch (RuntimeException malformed) {
            return new IOException("HTTP " + response.statusCode());
        }
    }
}
