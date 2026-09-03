package ai.ravenroot.server.spec;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.server.ratelimit.ActiveExecutionRegistry;

import java.util.List;
import java.util.Set;

import static ai.ravenroot.server.spec.AssistantPosture.CONFIRM;
import static ai.ravenroot.server.spec.AssistantPosture.NEVER;
import static ai.ravenroot.server.spec.AssistantPosture.READ;

/**
 * The complete, ordered list of {@code RavenrootServer}'s HTTP endpoints (API-05). See
 * {@link RouteDescriptor} for what drives from this table and what is declared by hand.
 *
 * <h2>Assistant postures</h2>
 * <p>Every entry declares an {@link AssistantPosture}. The default assignment is mechanical rather
 * than a judgement call per route, and that is deliberate: the initial {@code CONFIRM} set is empty,
 * while permanent {@code NEVER} exclusions remain explicit. The rule is <b>{@code READ}
 * for authenticated {@code GET}-only reads; {@code NEVER} for everything else</b> — which
 * {@link RouteDescriptor}'s compact constructor then enforces rather than trusting.</p>
 *
 * <p><b>One route departs from it: {@code POST /v1/executions} is {@code CONFIRM}.</b> It grants
 * nothing today: no effector exists, no tool mirrors it, and {@code CONFIRM} is not a posture any tool
 * may be built on — {@code AssistantInternalContext} accepts {@code READ} only. The model proposes
 * and the user confirms.</p>
 *
 * <h3>Why it is {@code CONFIRM} under the side-effect-free Play policy</h3>
 * <p>{@code RavenrootServer} passes
 * {@link ai.ravenroot.api.application.ExecutionPolicy#TEST_PASSTHROUGH} as a server-chosen constant —
 * not a caller parameter — so the submitted graph is traversed
 * structurally and behavior implementations are never constructed or invoked. The route cannot resolve
 * a production binding or perform an adapter or tool side effect.</p>
 *
 * <p>{@code CONFIRM} holds because side-effect-free is not the same as free: the route mints an
 * execution id, charges the tenant's and
 * the deployment's active-execution budgets ({@code ActiveExecutionRegistry}), emits an event stream,
 * and appears in {@code /v1/runtime} as live work. An assistant that could start these unbidden could
 * exhaust a tenant's execution budget without ever touching an adapter. Proposing and having the
 * author press is the right shape for work that is observable, bounded and charged, whether or not it
 * reaches the outside world.</p>
 *
 * <p>{@code READ} is not the alternative and could not be chosen even if someone wanted it:
 * {@link RouteDescriptor}'s compact constructor refuses {@code READ} for any route not declared
 * {@code sideEffectFree}, and refuses that declaration outright for a {@code 202} answer. This route
 * is caught twice over and by the property rather than by its verb. The read-shaped
 * {@code POST /v1/graphs/inspect} is {@code READ} because inspection
 * genuinely changes nothing, while starting a traversal mints an id and charges a budget.</p>
 *
 * <p>{@code READ} here means "this data may reach the assistant, through
 * {@code AuthorizedRavenrootApplication} under the author's own {@code SecurityContext}" — never
 * "the assistant may call this HTTP route". Which of the {@code READ} routes actually become
 * model-visible tools is a separate decision and is not made here.</p>
 */
public final class RouteTable {
    private RouteTable() {
    }

    private static final List<String> STANDARD_ERRORS = List.of(
            ErrorCode.AUTHENTICATION_REQUIRED.code(), ErrorCode.ACCESS_DENIED.code(),
            ErrorCode.METHOD_NOT_ALLOWED.code(), WireErrorCodes.HEADER_VALUE_TOO_LARGE,
            WireErrorCodes.TOO_MANY_HEADERS, WireErrorCodes.HEADERS_TOO_LARGE,
            WireErrorCodes.QUERY_TOO_LARGE, WireErrorCodes.TOO_MANY_QUERY_PARAMETERS,
            WireErrorCodes.LIMITER_CAPACITY_EXHAUSTED);

    public static final List<RouteDescriptor> ALL = List.of(
            new RouteDescriptor(Set.of("GET"), "/health", "Liveness probe.", false, false, 200, List.of(),
                    NEVER, true),
            new RouteDescriptor(Set.of("GET"), "/ready", "Readiness probe (PLAT-02).", false, false, 200,
                    List.of(), NEVER, true),
            new RouteDescriptor(Set.of("GET"), "/v1/status", "Application status and declared capabilities.",
                    true, true, 200, STANDARD_ERRORS, READ, true),
            new RouteDescriptor(Set.of("GET"), "/v1/runtime", "Runtime snapshot: active executions and node "
                    + "instance counts.", true, true, 200, STANDARD_ERRORS, READ, true),
            new RouteDescriptor(Set.of("GET"), "/v1/node-types", "The trusted node-type catalog.", true, true,
                    200, STANDARD_ERRORS, READ, true),
            // The program-language counterpart of /v1/node-types: a static, tenant-independent
            // capability catalog an editor reads to populate a selector, rather than a route bolted
            // onto /v1/runtime (a live execution snapshot) or /v1/status (a flat set of capability
            // tokens with no room for a starter source per entry). Mirrors /v1/node-types's own shape
            // for the same reason -- both answer "what can this deployment's engine do", read-only,
            // authenticated, no tenant scoping.
            new RouteDescriptor(Set.of("GET"), "/v1/program-languages",
                    "The program languages this deployment's runtime accepts, each with a starter "
                            + "example source an editor can offer before the author writes anything.",
                    true, true, 200, STANDARD_ERRORS, READ, true),
            new RouteDescriptor(Set.of("GET", "POST"), "/v1/program-artifacts",
                    "GET lists program artifacts; POST creates one from source.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.PROGRAM_SOURCE_TOO_LARGE.code(),
                            ErrorCode.INVALID_REQUEST.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/build",
                    "Starts or rejoins one durable graph-level program build and returns its observable build id "
                            + "and initial per-node phase snapshots.",
                    true, false, 202,
                    concat(STANDARD_ERRORS, ErrorCode.PROGRAM_SOURCE_TOO_LARGE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code()), NEVER, false),
            new RouteDescriptor(Set.of("GET"), "/v1/program-artifacts/builds/{id}",
                    "Returns one tenant-scoped durable program-build snapshot and resumes incomplete work.",
                    true, false, Set.of(200, 202),
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/approve-batch",
                    "Independently approves and activates a graph-level batch paused by dual control.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code()),
                    NEVER, false),
            // 200 for BOTH outcomes, with a body that names which one. A source the runtime
            // cannot compile is a result of a well-formed request, not a malformed request, and the
            // route used to answer 400 "the request was rejected as invalid" for it while dropping
            // the compiler's own text. The matching shape is /v1/executions/{id}/cancel, which spends a
            // body rather than status codes on outcomes; /v1/model-providers/{id}/verify follows the
            // same convention. The 4xx
            // codes below keep their previous meanings exactly: 409 for a conflicting artifact
            // state, 400 for a request this route genuinely cannot process.
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/{id}/validate",
                    "Validates a program artifact's source. 200 for both outcomes, with a body "
                            + "carrying 'outcome': 'validated' plus the artifact in its new state, or "
                            + "'rejected' plus the runtime's own diagnostic and the line and column it "
                            + "placed the refusal at (0 for either the runtime did not supply). A "
                            + "source that does not compile is a result, not a malformed request. The "
                            + "two 501s are the other half of #538: a deployment with no runtime "
                            + "adapter, or with no usable sandbox, fails before the source is read at "
                            + "all, and used to answer 400 as if the request had been malformed.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_ARTIFACT_OPERATION.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code(),
                            ErrorCode.PROGRAM_RUNTIME_NOT_INSTALLED.code(),
                            ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE.code(),
                            ErrorCode.PROGRAM_EXECUTION_TIMEOUT.code()), NEVER, false),
            // The same two 501s: test reaches the sandbox the same way validate does.
            // Both declare 504 for the same reason: the sandbox deadline is reached identically by
            // either, rather than producing 400 or 409 based only on which adapter wait expires first.
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/{id}/test",
                    "Runs a validated program artifact against a caller-supplied payload.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_ARTIFACT_OPERATION.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code(),
                            ErrorCode.PROGRAM_RUNTIME_NOT_INSTALLED.code(),
                            ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE.code(),
                            ErrorCode.PROGRAM_EXECUTION_TIMEOUT.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/{id}/approve",
                    "Approves a tested program artifact (dual control).", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_ARTIFACT_OPERATION.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/{id}/activate",
                    "Activates an approved program artifact.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_ARTIFACT_OPERATION.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/program-artifacts/{id}/retire",
                    "Retires an active program artifact.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_ARTIFACT_OPERATION.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.CONFLICT.code()), NEVER, false),
            // READ despite being a POST. It is a POST only because a GraphML document has to
            // travel in a request body; authenticated, it parses the submitted document and reports its
            // node and edge counts, and changes nothing. The previous rule refused it for the shape of
            // its request rather than for anything it does, which cost real capability to protect
            // against no risk. Now that READ's precondition is the declared property rather than the
            // verb, this route qualifies on the merits.
            new RouteDescriptor(Set.of("POST"), "/v1/graphs/inspect",
                    "Parses and summarizes a GraphML document without executing it.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE.code(),
                            ErrorCode.GRAPHML_RESOURCE_LIMIT.code(), ErrorCode.GRAPHML_UNSAFE_XML.code(),
                            ErrorCode.GRAPHML_MALFORMED_XML.code(), ErrorCode.GRAPHML_COMPRESSED_ARCHIVE.code(),
                            ErrorCode.GRAPHML_INVALID_GRAPH.code(), ErrorCode.INVALID_REQUEST.code()), READ, true),
            // These contexts are conditional on a complete, explicitly enabled
            // EmbedBrowserConfiguration, so they cannot participate in the unconditional
            // registersContext=true equality. EmbedBrowserHttpIntegrationTest verifies the live
            // contexts and their exact-origin protocol. NEVER excludes both the S2S grant exchange
            // and the child viewer's proof-bound surface from the authoring assistant.
            new RouteDescriptor(Set.of("POST"), "/v1/embed/sessions",
                    "Creates a one-use embedded-viewer launch ticket from an opaque, pre-registered "
                            + "registration id. Requires a WORKLOAD bearer and accepts no graph coordinates.",
                    true, false, 201,
                    List.of(WireErrorCodes.EMBED_REQUEST_INVALID, WireErrorCodes.EMBED_METHOD_NOT_ALLOWED,
                            WireErrorCodes.EMBED_SESSION_UNAVAILABLE,
                            WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE,
                            WireErrorCodes.EMBED_REQUEST_TOO_LARGE), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/embed/acknowledgements",
                    "Records the integrating BFF's authenticated, one-use acknowledgement of the "
                            + "exact viewer channel before child exchange is permitted.",
                    true, false, 200,
                    List.of(WireErrorCodes.EMBED_REQUEST_INVALID, WireErrorCodes.EMBED_METHOD_NOT_ALLOWED,
                            WireErrorCodes.EMBED_SESSION_UNAVAILABLE,
                            WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE,
                            WireErrorCodes.EMBED_REQUEST_TOO_LARGE), NEVER, false),
            new RouteDescriptor(Set.of("GET"), "/v1/embed/launch",
                    "Consumes a one-use launch ticket during an iframe navigation and returns only the "
                            + "distinct-origin viewer bootstrap; never returns graph data or a bearer.",
                    false, false, 200,
                    List.of(WireErrorCodes.EMBED_REQUEST_INVALID, WireErrorCodes.EMBED_METHOD_NOT_ALLOWED,
                            WireErrorCodes.EMBED_SESSION_UNAVAILABLE,
                            WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/embed/exchange",
                    "Exchanges the bootstrap challenge and a P-256 proof for a short-lived, "
                            + "child-memory-only projection bearer.",
                    false, false, 200,
                    List.of(WireErrorCodes.EMBED_REQUEST_INVALID, WireErrorCodes.EMBED_METHOD_NOT_ALLOWED,
                            WireErrorCodes.EMBED_SESSION_UNAVAILABLE,
                            WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE,
                            WireErrorCodes.EMBED_REQUEST_TOO_LARGE), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/embed/projection",
                    "Reads the pre-authorized #503 embedded graph projection after exact-origin, current-grant "
                            + "and P-256 proof-of-possession verification.",
                    true, false, 200,
                    List.of(WireErrorCodes.EMBED_REQUEST_INVALID, WireErrorCodes.EMBED_METHOD_NOT_ALLOWED,
                            WireErrorCodes.EMBED_SESSION_UNAVAILABLE,
                            WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE,
                            WireErrorCodes.EMBED_DATA_TOO_LARGE,
                            WireErrorCodes.EMBED_REQUEST_TOO_LARGE), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/executions",
                    "Starts a transient graph traversal. Default mode=test selects TEST_PASSTHROUGH and does not "
                            + "invoke behavior adapters; mode=run selects STANDARD and executes real node effects. "
                            + "Under mode=test a pacing behavior is skipped like any other, so a 'delay' inside a "
                            + "cycle does not wait and the cycle runs at full speed (#488); nothing bounds a "
                            + "repetition, and a running traversal is stopped with POST "
                            + "/v1/executions/{id}/cancel or held with .../pause. "
                            + "Neither mode creates a durable deployment. 202: accepted, not completed.", true, true, 202,
                    concat(STANDARD_ERRORS, ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE.code(),
                            ErrorCode.GRAPHML_RESOURCE_LIMIT.code(), ErrorCode.GRAPHML_UNSAFE_XML.code(),
                            ErrorCode.GRAPHML_MALFORMED_XML.code(), ErrorCode.GRAPHML_COMPRESSED_ARCHIVE.code(),
                            ErrorCode.GRAPHML_INVALID_GRAPH.code(), ErrorCode.SUBMISSION_DOCUMENT_TOO_LARGE.code(),
                            ErrorCode.UNSUPPORTED_MEDIA_TYPE.code(), ErrorCode.INVALID_REQUEST.code(),
                            ErrorCode.CONFLICT.code(), ErrorCode.EXECUTION_POLICY_UNSUPPORTED.code(),
                            ActiveExecutionRegistry.TENANT_LIMIT_CODE,
                            ActiveExecutionRegistry.GLOBAL_LIMIT_CODE), CONFIRM, false),
            new RouteDescriptor(Set.of("POST"), "/v1/source-sessions",
                    "Starts or idempotently rejoins one authenticated tenant's process-local inbound-source "
                            + "session from GraphML. Requires ?id=... and at least one trusted effective SOURCE. "
                            + "Starts listeners only: no traversal and no initial payload are created. The response "
                            + "scope is LOCAL_PROCESS and makes no durability, failover or cluster ownership claim.",
                    true, true, 202,
                    concat(STANDARD_ERRORS, ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE.code(),
                            ErrorCode.GRAPHML_RESOURCE_LIMIT.code(), ErrorCode.GRAPHML_UNSAFE_XML.code(),
                            ErrorCode.GRAPHML_MALFORMED_XML.code(), ErrorCode.GRAPHML_COMPRESSED_ARCHIVE.code(),
                            ErrorCode.GRAPHML_INVALID_GRAPH.code(), ErrorCode.INVALID_REQUEST.code(),
                            ErrorCode.CONFLICT.code(), ErrorCode.REQUEST_LIMIT_EXCEEDED.code(),
                            ErrorCode.EXECUTION_POLICY_UNSUPPORTED.code()), CONFIRM, false),
            new RouteDescriptor(Set.of("GET", "DELETE"), "/v1/source-sessions/{id}",
                    "GET observes and DELETE stops exactly one authenticated tenant's process-local source "
                            + "session. Unknown and sibling-tenant ids are nondisclosing 404 responses. DELETE "
                            + "releases only that deployment domain and never drains the shared ActorSystem.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code()), NEVER, false),
            // The tenant-scoped, process-local deployment lifecycle. One registered context,
            // four hand-declared sub-routes under it -- the same shape /v1/executions and
            // /v1/source-sessions already use, and for the same JDK reason (longest-prefix context
            // matching means /v1/deployments already receives /v1/deployments/{id}/start; a second
            // createContext would shadow rather than add).
            //
            // Why /v1/executions/{id}/cancel and /v1/drain are untouched by this addition: cancel
            // ends one traversal and drain ends the whole server's intake. Neither is a deployment
            // Stop. Relabelling either as one would still leave a source-less graph with no lifecycle.
            new RouteDescriptor(Set.of("GET", "POST"), "/v1/deployments",
                    "GET lists the authenticated tenant's process-local deployments; POST registers an "
                            + "immutable graph version under ?id=... from GraphML. Registration starts "
                            + "nothing -- the response is REGISTERED and POST .../start is what serves. "
                            + "Unlike /v1/source-sessions a graph with no effective SOURCE is accepted. "
                            + "Re-registering the same id with the same graph returns the current "
                            + "status unchanged; with a different graph it is a 409. An explicit ?scope= other than "
                            + "LOCAL_PROCESS is refused rather than degraded. The response scope is always "
                            + "LOCAL_PROCESS: no durability, lease, fencing, failover or cluster claim.",
                    true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE.code(),
                            ErrorCode.GRAPHML_RESOURCE_LIMIT.code(), ErrorCode.GRAPHML_UNSAFE_XML.code(),
                            ErrorCode.GRAPHML_MALFORMED_XML.code(), ErrorCode.GRAPHML_COMPRESSED_ARCHIVE.code(),
                            ErrorCode.GRAPHML_INVALID_GRAPH.code(), ErrorCode.INVALID_REQUEST.code(),
                            ErrorCode.CONFLICT.code(),
                            ErrorCode.EXECUTION_POLICY_UNSUPPORTED.code()), NEVER, false),
            new RouteDescriptor(Set.of("GET", "DELETE"), "/v1/deployments/{id}",
                    "GET inspects and DELETE undeploys exactly one authenticated tenant's process-local "
                            + "deployment. Undeploy stops first and only then removes the registration, so "
                            + "it is strictly distinct from POST .../stop, which leaves the deployment "
                            + "registered and re-startable. Unknown ids, sibling-tenant ids and an id "
                            + "already undeployed are the identical nondisclosing 404.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/deployments/{id}/start",
                    "Starts one process-local deployment and answers when it has reached READY, or with "
                            + "the truthful FAILED status if startup failed and rolled back. Idempotent: "
                            + "starting a READY deployment answers immediately with its current status. "
                            + "Subject to this pod's active-deployment cap, which counts deployments a "
                            + "graceful shutdown would owe time to and so is checked here rather than at "
                            + "registration; exceeding it is a 429.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code(),
                            ErrorCode.REQUEST_LIMIT_EXCEEDED.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/deployments/{id}/stop",
                    "Stops one process-local deployment and leaves it registered and re-startable. Closes "
                            + "admission and inbound sources first, then releases only that deployment's own "
                            + "domain -- never a sibling deployment and never the shared ActorSystem. "
                            + "Idempotent: stopping a stopped deployment answers STOPPED.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code()), NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/deployments/{id}/restart",
                    "A completed stop followed by a start, never the two overlapping, so no source "
                            + "subscription is duplicated across the restart.",
                    true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code()), NEVER, false),
            // Sub-routes under /v1/executions use registersContext=false:
            // the JDK HttpServer matches contexts by longest prefix, so "/v1/executions" already
            // receives both "/v1/executions/{id}" and "/v1/executions/{id}/cancel". Registering a
            // second context would shadow rather than add, and the artifact sub-operations above
            // establish the convention -- declared in the table, dispatched inside the parent handler.
            // visitedNodes, defaultedNodes, bypassedNodes and handledFailureNodes are each a JSON
            // array with no repeats. GraphExecutionResult holds every one of them as a Set, so a node
            // reached, defaulted, bypassed or failed-and-handled more than once in one traversal still
            // appears exactly once in this response. The event projections preserve per-visit counts.
            //
            // /v1/events/recent's durable projection carries the event type but not the node id:
            // RavenrootServer#recentDurableEvents serialises "type":event.eventType() verbatim, while
            // nodeId is absent from its six fields. Its own response names the source as "DURABLE" or
            // "RING"; this is the only API surface that exposes durableEventJournalAvailable(), since
            // neither /v1/status nor /v1/runtime does. The PERSISTENCE EngineCapability on /v1/status
            // is not StoreCapability.EVENT_JOURNAL and therefore is not a second discriminator.
            //
            // The best-effort nodeId join applies only to the durable source of GET /v1/events. On the
            // ring source, nodeId is an ordinary ExecutionEvent field passed straight through by
            // ExecutionMonitor.publish from the id the runner already holds: no join, no separate
            // retention, and no absence for the three event types this description tells a reader to
            // filter. See GraphExecutionResult#visitedNodes() for the Java-side statement of the set
            // collapse.
            //
            // GraphRunner.ExecutionState#nodeCompleted journals NODE_DEFAULTED as its own type, not as
            // a completion flag, so the four node-list fields have four symmetric per-visit event types
            // under both sources. The durable source still uses a best-effort nodeId join, and
            // /v1/events/recent's durable response still carries no node id. The end-to-end HTTP proof
            // is DefaultedNodeDurableCountHttpTest, which counts two NODE_DEFAULTED frames for one node
            // that defaulted twice while defaultedNodes reports it once: "which" and "how many" remain
            // distinct.
            //
            // Journals created by builds that did not emit NODE_DEFAULTED contain no such rows, and
            // nothing in the data distinguishes that history from a traversal with no defaults.
            // envelopeVersion remains 1 because EventEnvelope's shape did not change; its contract
            // increments only for an envelope change a consumer must notice. A client counting
            // defaults across a window spanning that producer upgrade therefore undercounts with no
            // signal, the same failure shape as the durable nodeId caveat.
            new RouteDescriptor(Set.of("GET"), "/v1/executions/{id}",
                    "Reads one execution's status, payload, visited nodes and defaulted nodes. "
                            + "410: it ran, but its result is past the retention horizon. visitedNodes, "
                            + "defaultedNodes, bypassedNodes and handledFailureNodes are each a JSON array "
                            + "of node ids with no repeats: the runtime holds every one of them as a set, "
                            + "so a node reached, defaulted, bypassed or failed-and-handled more than once "
                            + "in this traversal still appears exactly once here, and none of the four "
                            + "says how many times. A fifth field, untakenEdges (#519), is not one of "
                            + "these four and is not a node list: each entry is a string naming one "
                            + "outgoing edge of a node this run bypassed -- "
                            + "\"<source>-><target> [outcome=<outcome>]\" -- that the node's own "
                            + "hardcoded 'continue' outcome could never select, whether the bypass came "
                            + "from mode=test or from an individual edge naming the passthrough command "
                            + "under mode=run. It has no corresponding event on GET /v1/events. "
                            + "GET /v1/events, read directly and filtered "
                            + "client-side to this traversalId and a node id (neither endpoint takes such "
                            + "a filter as a parameter), carries one NODE_STARTED, NODE_BYPASSED, "
                            + "NODE_DEFAULTED or NODE_FAILED event per visit for visitedNodes, "
                            + "bypassedNodes, defaultedNodes and handledFailureNodes respectively -- "
                            + "under both of that stream's sources, with two silent-undercount "
                            + "caveats of the same shape where the source is a durable journal. "
                            + "First, NODE_DEFAULTED rows exist only in a journal written by a build "
                            + "that emits them, and nothing in the data says which: envelopeVersion "
                            + "does not change, correctly, because the envelope's shape did not -- so "
                            + "a client counting defaults over a window spanning that upgrade "
                            + "undercounts with no signal. Second, nodeId is a best-effort join "
                            + "against invocation state "
                            + "with its own retention, so a client filtering by node can undercount "
                            + "silently too; from the in-memory ring the node id is carried on the event "
                            + "itself. Which applies is "
                            + "observable: /v1/events/recent's own response names its source, "
                            + "'source=DURABLE' or 'source=RING' -- the only place on this API that "
                            + "surfaces it, since neither /v1/status nor /v1/runtime does. The durable "
                            + "response carries the type but not the node id, so it cannot attribute "
                            + "any of the four to a node; the ring response carries both, like "
                            + "/v1/events. Without a journal, /v1/events keeps a bounded in-memory "
                            + "window that resets on every restart and can be shorter than this "
                            + "result's own retention.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_EXECUTION.code(),
                            ErrorCode.EXECUTION_RESULT_EXPIRED.code(), ErrorCode.INVALID_REQUEST.code()), READ, true),
            // This tenant's live executions, with their identifiers, read straight from
            // runtime bookkeeping rather than from the event stream -- a stalled traversal that has
            // stopped emitting still appears, because it is listed from the same map
            // cancelTraversal mutates. A distinct path from "/v1/executions" rather than a bare GET
            // on it: RouteDescriptor is per-path (OpenApiSpecGenerator keys the generated document
            // by route.path()), and this table's own coherence rule refuses a multi-method
            // descriptor sideEffectFree/READ (see /v1/program-artifacts). Sharing the exact literal
            // path with POST /v1/executions's existing descriptor would force this listing into that
            // same NEVER/mutating shape for no reason connected to what it actually does -- so it
            // gets its own entry, exactly like /v1/executions/{id} and /v1/executions/{id}/cancel
            // already do, dispatched from the same physical context (registersContext=false).
            new RouteDescriptor(Set.of("GET"), "/v1/executions/live",
                    "Lists this tenant's live executions (accepted and not yet terminal) with their "
                            + "identifiers. Read directly from runtime bookkeeping, never derived from "
                            + "the event stream, so a stalled traversal that has stopped emitting still "
                            + "appears -- that property is why this route exists. Tenant-scoped "
                            + "structurally, the same way GET /v1/executions/{id} is: never reveals "
                            + "whether another tenant has executions running.", true, false, 200,
                    STANDARD_ERRORS, READ, true),
            new RouteDescriptor(Set.of("POST"), "/v1/executions/{id}/cancel",
                    "Cancels a traversal (#37). 200 with a CancelResult body distinguishing CANCELLED, "
                            + "ALREADY_CANCELLED and ALREADY_COMPLETED; unknown ownership fails closed as "
                            + "403, not a distinct outcome.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code(), ErrorCode.UNKNOWN_RESOURCE.code()),
                    NEVER, false),
            // Separate descriptors rather than extra methods on the cancel entry: they are
            // different paths, and this table is keyed by path (OpenApiSpecGenerator keys the
            // generated document by route.path()), the same reason /v1/executions/live has its own.
            new RouteDescriptor(Set.of("POST"), "/v1/executions/{id}/pause",
                    "Pauses a traversal (#488): the node in flight finishes and nothing after it is "
                            + "dispatched until the traversal is resumed. 200 with a PauseResult body "
                            + "distinguishing PAUSED, ALREADY_PAUSED and NOT_ACTIVE; unknown ownership "
                            + "fails closed as 403, not a distinct outcome. A paused traversal is still "
                            + "live: it keeps its state, still appears in GET /v1/executions/live and is "
                            + "still cancellable.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code(), ErrorCode.UNKNOWN_RESOURCE.code()),
                    NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/executions/{id}/resume",
                    "Resumes a paused traversal (#488), continuing from the node it was holding at. "
                            + "200 with a ResumeResult body distinguishing RESUMED, NOT_PAUSED and "
                            + "NOT_ACTIVE -- resuming a traversal that was never paused is reported, not "
                            + "silently successful.", true, false, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code(), ErrorCode.UNKNOWN_RESOURCE.code()),
                    NEVER, false),
            new RouteDescriptor(Set.of("POST"), "/v1/drain",
                    "Drains the server (#37): ADR 0012's engine-wide drain exposed as an operator command. "
                            + "Platform-scoped, not any tenant's own operator. 200 DRAINED; 202 TIMED_OUT if "
                            + "the configured bound elapsed with work still outstanding.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INTERNAL_ERROR.code()), NEVER, false),
            new RouteDescriptor(Set.of("GET"), "/v1/events",
                    "Server-Sent Events stream of execution events, resumable via Last-Event-ID. "
                            + "Without a selector it uses the durable journal when available. "
                            + "'include=diagnostics' instead selects the authenticated in-memory ring "
                            + "for the entire stream and declares source RING with PROCESS_LOCAL "
                            + "continuity. EDGE_TRAVERSED identifies an actual, unambiguous successor "
                            + "dispatch through stable edgeId; clients must not infer traversal from "
                            + "NODE_COMPLETED. edgeId is accepted unchanged up to 8192 strict UTF-8 bytes; "
                            + "all auxiliary traversal strings share a 12287-byte escaped budget, keeping "
                            + "the complete frame below 65536 bytes. Oversize values are rejected, never "
                            + "truncated. Durable frames carry handlerId beside processInstanceId, "
                            + "traversalId and invocationId, so a handler-lifecycle event is told apart "
                            + "from a node event that shares all three; it is null on every other type. "
                            + "Ring-served views (also "
                            + "the default where no journal exists) "
                            + "carry author-safe failure messages and built-in log output; these are "
                            + "bounded, carry explicit redacted/truncated flags, are lost on restart or "
                            + "ring eviction, and are never persisted. Raw 'detail' is never serialized. "
                            + "The diagnostics stream is not assistant/provider context.", true, true,
                    200, STANDARD_ERRORS, READ, true),
            // The bounded, request/response counterpart of /v1/events: a poll cannot be built on
            // the stream, which would spend a rate-limited stream slot on a read and answer over a
            // window the caller cannot observe.
            //
            // READ is declared deliberately, not inherited: this route is authenticated and answers
            // GET and nothing else, so it satisfies both of RouteDescriptor's coherence rules rather
            // than merely passing them. It carries the same data /v1/events already carries, under the
            // same authorization, to the same principal -- a different shape of one surface, so a
            // posture weaker than its stream's would be an inconsistency rather than caution.
            new RouteDescriptor(Set.of("GET"), "/v1/events/recent",
                    "Bounded, resumable read of recent execution events, ascending by cursor, strictly "
                            + "after 'after'. Declares which source served it and the oldest cursor still "
                            + "available; reports an explicit gap when 'after' precedes that floor rather "
                            + "than returning a silently continuous list. A 'limit' above the server cap "
                            + "is refused, never clamped. EDGE_TRAVERSED identifies an actual, unambiguous "
                            + "successor dispatch through stable edgeId; duplicate edges collapsed to one "
                            + "route produce no fabricated attribution. edgeId is accepted unchanged up to "
                            + "8192 strict UTF-8 bytes; auxiliary traversal strings share a 12287-byte escaped "
                            + "budget, keeping each frame below 65536 bytes. Oversize values are rejected, "
                            + "never truncated. Durable rows carry handlerId, so a handler-lifecycle event "
                            + "names the handler it is about; it is null on every other type. "
                            + "'include=diagnostics' selects in-process "
                            + "instrumentation (activeInstances, inFlightArrivals, fallback, "
                            + "processingDuration), bounded author-safe failure messages and built-in "
                            + "log output by "
                            + "content rather than by source; when those have aged out the caller is told "
                            + "they are gone rather than handed events with silently absent fields. The gap "
                            + "marker describes retention only, never visibility. Every event carries a "
                            + "bounded public description built from source-authored text and, where the "
                            + "event's meaning depends on it, a classifier in 'publicReason' -- a token "
                            + "restricted to letters, digits and . _ - : so it cannot hold prose. A completed "
                            + "node names the outcome it routed instead of being reported as a success, and a "
                            + "failure names its cause's Java class. With diagnostics selected, separate "
                            + "message/output fields carry redacted/truncated flags and are process-local, "
                            + "lost on restart or ring eviction, never persisted, and never eligible for "
                            + "assistant/provider projection. Raw diagnostic detail never crosses this route "
                            + "or the SSE stream. The journal never captured "
                            + "the classifier, so a replayed completed node gets the weaker sentence that does "
                            + "not claim success rather than a confident one it cannot substantiate. The "
                            + "legacy 'detail' key, which held a copy of the public sentence under a name "
                            + "promising the diagnostic, is removed.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code(),
                            ErrorCode.EVENT_LIMIT_ABOVE_MAXIMUM.code(),
                            ErrorCode.INTERNAL_ERROR.code()), READ, true),
            // ADR 0025. The two paths ravenroot-ui's assistant-client.js calls, and the
            // complete set it is permitted to call -- `ASSISTANT_PATHS` there is frozen to exactly these.
            //
            // Both are NEVER, and that is the interesting declaration in this table rather than a
            // formality: the assistant must not read its own status or replay its own transcript. A READ
            // posture on either would give a conversational surface a view of itself, which is the
            // shortest path from "read-only assistant" to "assistant that can observe the effect of its
            // own turns"; lifecycle control is exposed on exactly this surface.
            new RouteDescriptor(Set.of("GET"), "/v1/assistant",
                    "What this deployment says about its authoring assistant: whether a provider profile is "
                            + "configured, whether its host is permitted by the outbound policy, and whether a "
                            + "credential resolves. 404 when the operator has disabled the service. Never "
                            + "carries the provider credential.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code()), NEVER, true),
            new RouteDescriptor(Set.of("POST"), "/v1/assistant/messages",
                    "Accepts the author's prompt, disclosed context classes and optional exact editor "
                            + "document binding (incarnation, revision and catalog digest), then sends the "
                            + "turn to the configured provider. A success always carries text, model and "
                            + "truncated, and may also carry one validated inert graph proposal that the "
                            + "browser previews and applies only after explicit per-proposal confirmation. "
                            + "Answers either that compatible success shape or a named failure -- never an "
                            + "empty 2xx and never a server-side graph mutation.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_INTERRUPTED.code(),
                            ErrorCode.INTERNAL_ERROR.code()), NEVER, false),
            // The third, and NEVER for a sharper reason than the other two: this path conducts
            // the author's credential exchange. A conversational surface that could reach it could
            // begin a sign-in the author did not ask for, and read back the code meant for their
            // eyes -- which is the whole grant, since the code is what a phishing page needs.
            //
            // `sideEffectFree` is false because POST and DELETE start and abandon a real exchange
            // with a provider. GET only observes, but a descriptor covers the path, and declaring
            // the safest of three methods would describe the route by its most harmless one.
            new RouteDescriptor(Set.of("POST", "GET", "DELETE"), "/v1/assistant/connection",
                    "Begins, reports on, and abandons this author's own connection to the model "
                            + "provider through the device-authorization grant. Answers the code and "
                            + "the address the author must use, then the named outcome of each check. "
                            + "Never carries the device code or the obtained token. 409 when this "
                            + "deployment has no connection path configured.", true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.UNKNOWN_RESOURCE.code(),
                            ErrorCode.CONFLICT.code(), ErrorCode.METHOD_NOT_ALLOWED.code()),
                    NEVER, false),
            // The one route in this server through which a credential VALUE may enter, and the
            // route from which an author's own references are listed for a selector.
            //
            // registersContext is TRUE and the context is registered unconditionally, which is what
            // keeps this table's agreement with the live server true by construction rather than by
            // composition. A host that composed no credential store answers UNKNOWN_RESOURCE from the
            // handler -- observationally the same thing a client sees for an unserved path, and what
            // the absent-adapter contract requires. RavenrootServerMain always composes one.
            //
            // NEVER, and this one deserves its reason rather than the mechanical rule, because GET
            // alone would otherwise have qualified for READ: it is authenticated, it is genuinely
            // side-effect free, and it returns no secret. READ means "this data may reach the
            // assistant, under the author's own SecurityContext". An enumeration of which providers
            // an author holds credentials for is a description of that author's paid relationships,
            // and it is inferable from the labels alone -- "Claude connection", "OpenAI API
            // connection". Nothing in this feature needs the assistant to know it, so it is not offered.
            //
            // There is deliberately no DELETE. A delete that forgot the row while every graph naming the reference
            // kept naming it would be a worse state than not having one: the author would believe a
            // credential was withdrawn while the graphs that used it merely started failing.
            new RouteDescriptor(Set.of("GET", "POST"), "/v1/credentials",
                    "GET answers {credentials}, each carrying reference, label, scheme, username and "
                            + "createdAt -- ONLY those belonging to the authenticated caller, and "
                            + "never a value in any form, masked or otherwise. POST stores one value "
                            + "and answers the same shape. The reference is minted by the server: a "
                            + "body proposing one is refused rather than ignored. A graph submitted "
                            + "to /v1/executions naming a stored "
                            + "credential the submitter does not own is refused with access denied.",
                    true, true, 200,
                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code()), NEVER, false));

    private static List<String> concat(List<String> base, String... extra) {
        var combined = new java.util.ArrayList<>(base);
        combined.addAll(List.of(extra));
        return List.copyOf(combined);
    }
}
