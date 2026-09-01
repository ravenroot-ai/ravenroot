package ai.ravenroot.server.assistant.tools;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.server.assistant.AssistantContextClass;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.audit.JsonStrings;
import ai.ravenroot.server.spec.AssistantPosture;
import ai.ravenroot.server.spec.RouteDescriptor;
import ai.ravenroot.server.spec.RouteTable;

import java.util.List;
import java.util.Objects;

/**
 * Tier-0 internal context: the reads the assistant may perform on the author's behalf under the
 * assistant-tool boundary.
 *
 * <h2>The two type-level properties this class exists to make true</h2>
 * <p>The assistant-tool boundary requires both properties:</p>
 *
 * <p><b>1. The authorized application is the only capability.</b> This class has exactly one field.
 * There is no HTTP client, no {@code URI}, no socket, no provider reference and no constructor
 * parameter that could carry one — <em>an internal tool cannot egress because it has nothing to
 * egress with.</em> That is checked rather than asserted: {@code AssistantToolBoundaryTest#noToolHoldsAnythingThatCanEgress}
 * reflects over every type in this package and fails on a field or constructor parameter assignable to
 * {@link java.net.http.HttpClient}, {@link java.net.URLConnection}, {@link java.net.Socket},
 * {@link java.net.URL} or {@link AssistantProvider}. Adding any of them reds that test; the import of
 * {@code AssistantProvider} above exists so the test's own reference set is compiled against reality
 * rather than against a string.</p>
 *
 * <p><b>2. Every read passes the same authorization the user's own UI calls pass.</b> Not "a similar
 * check" — the same one, because every method below goes through
 * {@link AuthorizedRavenrootApplication} with the author's own {@link RequestContext}, which is the
 * identical entry point {@code RavenrootServer}'s own handlers use. There is deliberately no path here
 * to the undecorated {@code RavenrootApplication}: a denial to the user is therefore a denial to the
 * panel, and it is a denial for the same reason, decided by the same policy point, in the same call.
 * {@code AssistantRouteTest#aDeniedReadEndsTheTurnWithTheSameDenialTheRouteGives} plants an
 * authorization service that denies exactly one
 * action and asserts the tool surfaces the same denial the HTTP route produces.</p>
 *
 * <h2>Posture, made load-bearing rather than declarative</h2>
 * <p>Each tool names the route whose data it mirrors, and construction fails if that route's
 * {@link AssistantPosture} is not {@link AssistantPosture#READ}. This is what stops the posture column
 * in {@code RouteTable} from being a comment: flipping {@code /v1/node-types} to {@code NEVER} does not
 * produce a stale label, it produces a server that refuses to start and a test that reds.</p>
 *
 * <h2>What is deliberately absent: logs</h2>
 * <p>ADR 0025 names logs as a tier-0 class and the contract requires them by name. There is <b>no tool
 * for them here</b>, because {@link AuthorizedRavenrootApplication} exposes no log-reading method —
 * there is no authorized port to call. Adding one means a new authorization action with its own scope,
 * a tenancy filter, a redaction pass and a bounded tail: real work with real security surface, and
 * adding one without those boundaries would create exactly the "reads it should not be able to
 * perform" failure the design prevents.</p>
 *
 * <p>{@link AssistantContextClass} has no {@code LOGS} member because a consent toggle over a read
 * that does not exist would promise an author that
 * something is being withheld from a flow that never runs. The same holds for the graph and for
 * validation errors. See that enum for the full reasoning.</p>
 *
 * <h2>Authorization and consent are different gates, and both apply</h2>
 * <p>Property 2 above answers "may this author read this?". {@link AssistantContextClass} answers a
 * question authorization cannot: "may this author's data be <em>sent to a third party</em>?".
 * They are independent and neither substitutes for the other — an author permitted to read the event
 * stream may still refuse to have it forwarded to a model provider, and the refusal is not a
 * permission problem. So {@link #specs} and {@link #invoke} take the consented set as a parameter and
 * this class stores none of it: the composer re-reads the register before every request, and nothing
 * here can serve a class the author has withdrawn since the turn began.</p>
 *
 * <h2>Composition boundary</h2>
 * <p>Which of these become model-visible tools and their model-facing descriptions are composition
 * concerns. This class defines the mechanism: the capability
 * type, the authorization path, the posture gate and the failure semantics.</p>
 */
public final class AssistantInternalContext {

    /**
     * The tier-0 reads.
     *
     * <p>Each carries the route path it mirrors. All four are authenticated, {@code GET}-only routes,
     * which is what lets them hold a {@code READ} posture at all — {@code RouteDescriptor} refuses that
     * posture for anything else.</p>
     */
    public enum Tool {
        STATUS("ravenroot_status", "/v1/status", AssistantContextClass.STATUS,
                "Read this Ravenroot deployment's status: engine state, execution engine id, and the "
                        + "capabilities this build declares."),
        RUNTIME("ravenroot_runtime", "/v1/runtime", AssistantContextClass.RUNTIME,
                "Read the live runtime snapshot: how many executions are active and how many instances "
                        + "of each node type are running."),
        NODE_TYPES("ravenroot_node_types", "/v1/node-types", AssistantContextClass.NODE_TYPES,
                "Read the trusted node-type catalog: which node types this deployment offers and what "
                        + "properties each accepts."),
        EXECUTION_EVENTS("ravenroot_execution_events", "/v1/events",
                AssistantContextClass.EXECUTION_EVENTS,
                "Read recent execution events the calling author is permitted to observe, oldest first.");

        private final String toolName;
        private final String routePath;
        private final AssistantContextClass contextClass;
        private final String description;

        Tool(String toolName, String routePath, AssistantContextClass contextClass, String description) {
            this.toolName = toolName;
            this.routePath = routePath;
            this.contextClass = contextClass;
            this.description = description;
        }

        public String toolName() {
            return toolName;
        }

        public String routePath() {
            return routePath;
        }

        /**
         * The class of context this read produces, which is what an author consents to or refuses.
         *
         * <p>The mapping is one-to-one today and is still written out rather than derived from the
         * constant's name: the tool name and description above may change, while
         * {@link AssistantContextClass} is what durable consent rows are
         * keyed on. A derived mapping would make a rename here a silent revocation there — see that
         * enum's Javadoc.</p>
         */
        public AssistantContextClass contextClass() {
            return contextClass;
        }
    }

    /** How many events one read returns. Bounded so a busy deployment cannot flood one turn. */
    static final int EVENT_LIMIT = 200;

    /** No arguments. A schema authored in product code, never derived from graph or chat content. */
    private static final String NO_ARGUMENTS_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    /** The one capability. See this class's Javadoc — the singularity is the property, not a style. */
    private final AuthorizedRavenrootApplication application;

    public AssistantInternalContext(AuthorizedRavenrootApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        for (Tool tool : Tool.values()) {
            requireReadPosture(tool.toolName(), tool.routePath());
        }
    }

    /**
     * Refuses to build a reader over a route the table does not permit the assistant to read.
     *
     * <p>Fails at construction rather than at invocation on purpose: a posture regression should stop
     * the server from starting, not wait for an author to ask a question that happens to hit the
     * downgraded tool.</p>
     */
    static void requireReadPosture(String toolName, String routePath) {
        RouteDescriptor route = RouteTable.ALL.stream()
                .filter(candidate -> candidate.path().equals(routePath))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "assistant tool '" + toolName + "' names a route that is not in RouteTable: "
                                + routePath));
        if (route.assistantPosture() != AssistantPosture.READ) {
            throw new IllegalStateException("assistant tool '" + toolName + "' reads "
                    + routePath + ", whose assistant posture is " + route.assistantPosture()
                    + " -- a tool may only mirror a route declared READ");
        }
    }

    /**
     * The tool declarations handed to the provider, narrowed to what this author consented to send.
     *
     * <p><b>The granted set is a parameter and not a field, and that is deliberate.</b> This class
     * holds one capability by design — see its Javadoc — and a cached consent set would be a second
     * piece of state, one that goes stale the moment an author revokes. Passing it in per call means
     * the composer re-reads the register before every request it composes, and this class cannot serve
     * a grant that has since been withdrawn because it never held one.</p>
     *
     * @param granted the classes {@code AssistantConsentStore} reports for this author and provider.
     *                Empty is legitimate and yields no tools at all: an author who has consented to
     *                nothing gets a model that can read nothing about their deployment.
     */
    public List<AssistantProvider.ToolSpec> specs(java.util.Set<AssistantContextClass> granted) {
        Objects.requireNonNull(granted, "granted");
        return java.util.Arrays.stream(Tool.values())
                .filter(tool -> granted.contains(tool.contextClass()))
                .map(tool -> new AssistantProvider.ToolSpec(tool.toolName(), tool.description,
                        NO_ARGUMENTS_SCHEMA))
                .toList();
    }

    /**
     * Whether this author's consent covers the class of context {@code toolName} would read.
     *
     * <p>Separate from {@link #knows(String)}, which answers whether the tool <em>exists</em>. The two
     * questions have different answers and different consequences: an unknown name is the model asking
     * for something this build does not offer, which it can recover from; a known name over a refused
     * class is the model asking for something it was deliberately not offered.</p>
     */
    public boolean permits(String toolName, java.util.Set<AssistantContextClass> granted) {
        Objects.requireNonNull(granted, "granted");
        return java.util.Arrays.stream(Tool.values())
                .filter(tool -> tool.toolName().equals(toolName))
                .anyMatch(tool -> granted.contains(tool.contextClass()));
    }

    public boolean knows(String toolName) {
        return java.util.Arrays.stream(Tool.values()).anyMatch(tool -> tool.toolName().equals(toolName));
    }

    /**
     * Performs one read under the author's own {@link RequestContext}.
     *
     * @throws AuthorizationDeniedException propagated unchanged from
     *                                      {@link AuthorizedRavenrootApplication}. <b>Not caught
     *                                      here.</b> The caller decides whether a denial ends the turn
     *                                      or is reported to the model as a failed tool call, and
     *                                      swallowing it here would let the assistant retry a read its
     *                                      author is not allowed to perform.
     */
    public String invoke(String toolName, RequestContext context,
                         java.util.Set<AssistantContextClass> granted) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(granted, "granted");
        Tool tool = java.util.Arrays.stream(Tool.values())
                .filter(candidate -> candidate.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown assistant tool: " + toolName));
        // The backstop, not the graceful path. AssistantService#runTools branches on permits() and
        // answers a refused class as a failed tool result the model can reason about; reaching here
        // means a caller performed the read without asking, and the read must not happen anyway. The
        // check sits before the switch so no branch below can touch the application first.
        if (!granted.contains(tool.contextClass())) {
            throw new IllegalStateException("assistant tool '" + toolName + "' reads context class "
                    + tool.contextClass() + ", which this author has not consented to send to the "
                    + "configured provider");
        }
        return switch (tool) {
            case STATUS -> statusJson(context);
            case RUNTIME -> runtimeJson(context);
            case NODE_TYPES -> nodeTypesJson(context);
            case EXECUTION_EVENTS -> eventsJson(context);
        };
    }

    private String statusJson(RequestContext context) {
        var status = application.status(context);
        return "{\"state\":\"" + JsonStrings.escape(status.state())
                + "\",\"executionEngine\":\"" + JsonStrings.escape(status.executionEngine())
                + "\",\"capabilities\":[" + status.capabilities().stream().sorted()
                .map(value -> "\"" + JsonStrings.escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    private String runtimeJson(RequestContext context) {
        var snapshot = application.runtimeSnapshot(context);
        // activeNodeInstances is the number the assistant reports to a request for load per node --
        // it is arrivals in flight, not instances of the node's actor. See RuntimeSnapshot's own
        // Javadoc; the wire name is kept for compatibility.
        String nodes = snapshot.activeNodeInstances().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> "\"" + JsonStrings.escape(entry.getKey()) + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"activeExecutions\":" + snapshot.activeExecutions()
                + ",\"activeNodeInstances\":{" + nodes + "}}";
    }

    private String nodeTypesJson(RequestContext context) {
        return application.nodeTypes(context).stream()
                .map(type -> "{\"behavior\":\"" + JsonStrings.escape(type.behavior())
                        + "\",\"displayName\":\"" + JsonStrings.escape(type.displayName())
                        + "\",\"category\":\"" + JsonStrings.escape(type.category())
                        + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * Recent events, already narrowed to what this author may observe.
     *
     * <p>The narrowing is {@link AuthorizedRavenrootApplication#executionEventsAfter}'s own: it
     * authorizes the collection, then filters each event through the same per-execution ownership check
     * the SSE route applies. This tool adds a bound and nothing else — it does not re-implement the
     * filter, which is the point.</p>
     */
    private String eventsJson(RequestContext context) {
        var events = application.executionEventsAfter(context, 0L);
        int from = Math.max(0, events.size() - EVENT_LIMIT);
        return events.subList(from, events.size()).stream()
                .map(event -> "{\"sequence\":" + event.sequence()
                        + ",\"type\":\"" + JsonStrings.escape(String.valueOf(event.type()))
                        + "\",\"node\":\"" + JsonStrings.escape(String.valueOf(event.nodeId()))
                        + "\",\"execution\":\"" + JsonStrings.escape(String.valueOf(event.executionId()))
                        + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
