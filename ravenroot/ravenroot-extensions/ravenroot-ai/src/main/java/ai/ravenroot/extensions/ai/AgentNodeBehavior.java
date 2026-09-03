package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
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
import ai.ravenroot.api.node.service.ToolCallAuthorization;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import java.util.function.LongSupplier;

/**
 * {@code agent}: a real agent — a bounded loop of model turns and tool calls — reached over the
 * managed HTTP channel against an operator-owned OpenAI-compatible endpoint.
 *
 * <h2>What separates this node from {@code llm-prompt}, which is the whole of it</h2>
 * <p>{@link LlmPromptNodeBehavior} makes <b>one</b> call: it renders a template, sends it, reads the
 * answer, and is done. No second turn exists there by construction, and that is the source of its
 * safety argument.</p>
 *
 * <p>This node is a <b>loop</b>. It sends the objective together with the tools it is willing to
 * expose; the model either answers or asks for a tool; a tool answers; the loop repeats. Everything
 * else about this class follows from that one difference:</p>
 * <ul>
 *   <li><b>the budgets exist because the loop may not terminate on its own</b> — a model can ask for
 *   the same tool forever, and only a finite bound ends it;</li>
 *   <li><b>the tool contract exists because the model chooses what to call</b> — a refusal must be an
 *   answer the model can read and correct, not a terminated traversal (see {@link AgentTool});</li>
 *   <li><b>only operator policy lives in the system turn</b>; graph instructions are a separate,
 *   untrusted user turn — see {@link AgentTurn} rule 2.</li>
 * </ul>
 *
 * <h2>Three properties carried over from {@code llm-prompt} unchanged, and why each is not optional</h2>
 * <ol>
 *   <li><b>{@link #descriptor()} declares {@code ai} and {@code agentic}.</b> Those are what mark this
 *   node's output as synthetic: {@code SyntheticProvenance.GENERATIVE_CAPABILITIES} reads the
 *   <em>registered catalog descriptor</em> on every successful completion, not anything the node did
 *   (the generative-provenance contract). Removing either would silently unmark the output.</li>
 *   <li><b>A refusal is never a {@link NodeResult}.</b> A successful result stamps a machine-readable
 *   assertion that a model generated content; returning one from a refusal asserts it about content
 *   no model produced. Every refusal here is a failed future.</li>
 *   <li><b>And never a synchronous throw.</b> The runner invokes the action inside
 *   {@code RavenNode#onMessage} while the traversal's terminal bookkeeping lives inside the returned
 *   stage, so a synchronous throw can leave a traversal non-terminal with no failure event recorded —
 *   and whether it does depends on which engine adapter is installed.</li>
 * </ol>
 *
 * <h2>The credential is never in this process's reach</h2>
 * <p>Like its sibling, this behavior requires {@link NodePackageCapability#OUTBOUND_HTTP} and
 * deliberately <b>not</b> {@code CREDENTIAL_RESOLUTION}; it additionally requires
 * {@link NodePackageCapability#TOOL_AUTHORIZATION}. The profile names a binding; the runtime resolves
 * it and places it on the request. This bundle never holds a secret and has no code path that could
 * return one.</p>
 */
public final class AgentNodeBehavior implements NodeBehavior {

    /** The catalog name, preserved from the node formerly published by the core. */
    public static final String BEHAVIOR = "agent";

    /**
     * Turns a run takes when the author names no bound.
     *
     * <p>Small on purpose. An agent that needs more than this is usually one whose objective is
     * under-specified, and the failure an author wants in that case is a fast, named one rather than
     * a long, expensive one that arrives at the same place.</p>
     */
    static final int DEFAULT_MAX_TURNS = 8;

    /** Turns this node will run however large a number an author writes. */
    static final int MAX_TURNS_CEILING = 64;

    /**
     * What a model is told when a tool broke its own contract — threw, failed its stage, or answered
     * with nothing.
     *
     * <p>Says nothing about which of the three it was, because the model can do the same thing about
     * all three and the difference is an operator's to see in a log, not a model's to reason about.</p>
     */
    static final String TOOL_FAILED =
            "The tool failed. Do not call it again with the same arguments.";

    /**
     * The token ceiling when the author names none: unbounded.
     *
     * <p>Deliberately not a number. Token usage per turn depends on the model, the instructions and
     * how much a tool returns, so any default this bundle picked would terminate some legitimate runs
     * and none of the runaway ones. The bound that always applies is {@link #DEFAULT_MAX_TURNS}, and
     * it is the one that does not need to guess.</p>
     */
    static final long UNBOUNDED_TOKENS = 0L;

    /**
     * Most MCP servers one node may declare.
     *
     * <p>Small, and the bound is discovery rather than taste: opening a server costs three exchanges
     * before the first model turn, so the number of servers multiplies the latency an author pays
     * before anything happens. An agent that genuinely needs more than this is one whose objective
     * should be split across nodes, which the managed-team shape of
     * {@code docs/product/agent-node-and-managed-teams.md} is exactly for.</p>
     */
    static final int MAX_MCP_SERVERS = 8;

    /** The mask a server name must match before it is used to derive an environment variable. */
    private static final String MCP_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final LlmProfileResolver profiles;
    private final McpProfileResolver mcpProfiles;
    /** Per (tenant, profile) admission, shared by every node of this type. */
    private final Admission profileAdmission = new Admission();
    /**
     * Per (tenant, MCP server) admission, held for the whole run.
     *
     * <p>Its own registry and not a shared one keyed by a composed string: a model profile and an MCP
     * server may legitimately carry the same name, and one namespace would let either one consume the
     * other's allowance. Held for the run rather than per exchange because that is what the operator's
     * number means — how many agents may be talking to this server at once, not how many requests may
     * be in flight, which is a quantity nobody can act on.</p>
     */
    private final Admission mcpAdmission = new Admission();

    public AgentNodeBehavior() {
        this(new EnvironmentLlmProfileResolver(), new EnvironmentMcpProfileResolver());
    }

    AgentNodeBehavior(LlmProfileResolver profiles) {
        this(profiles, name -> Optional.empty());
    }

    AgentNodeBehavior(LlmProfileResolver profiles, McpProfileResolver mcpProfiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.mcpProfiles = Objects.requireNonNull(mcpProfiles, "mcpProfiles");
    }

    @Override
    public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP,
                NodePackageCapability.TOOL_AUTHORIZATION);
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Agent", "AI",
                "Runs a bounded agent loop against an operator-configured OpenAI-compatible model: "
                        + "the model plans, calls the tools this node exposes, reads their results, "
                        + "and continues with its final answer.",
                "agent", true, properties(List.of(
                // An adapter binding, not a plain required property, for the reason spelled out on
                // LlmPromptNodeBehavior: leaving it blank makes this an unconfigured node rather than
                // a defective graph (CORE-07), and the emptiness test must stay adapterIdOf(...).
                NodePropertyDescriptor.adapterId("provider", "Provider", NodePropertyType.STRING,
                        "Name of a model profile this deployment declared in its environment "
                                + "(RAVENROOT_LLM_PROFILE_<hex(name)>)."),
                NodePropertyDescriptor.required("instructions", "Instructions", NodePropertyType.TEXT,
                        "Who the agent is and how it should work. Sent as untrusted graph content, "
                                + "separate from operator policy. Supports {{payload}}, {{payload.a.b}} and "
                                + "{{attributes.x}}."),
                NodePropertyDescriptor.required("objective", "Objective", NodePropertyType.TEXT,
                        "The task for this invocation. Sent as the first user turn. Supports "
                                + "{{payload}}, {{payload.a.b}} and {{attributes.x}}."),
                NodePropertyDescriptor.optional("model", "Model", NodePropertyType.STRING,
                        "Overrides the profile's model. The endpoint is not overridable.", ""),
                // Names only. The endpoint of each server, its credential and -- decisively -- the
                // tools it may run all come from the operator's environment, never from here. ADR
                // The egress contract refuses user-configurable context endpoints and names MCP
                // endpoints in as many words, so this property is a reference and cannot become an
                // address however it is written.
                NodePropertyDescriptor.optional("mcpServers", "MCP servers", NodePropertyType.STRING,
                        "Comma-separated names of MCP servers this deployment declared in its "
                                + "environment (RAVENROOT_MCP_SERVER_<hex(name)>). Their tools are "
                                + "offered to the model as <server>__<tool>. At most "
                                + MAX_MCP_SERVERS + ".", ""),
                NodePropertyDescriptor.optional("maxTurns", "Max turns", NodePropertyType.INTEGER,
                        "Model turns this run may take before it is refused. Defaults to "
                                + DEFAULT_MAX_TURNS + ", never exceeds " + MAX_TURNS_CEILING + ".", ""),
                NodePropertyDescriptor.optional("maxTotalTokens", "Max tokens", NodePropertyType.INTEGER,
                        "Cumulative reported tokens across the whole run before it is refused. "
                                + "Unbounded when absent.", ""),
                NodePropertyDescriptor.optional("timeoutMs", "Deadline", NodePropertyType.INTEGER,
                        "Deadline for the WHOLE run, not for one turn. May only tighten the "
                                + "profile deadline.", ""),
                NodePropertyDescriptor.optional("maxTokens", "Max tokens per turn",
                        NodePropertyType.INTEGER,
                        "Upper bound on one generated turn, when the endpoint honours it.", ""),
                NodePropertyDescriptor.optional("temperature", "Temperature", NodePropertyType.STRING,
                        "Sampling temperature forwarded verbatim.", ""),
                NodePropertyDescriptor.optional("topP", "Top-p", NodePropertyType.STRING,
                        "Nucleus sampling parameter forwarded verbatim.", ""),
                NodePropertyDescriptor.optional("seed", "Seed", NodePropertyType.INTEGER,
                        "Sampling seed, when the endpoint honours it.", "")),
                // Declared here and built there, so this bundle spells "skills.<n>.name" exactly
                // once: the descriptor the editor renders, the reader that parses the values and the
                // listing the model sees all go through AgentSkill and cannot drift apart.
                AgentSkill.propertyDescriptors()),
                // "ai" and "agentic" are both members of SyntheticProvenance.GENERATIVE_CAPABILITIES.
                // They are not decoration: they are the entire reason this node's output is marked,
                // and the other three only describe what it does.
                Set.of("ai", "agentic", "external-provider", "network", "credential-reference"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The agent finished and its answer becomes the outgoing payload. The only "
                                + "outcome this node produces: anything else fails the node."));
    }

    /**
     * The node's own properties followed by the skill slots, as one list.
     *
     * <p>Concatenated rather than interleaved so the order the editor renders stays the order an
     * author works in: what the node is and how it is bounded first, then what it knows how to do.</p>
     */
    private static List<NodePropertyDescriptor> properties(List<NodePropertyDescriptor> own,
                                                           List<NodePropertyDescriptor> skills) {
        var all = new ArrayList<NodePropertyDescriptor>(own.size() + skills.size());
        all.addAll(own);
        all.addAll(skills);
        return List.copyOf(all);
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        // SDK /1 path, and the deny-only view is the correct authority for it: a package activated
        // without a grant has no egress, and the node says so when reached rather than at build time.
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override
    public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        // Skills are validated BEFORE the provider, and the ordering is deliberate in both directions.
        //
        // Before, because a defective skill is a defect of the GRAPH and not of the deployment: it is
        // wrong whatever profile is or is not declared, and validating it after the provider made the
        // refusal conditional on the profile resolving -- so a node whose provider is still blank, the
        // ordinary state while a graph is being drawn, was never checked at all. That is exactly when
        // an author is writing skills, so it is the worst moment to be silent. Found by
        // AiBundleEndToEndTest#anOversizeSkillRefusesThroughTheRealRegistry, which did not throw.
        //
        // And it does not weaken CORE-07, which the provider read below defends: that rule makes a
        // node with no provider UNCONFIGURED rather than defective, and skills say nothing about it.
        // A node with no skill properties passes here trivially and reaches the provider read
        // unchanged; only a node carrying a skill that can never work is stopped, which is what
        // NodeBehavior#create reserves a throw for.
        List<AgentSkill> skills = AgentSkill.declaredOn(configuration);
        // Read FIRST among the adapter properties, or a node with neither a provider nor instructions
        // would fail on the instructions and the CORE-07 reversal would only appear to work.
        String profileName = NodePropertyDescriptor.adapterIdOf(configuration.properties().get("provider"));
        if (profileName.isEmpty()) {
            return refuse(AgentException.Code.PROFILE_UNKNOWN, "");
        }
        // Resolved exactly once, here, and captured: an execution's capability set is fixed at
        // admission, so a profile written into the environment while a graph runs cannot arm a node
        // that already refused.
        Optional<LlmProfile> resolved = profiles.resolve(profileName);
        if (resolved.isEmpty()) {
            return refuse(AgentException.Code.PROFILE_UNKNOWN, profileName);
        }
        // Resolved here, once, and for the same reason the model profile is: an execution's
        // capability set is fixed at admission, so a server written into the environment while a
        // graph runs cannot arm a node that already refused -- and one REMOVED from the environment
        // cannot silently narrow an agent that is mid-run.
        List<String> declared = mcpNames(configuration);
        if (declared.size() > MAX_MCP_SERVERS) {
            // Checked, and not merely documented. The descriptor tells a graph author "at most eight"
            // and the guide repeats it; a bound that only three sentences believe in is not a bound,
            // and the ninth server would be opened by an author who had been told it would not be.
            return refuse(AgentException.Code.MCP_TOO_MANY_SERVERS, "");
        }
        var servers = new ArrayList<McpProfile>();
        for (String serverName : declared) {
            Optional<McpProfile> server = mcpProfiles.resolve(serverName);
            if (server.isEmpty()) {
                // Includes a profile that exists but is malformed, and a profile whose credential
                // sits on a plaintext origin -- McpProfile refuses that at construction, which is
                // what "rejected when the profile is read" means: the operator sees it against the
                // server they named, before any byte leaves.
                return refuse(AgentException.Code.MCP_PROFILE_UNKNOWN,
                        serverName.matches(MCP_NAME_PATTERN) ? serverName : "");
            }
            servers.add(server.get());
        }
        Settings settings = Settings.compile(configuration, resolved.get(), skills, List.copyOf(servers));
        return message -> invoke(message, services, settings);
    }

    /**
     * The server names an author wrote, in order, without duplicates.
     *
     * <p>A duplicate is collapsed rather than refused: naming one server twice is a typo with an
     * unambiguous meaning, and it would otherwise become an exposed-name collision -- a refusal whose
     * message would send the operator looking for two servers when there is one.</p>
     *
     * <p>More than {@link #MAX_MCP_SERVERS} is refused by {@link #create}, and is <b>not</b> silently
     * truncated. Truncation would mean an agent quietly running without tools its author declared,
     * which is the shape of failure that is discovered in production. This method only collects; the
     * count is checked where the refusal can be a named failure.</p>
     */
    private static List<String> mcpNames(NodeConfiguration configuration) {
        String raw = configuration.property("mcpServers", "").strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        var names = new LinkedHashSet<String>();
        for (String token : raw.split("[,\\s]+")) {
            String name = token.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static NodeAction refuse(AgentException.Code code, String hint) {
        return message -> CompletableFuture.failedFuture(new AgentException(code, hint));
    }

    /** Everything derived once per node. Immutable, because one action serves concurrent traversals. */
    record Settings(LlmProfile profile, String instructions, String objective, String model,
                    int deadlineMs, int maxTurns, long maxTotalTokens,
                    OpenAiCompatibleChat.Tuning tuning, List<AgentSkill> skills,
                    List<McpProfile> mcpServers) {

        Settings {
            skills = List.copyOf(skills);
            mcpServers = List.copyOf(mcpServers);
        }

        /**
         * @param skills already read and validated by the caller, before the provider was resolved,
         *     so that a defective declaration refuses a graph whose provider is not configured yet
         * @param mcpServers likewise resolved by the caller, so that a server this deployment did not
         *     declare refuses the node with its own code rather than at the first turn
         */
        static Settings compile(NodeConfiguration configuration, LlmProfile profile,
                                List<AgentSkill> skills, List<McpProfile> mcpServers) {
            String instructions = configuration.requiredProperty("instructions");
            String objective = configuration.requiredProperty("objective");
            String model = configuration.property("model", "").strip();
            // The profile's deadline bounds the WHOLE run here, where for llm-prompt it bounds one
            // call. That is the deliberate reading: a loop of n turns under a per-turn ceiling has no
            // ceiling at all, and the operator's number has to stay a number they can reason about.
            int requestedDeadline = positiveInt(configuration, "timeoutMs").orElse(profile.timeoutMs());
            int maxTurns = positiveInt(configuration, "maxTurns").orElse(DEFAULT_MAX_TURNS);
            long maxTotalTokens = positiveLong(configuration, "maxTotalTokens").orElse(UNBOUNDED_TOKENS);
            var tuning = new OpenAiCompatibleChat.Tuning(
                    positiveLong(configuration, "maxTokens"),
                    finiteDouble(configuration, "temperature"),
                    finiteDouble(configuration, "topP"),
                    longValue(configuration, "seed"));
            return new Settings(profile, instructions, objective,
                    model.isEmpty() ? profile.model() : model,
                    Math.max(1, Math.min(profile.timeoutMs(), requestedDeadline)),
                    Math.max(1, Math.min(MAX_TURNS_CEILING, maxTurns)),
                    maxTotalTokens, tuning, skills, mcpServers);
        }

        // GraphML properties arrive as text, so a declared value may be a Number or a String. A
        // malformed one is OMITTED rather than thrown on: the value is graph content, and a
        // credential-adjacent execution path is not where attacker-influenced text should be able to
        // raise a new exception type. Carried over from the sibling node, which carried it from the
        // adapter's own parameter reader.
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

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services,
                                               Settings settings) {
        // The key pairs the tenant with the profile, and the separator is a character neither can
        // contain: a profile name is masked to [A-Za-z0-9._-] before it is ever resolved.
        Admission.Lease lease = profileAdmission.tryAcquire(
                message.tenantId() + " " + settings.profile().name(), settings.profile().maxConcurrency());
        if (lease == null) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.Code.CAPACITY_UNAVAILABLE));
        }
        // One more lease per declared MCP server, taken before anything is opened. Taking them all up
        // front and failing whole is what keeps the counters honest: a run that acquired three of four
        // and then gave up would have to unwind them on a path that does not otherwise exist, and that
        // path is where a leak lives in every implementation that has one.
        var leases = new ArrayList<Admission.Lease>(1 + settings.mcpServers().size());
        leases.add(lease);
        for (McpProfile server : settings.mcpServers()) {
            Admission.Lease held = mcpAdmission.tryAcquire(
                    message.tenantId() + " " + server.name(), server.maxConcurrency());
            if (held == null) {
                leases.forEach(Admission.Lease::close);
                return CompletableFuture.failedFuture(
                        new AgentException(AgentException.Code.CAPACITY_UNAVAILABLE));
            }
            leases.add(held);
        }
        Run run;
        try {
            run = new Run(message, services, settings);
        } catch (RuntimeException failure) {
            leases.forEach(Admission.Lease::close);
            return CompletableFuture.failedFuture(sanitize(failure));
        }
        var result = new CompletableFuture<NodeResult>();
        // ONE callback holding both actions, in this order, and not two callbacks written in this
        // order. CompletableFuture dispatches its dependents LIFO -- they sit on a stack and
        // postComplete pops from the head -- so two registrations run last-registered-first, and an
        // earlier version of these lines released the admission BEFORE stopping the work while a
        // comment above them claimed the opposite. Measured, not reasoned about.
        //
        // The order inside the callback is the one that matters: stopping first means that by the
        // time the admission is given back there is nothing left running under it, and a counter that
        // returned to zero while the loop was still calling tools would make an operator's
        // maxConcurrency describe something other than the work in flight.
        //
        // The leases are held until the LAST turn finishes, not until the first returns -- and, with
        // MCP servers declared, until after discovery, which happens before the first turn. Every
        // lease is idempotent, so a run that fails between two turns releases here exactly once.
        result.whenComplete((ignored, alsoIgnored) -> {
            try {
                run.abort();
            } finally {
                // The finally is the whole point, and it was learned the expensive way. Fusing the
                // two actions into one callback bought the ordering and lost something the two
                // separate registrations had for free: independence. OutboundCall.cancel() is a
                // runtime implementation and its contract does not promise it will not throw -- this
                // file guards seven other crossings into the runtime for exactly that reason -- and
                // without this finally, one throw there skips the release entirely. That is not a
                // lost nanosecond: Admission.Gate is reference-counted and only leaves the map at
                // zero, so the (tenant, profile) key stays poisoned for the life of the process.
                for (Admission.Lease held : leases) {
                    // One at a time and not forEach, so that a lease refusing to close cannot take
                    // the ones after it down with it. Admission.releaseReference raises on a broken
                    // invariant, which is deliberate and stays -- what changes is that it costs one
                    // lease instead of all of them.
                    try {
                        held.close();
                    } catch (RuntimeException broken) {
                        // Deliberately swallowed HERE and nowhere else in this class: this is the
                        // terminal callback of a run that has already completed, so there is nothing
                        // left to fail and no caller to tell. Rethrowing would only lose the
                        // remaining leases.
                        continue;
                    }
                }
            }
        });
        run.start(result);
        return cancellableView(result);
    }

    /**
     * The stage handed to the caller: everything {@code run} completes, plus cancellation that
     * actually reaches the run.
     *
     * <h2>The cancellation defect this closes</h2>
     * <p>Returning {@code result.whenComplete(...)} — which is what this method replaces — hands back
     * a <em>derived</em> future. Cancelling a derived future completes that one and leaves its source
     * running, so the release registered on the source never fired: a cancelled run went on holding
     * its {@code (tenant, profile)} admission until the in-flight HTTP call resolved on its own
     * deadline. Nothing detected it, because the only test that named cancellation cancelled a future
     * that had already completed, where cancel is a no-op.</p>
     *
     * <p>Returning the source directly would fix the cancellation and give the caller the power to
     * {@code complete()} the node's result with a value no model produced — which is the one thing
     * this node's provenance marking must never allow. So the view is a future that forwards
     * cancellation inwards and takes its value only from the run.</p>
     */
    private static CompletionStage<NodeResult> cancellableView(CompletableFuture<NodeResult> run) {
        var exposed = new CompletableFuture<NodeResult>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                // This future first and the run second, so the return value does not depend on a
                // subtlety of CompletableFuture. It was reported that the other order returns false
                // on a cancellation that worked; MEASURED, it does not -- cancel() answers
                // "cancelled || isCancelled()", and the exception the forwarding below propagates is
                // itself a CancellationException, so the already-completed future still reports
                // true. The order here is the one that stays correct without relying on that.
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                run.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        run.whenComplete((value, failure) -> {
            if (failure != null) {
                exposed.completeExceptionally(failure);
            } else {
                exposed.complete(value);
            }
        });
        return exposed;
    }

    /**
     * One invocation's mutable state: the conversation, the counters and the deadline.
     *
     * <p>Per invocation and never per node, which is the rule ADR 0024 §3 states for everything on
     * this path: several traversals run through one action at the same time, so conversation state
     * kept on the behavior would be shared between two agents that must not see each other.</p>
     */
    private final class Run {
        private final NodeMessage message;
        private final NodePackageServices services;
        private final Settings settings;
        /**
         * The built-in tool, plus whatever MCP discovery adds before the first turn.
         *
         * <p>Per invocation and not per node, and that is not incidental: {@link LoadSkillTool}
         * remembers which bodies it has already handed over, and one shared instance would make one
         * agent's first request answer with another agent's second. {@link #loadSkill} holds that one
         * instance so discovery re-uses it rather than building a second with an empty memory.</p>
         *
         * <p>Not final, and written exactly once — in {@link #start} — before any turn is sent and
         * therefore before any thread but the starting one can read it. Volatile because that
         * write and the reads that follow it are separated by the managed channel's completion
         * callbacks, which run on threads this bundle does not own.</p>
         */
        private volatile List<AgentTool> tools;
        /** The one {@link LoadSkillTool} of this invocation, kept so discovery cannot replace it. */
        private final AgentTool loadSkill;
        private final List<PayloadValue> messages = new ArrayList<>();
        private final ModelInputProvenance provenance = new ModelInputProvenance();
        /**
         * The model call currently in flight, so a cancelled run can actually stop.
         *
         * <p>Cancelling the stage this node returned used to complete a future and nothing else: the
         * outbound call went on, its answer arrived, and the loop carried on from there -- calling
         * MCP tools, which are remote side effects, and opening another model turn, all after the
         * caller had said stop and after the admission had been given back.</p>
         */
        private volatile OutboundCall<OutboundHttpResponse> inFlight;
        /** Set once the run is over, by any route. Read before every step the loop would take next. */
        private volatile boolean over;
        private final long deadlineNanos;
        private int turns;
        private int toolCalls;
        private long tokens;
        private String finishReason = "";

        Run(NodeMessage message, NodePackageServices services, Settings settings) {
            this.message = message;
            this.services = services;
            this.settings = settings;
            this.loadSkill = new LoadSkillTool(settings.skills());
            this.tools = List.of(loadSkill);
            this.deadlineNanos = System.nanoTime()
                    + Duration.ofMillis(settings.deadlineMs()).toNanos();
            // Rendered once, here, and inside the try of the caller: a template that cannot render is
            // a property defect and must refuse before any byte leaves.
            String instructions = render(settings.instructions());
            String objective = render(settings.objective());
            provenance.add(ModelInputProvenance.Kind.GRAPH_INSTRUCTIONS,
                    "node:" + message.nodeId(), instructions);
            provenance.add(ModelInputProvenance.Kind.GRAPH_OBJECTIVE,
                    "node:" + message.nodeId(), objective);
            provenance.add(ModelInputProvenance.Kind.INBOUND_PAYLOAD,
                    "invocation:" + message.invocationId(), message.payload());
            provenance.add(ModelInputProvenance.Kind.INBOUND_ATTRIBUTES,
                    "invocation:" + message.invocationId(), message.attributes());
            provenance.add(ModelInputProvenance.Kind.TOOL_DESCRIPTION, loadSkill.name(),
                    Map.of("description", loadSkill.description(),
                            "parameters", loadSkill.parameters().toJava()));
            for (AgentSkill skill : settings.skills()) {
                provenance.add(ModelInputProvenance.Kind.TOOL_DESCRIPTION,
                        LoadSkillTool.NAME,
                        Map.of("name", skill.name(), "description", skill.description()));
            }
            PayloadValue systemMessage = AgentTurn.systemMessage(settings.profile().systemPreamble());
            PayloadValue authorMessage = AgentTurn.authorInstructionsMessage(instructions, settings.skills());
            provenance.add(ModelInputProvenance.Kind.GENERATED_SYSTEM_MESSAGE,
                    "agent-system", systemMessage.toJava());
            provenance.add(ModelInputProvenance.Kind.GENERATED_AUTHOR_MESSAGE,
                    "agent-author", authorMessage.toJava());
            messages.add(systemMessage);
            messages.add(authorMessage);
            messages.add(AgentTurn.userMessage(objective));
        }

        private String render(String template) {
            return PromptTemplate.render(template, message.payload(), message.attributes(), Map.of());
        }

        /**
         * Discovers the declared MCP servers, then runs the loop.
         *
         * <p>Discovery is before the first turn and cannot be anywhere else: the tool list is part of
         * the first request, so a model cannot be told about a tool discovered later. That ordering is
         * also what makes a discovery failure a <b>node failure</b> rather than a tool message —
         * nothing has been promised to the model yet, so there is no loop to keep alive, and an agent
         * missing the tools its author declared is not an agent that should quietly answer anyway.</p>
         *
         * <p>With no servers declared this is the previous behaviour exactly: one synchronous call
         * into {@link #step}, no extra stage, no extra allocation.</p>
         */
        void start(CompletableFuture<NodeResult> result) {
            if (settings.mcpServers().isEmpty()) {
                step(result);
                return;
            }
            // A run that is already over has no time left, by definition. Expressing cancellation as
            // an exhausted deadline is what lets one check inside McpSession cover both: an exchange
            // it has not started yet is refused, and the discovery chain unwinds instead of opening
            // the next server.
            LongSupplier remaining = () -> over || result.isDone() ? 0 : remainingMillis();
            McpToolset.discover(settings.mcpServers(), services, message, remaining)
                    .whenComplete((discovered, failure) -> {
                        if (over || result.isDone()) {
                            return;
                        }
                        if (failure != null) {
                            result.completeExceptionally(sanitize(failure));
                            return;
                        }
                        try {
                            var all = new ArrayList<AgentTool>(discovered.size() + 1);
                            // The built-in first, so a model reading the list top-down meets the tool
                            // that needs no third party before the ones that do.
                            //
                            // The SAME instance the constructor built. Measured, and the measurement
                            // is worth recording because it contradicts the obvious reason: building
                            // a fresh LoadSkillTool here loses NOTHING today, and the suite stays
                            // green if you do. It cannot lose anything because discovery runs before
                            // the first model turn, so no skill can have been loaded yet and the
                            // memory being discarded is always empty. What reusing it buys is
                            // independence from that ordering -- the rule that loading a skill twice does
                            // not duplicate it" lives in this object's memory, and a second instance
                            // would make that rule depend on nobody ever calling a tool before
                            // discovery. Nothing in the suite would catch it if that changed.
                            all.add(loadSkill);
                            all.addAll(discovered);
                            tools = List.copyOf(all);
                            for (AgentTool tool : discovered) {
                                provenance.add(ModelInputProvenance.Kind.TOOL_DESCRIPTION,
                                        tool.name(), Map.of("description", tool.description(),
                                                "parameters", tool.parameters().toJava()));
                            }
                            step(result);
                        } catch (RuntimeException invalid) {
                            result.completeExceptionally(sanitize(invalid));
                        }
                    });
        }

        void step(CompletableFuture<NodeResult> result) {
            if (over || result.isDone()) {
                // One of the checks that stop a cancelled run, and a note on what the tests pin,
                // because a green suite is misleading here. THREE of them defend the property that
                // matters -- a cancelled run makes no further remote call: the LongSupplier handed to
                // McpToolset.discover, which McpSession re-reads before every exchange; the check
                // inside the response callback below; and the one at the entry of runTools. Remove
                // any ONE and the suite stays green, because the other two cover it; remove all three
                // and one MCP call escapes; remove every check and the loop runs to its turn budget
                // calling the tool each time. This check and the one after "inFlight = call" stop the
                // next turn rather than the next remote call, so they are the margin, not the floor.
                // What is individually necessary is run.abort(): drop its registration alone and
                // aCancelledRunStopsDoingWork goes red. Do not read a green suite as licence to
                // delete one of these.
                return;
            }
            long remaining = remainingMillis();
            if (remaining <= 0) {
                result.completeExceptionally(new AgentException(AgentException.Code.DEADLINE_EXCEEDED));
                return;
            }
            if (turns >= settings.maxTurns()) {
                // Reached only when the previous turn asked for a tool: a turn that answered has
                // already completed the result. So this code says what it names -- the model was
                // still working when its allowance ran out.
                result.completeExceptionally(
                        new AgentException(AgentException.Code.TURN_BUDGET_EXHAUSTED));
                return;
            }
            turns++;
            OutboundCall<OutboundHttpResponse> call;
            try {
                byte[] body = AgentTurn.writeRequest(settings.model(), messages, tools, settings.tuning());
                call = services.outboundHttp().execute(message, new OutboundHttpRequest(
                        settings.profile().endpoint(), "POST",
                        Map.of("content-type", List.of("application/json")), body,
                        Duration.ofMillis(remaining),
                        settings.profile().credentialBinding().orElse(null)));
            } catch (RuntimeException failure) {
                result.completeExceptionally(sanitize(failure));
                return;
            }
            inFlight = call;
            if (over || result.isDone()) {
                // Cancellation can land between the guard above and this line. Cancelling here rather
                // than trusting the ordering is the difference between a narrow window and none.
                call.cancel();
                return;
            }
            call.completion().whenComplete((response, failure) -> {
                if (over || result.isDone()) {
                    return;
                }
                try {
                    if (failure != null) {
                        result.completeExceptionally(sanitize(failure));
                    } else {
                        advance(response, result);
                    }
                } catch (RuntimeException invalid) {
                    result.completeExceptionally(sanitize(invalid));
                }
            });
        }

        private void advance(OutboundHttpResponse response, CompletableFuture<NodeResult> result) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // The body is NOT read into the failure. An endpoint's error document is remote text
                // and may quote the objective back; the status is the operator-actionable part.
                throw new AgentException(AgentException.Code.ENDPOINT_REJECTED);
            }
            AgentTurn.Turn turn = AgentTurn.read(response.body(), settings.profile().maxResponseBytes());
            var modelOutput = new LinkedHashMap<String, Object>();
            modelOutput.put("answer", turn.answer());
            var requestedTools = new ArrayList<Map<String, Object>>(turn.toolCalls().size());
            for (AgentTurn.ToolCall call : turn.toolCalls()) {
                requestedTools.add(Map.of("id", call.id(), "name", call.name(),
                        "arguments", call.arguments()));
            }
            modelOutput.put("toolCalls", List.copyOf(requestedTools));
            modelOutput.put("finishReason", turn.finishReason());
            turn.promptTokens().ifPresent(value -> modelOutput.put("promptTokens", value));
            turn.completionTokens().ifPresent(value -> modelOutput.put("completionTokens", value));
            provenance.add(ModelInputProvenance.Kind.MODEL_OUTPUT, "turn:" + turns,
                    Map.copyOf(modelOutput));
            finishReason = turn.finishReason();
            // Prompt AND completion tokens, summed per turn. That over-counts against a conversation
            // measured once, and it is the right number anyway: an endpoint re-reads the whole
            // conversation on every turn and bills for it, so this is what the run costs. A budget
            // that counted only new tokens would let a long conversation run far past what an
            // operator thought they had allowed.
            turn.promptTokens().ifPresent(count -> tokens += count);
            turn.completionTokens().ifPresent(count -> tokens += count);
            if (settings.maxTotalTokens() > UNBOUNDED_TOKENS && tokens > settings.maxTotalTokens()) {
                throw new AgentException(AgentException.Code.TOKEN_BUDGET_EXHAUSTED);
            }
            if (turn.answered()) {
                result.complete(answer(turn));
                return;
            }
            messages.add(AgentTurn.assistantToolCallMessage(turn.answer(), turn.toolCalls()));
            runTools(turn.toolCalls(), 0, result);
        }

        /**
         * Runs the turn's tool calls one after another, then takes the next turn.
         *
         * <p>Sequential and not concurrent, which is a deliberate choice and not an omission. A model
         * asking for three tools in one turn has not said they are independent, and running them at
         * once would (a) let a later call observe a side effect of an earlier one in an order nothing
         * chose, and (b) multiply the concurrency an operator's {@code maxConcurrency} was meant to
         * bound. It also keeps {@link #messages} written by one thread at a time, which is what makes
         * an {@link ArrayList} correct here.</p>
         *
         * <p>Written as a chain rather than a loop because a tool may now answer asynchronously; see
         * {@link AgentTool#invoke(String)}. The recursion is bounded by the number of tool calls in
         * one turn and, through {@link #step}, by {@code maxTurns}.</p>
         */
        private void runTools(List<AgentTurn.ToolCall> requested, int index,
                              CompletableFuture<NodeResult> result) {
            if (over || result.isDone()) {
                // Checked before EACH call and not once per turn: a turn asking for three tools must
                // not send the second and third after the run was cancelled during the first.
                return;
            }
            if (index >= requested.size()) {
                step(result);
                return;
            }
            AgentTurn.ToolCall call = requested.get(index);
            toolCalls++;
            CompletionStage<String> answering;
            try {
                answering = run(call);
            } catch (RuntimeException broken) {
                answering = CompletableFuture.completedFuture(TOOL_FAILED);
            }
            answering.whenComplete((text, failure) -> {
                if (over || result.isDone()) {
                    return;
                }
                try {
                    // A failed stage is a tool that broke its own contract. It costs a turn and not a
                    // traversal, for the reason on AgentTool: a defect in one tool must not be able to
                    // terminate an execution the model could still finish another way. Nothing of the
                    // throwable reaches the message -- a tool's internals are not content the model
                    // was promised.
                    messages.add(AgentTurn.toolResultMessage(call.id(),
                            failure == null && text != null && !text.isEmpty() ? text : TOOL_FAILED));
                    provenance.add(ModelInputProvenance.Kind.TOOL_RESULT, "tool-call:" + (index + 1),
                            failure == null && text != null && !text.isEmpty() ? text : TOOL_FAILED);
                    runTools(requested, index + 1, result);
                } catch (RuntimeException invalid) {
                    result.completeExceptionally(sanitize(invalid));
                }
            });
        }

        /**
         * Runs one tool call and always produces a message the model can read.
         *
         * <p>An unknown name is answered, not refused: a model that invented a tool has to be able to
         * see that it did and pick a real one. The turn budget is what stops a model that cannot.
         * A tool that throws in spite of {@link AgentTool}'s contract is treated the same way — a bug
         * in a tool should cost a turn, not a traversal — and nothing of the throwable reaches the
         * message, because a tool's internals are not content the model was promised.</p>
         */
        private CompletionStage<String> run(AgentTurn.ToolCall requested) {
            AgentTool selected = null;
            for (AgentTool tool : tools) {
                if (tool.name().equals(requested.name())) {
                    selected = tool;
                    break;
                }
            }
            // An invented name is still authorized and audited, but its attacker-controlled spelling
            // is neither a policy input nor durable evidence. The fixed token states the only trusted
            // fact about it: it was absent from this invocation's immutable inventory.
            String canonicalTool = selected == null ? "unavailable-tool" : selected.name();
            AgentTool authorizedTool = selected;
            ToolCallAuthorization authorization;
            try {
                authorization = services.toolAuthorization().authorize(message, canonicalTool,
                        requested.arguments().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException unavailable) {
                return CompletableFuture.failedFuture(new AgentException(
                        AgentException.Code.TRANSPORT_UNAVAILABLE));
            }
            if (authorization.disposition() == ToolCallAuthorization.Disposition.DENY) {
                return CompletableFuture.completedFuture(
                        "The server denied this tool call. It performed no effect.");
            }
            if (authorization.disposition() == ToolCallAuthorization.Disposition.REQUIRE_APPROVAL) {
                return CompletableFuture.completedFuture(
                        "This tool call requires approval. It performed no effect.");
            }
            String canonicalArguments = new String(authorization.canonicalArguments(),
                    StandardCharsets.UTF_8);
            if (authorizedTool != null) {
                try {
                    CompletionStage<AgentTool.Result> effect = authorizedTool.invoke(canonicalArguments);
                    return effect.handle((toolResult, failure) -> {
                        boolean succeeded = failure == null && toolResult != null
                                && toolResult.succeeded();
                        authorization.complete(succeeded
                                ? ToolCallAuthorization.Outcome.SUCCEEDED
                                : ToolCallAuthorization.Outcome.FAILED);
                        return failure == null && toolResult != null
                                ? toolResult.text()
                                : TOOL_FAILED;
                    });
                } catch (RuntimeException broken) {
                    authorization.complete(ToolCallAuthorization.Outcome.FAILED);
                    return CompletableFuture.completedFuture(TOOL_FAILED);
                }
            }
            authorization.complete(ToolCallAuthorization.Outcome.FAILED);
            // Reached by every name the model invented, including one that names a real tool on a
            // real server the operator did not permit: such a tool was never placed in the list, so
            // there is nothing here to match, and the model is told what it may call instead.
            return CompletableFuture.completedFuture(
                    "No tool by that name is available to you. The tools you may call are listed in "
                            + "this request; call one of those or answer without tools.");
        }

        private NodeResult answer(AgentTurn.Turn turn) {
            var attributes = new LinkedHashMap<String, Object>(message.attributes());
            // The model this bundle ASKED for, never the one the response reports: an endpoint
            // resolves a name to whatever tag it currently has installed and reports that back, so
            // echoing it would persist unaudited remote text under a Ravenroot-looking name.
            attributes.put("agent.provider", settings.profile().name());
            attributes.put("agent.model", settings.model());
            attributes.put("agent.turns", turns);
            attributes.put("agent.toolCalls", toolCalls);
            attributes.put("agent.finishReason", finishReason);
            attributes.put("agent.truncated", turn.truncated());
            attributes.put(ModelInputProvenance.AGENT_ATTRIBUTE, provenance.snapshot());
            if (tokens > 0) {
                attributes.put("agent.totalTokens", tokens);
            }
            return new NodeResult("continue", turn.answer(), Map.copyOf(attributes));
        }

        private long remainingMillis() {
            return Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis();
        }

        /**
         * Stops this run: no further turn, no further tool call, and the call in flight cancelled.
         *
         * <p>Called on every exit and not only on cancellation, because "the run is over" is the same
         * fact whether it ended in an answer, a refusal or a cancellation, and a flag that is only set
         * on one of the three is a flag that is wrong on the other two.</p>
         *
         * <p>What it cannot reach is an MCP exchange already handed to the managed channel: that one
         * is bounded by its own deadline and by the profile's, and — this is the part that matters —
         * it cannot lead to another, because every continuation above reads {@link #over} first.</p>
         */
        void abort() {
            over = true;
            OutboundCall<OutboundHttpResponse> current = inFlight;
            if (current != null) {
                current.cancel();
            }
        }
    }

    /**
     * Every failure leaves as an {@link AgentException} carrying a closed code.
     *
     * <p>{@link NodePackageServiceException} is already sanitized by the managed channel; mapping it
     * here keeps this node's vocabulary the only one a graph author or an execution record sees.
     * {@link LlmPromptException} appears on exactly one path — {@link PromptTemplate}, shared with
     * the sibling node — and is translated rather than let through, so a reader of an agent failure
     * never has to know that another node's vocabulary exists.</p>
     */
    private static RuntimeException sanitize(Throwable raw) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof AgentException known) {
            return known;
        }
        // Reachable only from discovery: inside the loop an McpRefusal never travels as a throwable,
        // it becomes a tool message. See McpRefusal for why the same condition has two outcomes.
        if (failure instanceof McpRefusal refused) {
            return new AgentException(refused.asNodeFailure());
        }
        if (failure instanceof LlmPromptException shared) {
            return new AgentException(
                    shared.code() == LlmPromptException.Code.PROMPT_UNRENDERABLE
                            ? AgentException.Code.TEMPLATE_UNRENDERABLE
                            : AgentException.Code.TRANSPORT_UNAVAILABLE,
                    shared.hint());
        }
        if (failure instanceof CancellationException) {
            return new AgentException(AgentException.Code.DEADLINE_EXCEEDED);
        }
        if (failure instanceof NodePackageServiceException service) {
            return new AgentException(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> AgentException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED, TLS_REFUSED ->
                        AgentException.Code.DESTINATION_REFUSED;
                case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> AgentException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> AgentException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> AgentException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED -> AgentException.Code.TRANSPORT_UNAVAILABLE;
            });
        }
        return new AgentException(AgentException.Code.TRANSPORT_UNAVAILABLE);
    }

    /**
     * Test seam: how many (tenant, profile) admission entries are held right now.
     *
     * <p>Zero when nothing is in flight, which is the property worth pinning here more than on the
     * sibling node: this one holds its lease across an unknown number of turns, so a path that
     * forgets to release leaks for the whole run rather than for one call.</p>
     */
    int admissionEntries() {
        return profileAdmission.size();
    }

    /**
     * Test seam: how many (tenant, MCP server) admission entries are held right now.
     *
     * <p>Separate from {@link #admissionEntries()} because the two registries are separate, and a
     * single number would hide the leak worth pinning: an MCP lease is taken before discovery and
     * released after the last turn, so it spans strictly more of the run than the model profile's
     * does and has strictly more paths on which it can be forgotten.</p>
     */
    int mcpAdmissionEntries() {
        return mcpAdmission.size();
    }
}
