package ai.ravenroot.devharness;

import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code llm-prompt} behavior, supplied by this bench because the core no longer supplies it.
 *
 * <h1>Why this class exists here</h1>
 * <p>The core does not ship AI node behaviors. This source-only bench must therefore supply the node
 * that consumes its {@link ModelProviderRegistry}; otherwise it could start successfully while the
 * capability it exists to exercise remained absent from the catalogue.</p>
 *
 * <p><b>This is not a second implementation competing with the bundle's.</b> A bundle node reaches a
 * model through the managed HTTP channel and implements {@code NodeBehavior}; it does not implement
 * {@code ModelProvider} and does not pass through {@link ModelProviderRegistry}. This class is the
 * <em>embedding</em> route and is executable evidence that the route still works.</p>
 *
 * <h1>The discipline the refusal is held to. Read before simplifying any of it.</h1>
 * <p>The core's {@code UnconfiguredAdapterRefusal} left with the two nodes. What it documented is a
 * property of the refusal rather than of that class, so it is restated here in full instead of being
 * summarised away — a bench is exactly where a control gets quietly relaxed because "it is only the
 * bench".</p>
 * <ol>
 *   <li><b>Never a {@link NodeResult}, for any outcome.</b> {@code GraphRunner} calls
 *   {@code markSyntheticProvenance} on every <em>successful</em> node completion, and derives the
 *   marker from the registered catalog descriptor rather than from what the node actually did. This
 *   descriptor declares capability {@code "ai"}, which is in
 *   {@code SyntheticProvenance.GENERATIVE_CAPABILITIES}, so any successful result returned from a
 *   refusal would stamp {@code ravenroot.provenance.synthetic} onto the payload — a machine-readable
 *   assertion that a model generated content no model produced. {@code SyntheticProvenance} states
 *   the rule this breaks in its own design record: a missing marker is a stated limitation, a wrong
 *   marker is a false statement about the world. Failing is what avoids it, and only failing does: on
 *   the error branch the runner goes to {@code nodeFailed} and never reaches the marking step.</li>
 *   <li><b>Exceptionally, but never by throwing.</b> {@code GraphRunner} invokes the handler inside
 *   {@code RavenNode#onMessage} and calls {@code engine.send(...)} uncaught, while the traversal's
 *   terminal bookkeeping lives inside the returned stage. A synchronous throw escapes
 *   {@code execute()} entirely and leaves the traversal non-terminal with no failure event recorded —
 *   and whether it does so depends on which engine adapter is installed, since the Pekko adapter
 *   converts such a throw into a failed future and a direct-call engine does not. Always a failed
 *   future.</li>
 *   <li><b>No SPI is touched on the refusal path.</b> {@link ToolPolicy} is integrator-supplied, so
 *   evaluating it calls arbitrary third-party code that may reach a network decision point and may
 *   answer {@code REQUIRE_APPROVAL} with an approval id — that is, evaluation can create a
 *   human-approval work item as a side effect. A graph author who cannot invoke anything must not be
 *   able to generate approval requests by naming providers that do not exist. The registry is not
 *   re-read either: the lookup happens once, in {@link #create}, so an execution's capability set is
 *   fixed at admission and a registration made while it runs cannot arm a node it already refused.
 *   That freeze is per execution, not per graph document — the runner is rebuilt on every
 *   submission.</li>
 * </ol>
 */
public final class LlmPromptNodeBehaviorFactory implements NodeBehaviorFactory {

    private static final System.Logger LOGGER = System.getLogger("ai.ravenroot.node.refused");

    /**
     * Matches {@code {{payload}}} and {@code {{payload.a.b}}} / {@code {{payload.items.0}}}.
     *
     * <p>A reduced copy of the core's package-private {@code NodeProperties} rendering, carried across
     * because that class is not public API and this module is not in the core's package. Reduced
     * deliberately to the token families an {@code llm-prompt} prompt actually uses; the numeric and
     * boolean readers are not carried, because a copy of code nothing calls is the part that rots
     * unnoticed. <b>The known limitation, stated rather than discovered:</b> this is a copy, so a
     * later change to the core's rendering does not reach it. That is acceptable only because this
     * module is never published and is not in the reactor — it is a real cost of retention, not a
     * detail.</p>
     */
    private static final Pattern PAYLOAD_TOKEN = Pattern.compile("\\{\\{payload(\\.[^{}]*)?}}");

    private final ModelProviderRegistry providers;
    private final ToolPolicy toolPolicy;

    public LlmPromptNodeBehaviorFactory(ModelProviderRegistry providers, ToolPolicy toolPolicy) {
        this.providers = java.util.Objects.requireNonNull(providers, "providers");
        this.toolPolicy = java.util.Objects.requireNonNull(toolPolicy, "toolPolicy");
    }

    /**
     * The descriptor the core used to publish, carried over verbatim in the parts that matter.
     *
     * <p>{@code Set.of("ai", ...)} is not decoration: it is what makes {@code SyntheticProvenance}
     * mark this node's output, without the runtime knowing anything about this class. That mechanism
     * is defined in the core so that a node supplied from outside — this one, an embedder's, or a
     * bundle's — is marked identically.</p>
     */
    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("llm-prompt", "LLM prompt", "AI",
                "Uses a configured model provider to transform the incoming payload according to a "
                        + "prompt. Supplied by ravenroot-dev-harness, which is never published: this "
                        + "node is not in the shipped catalogue.",
                "agent", false, List.of(
                // An adapter binding, not a plain required property. Leaving it blank makes this an
                // unconfigured node rather than a defective graph (CORE-07), and the emptiness test
                // must stay adapterIdOf(...) -- the same call BehaviorPropertySchema uses to grant
                // the exemption. Respelling it as isBlank(), trim() or strip() would make the schema
                // and runtime judge some values differently: a value is admitted by the schema and
                // not refused here, and the node then neither validates nor refuses.
                NodePropertyDescriptor.adapterId("provider", "Provider", NodePropertyType.STRING,
                        "ID of a model provider this bench registered from its environment."),
                NodePropertyDescriptor.optional("model", "Model", NodePropertyType.STRING,
                        "Provider-specific model override.", ""),
                NodePropertyDescriptor.required("prompt", "Prompt", NodePropertyType.TEXT,
                        "Instruction template. Supports {{payload}}."),
                NodePropertyDescriptor.optional("credentialRef", "Credential reference",
                        NodePropertyType.SECRET_REFERENCE, "Opaque reference resolved by the provider adapter.", "")),
                Set.of("ai", "external-provider", "credential-reference"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The provider returned a completion, which becomes the outgoing payload. The "
                                + "only outcome this node produces: a provider error fails the node."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        // The adapter id is read FIRST, before any other required property. Otherwise a node with
        // neither provider nor prompt would still fail on the prompt, and the CORE-07 reversal would
        // only appear to work.
        String providerId = NodePropertyDescriptor.adapterIdOf(node.properties().get("provider"));
        if (providerId.isEmpty()) {
            // The "for id" clause is dropped rather than filled with a placeholder: reusing the
            // id-carrying wording with an empty id renders "... configured for id ''", which reads as
            // though the author deliberately wrote an empty string.
            return refuse(node, "Node " + node.id() + " cannot run: no model provider is configured");
        }
        // Naming an adapter is the author asserting the node is meant to be configured. From here the
        // node is held to the full schema, so these reads stay eager.
        String prompt = required(node, "prompt");
        String model = string(node, "model", "");
        // Verbatim: see NodePropertyType.SECRET_REFERENCE. Trimming maps three distinct references
        // onto one key.
        String credentialRef = string(node, "credentialRef", "");
        Map<String, Object> parameters = prefixed(node, "parameter.");
        // Resolved exactly once, here, and captured. See rule 3 on this class.
        var configured = providers.find(providerId);
        if (configured.isEmpty()) {
            return refuse(node, "Node " + node.id() + " cannot run: no model provider is configured for id '"
                    + providerId + "'");
        }
        var provider = configured.get();
        return message -> {
            requireToolAllowed(message, "model.generate", Map.of("provider", providerId));
            var request = new ModelRequest(message.executionId(), node.id(),
                    render(prompt, message, node), message.payload(), model, credentialRef, parameters);
            return provider.generate(request).thenApply(response -> new NodeResult("continue", response.payload(),
                    attributes(message, "llm.provider", response.providerId(), "llm.model", response.model())));
        };
    }

    // ------------------------------------------------------------------ refusal

    private static NodeHandler refuse(GraphNode node, String failure) {
        return message -> {
            // Identifiers only. No payload, no rendered prompt, no credential reference: the prompt
            // interpolates {{payload}} and can therefore carry personal data, and this log is
            // structured and persisted outside the runtime. The adapter id is deliberately not
            // interpolated either -- it is graph-supplied GraphML of unbounded length that may
            // contain newlines, and this is a plain-text logger. It travels on the exception instead;
            // StructuredExecutionLogger escapes it when rendering the structured execution log.
            LOGGER.log(System.Logger.Level.WARNING,
                    "ravenroot_node_refused node={0} execution={1} behavior={2} reason=no-adapter-configured",
                    node.id(), message.executionId(), "llm-prompt");
            // Never a NodeResult -- see rule 1 on this class. The wording stays technical: a refusal
            // asserting a compliance or legal posture would be an external representation this
            // project has not authorised and is quotable out of context.
            return CompletableFuture.<NodeResult>failedFuture(new IllegalStateException(failure));
        };
    }

    /** The core's {@code ToolAuthorization}, carried across for the same reason the renderer is. */
    private void requireToolAllowed(NodeMessage message, String tool, Map<String, Object> arguments) {
        // The identity comes from the delivered message, never from node properties or the payload:
        // NodeMessage.security() was fixed at ingress and carried forward by NodeMessage.next(...).
        ToolDecision decision = toolPolicy.evaluate(new ToolInvocation(message.security(), message.executionId(),
                message.nodeId(), tool, arguments));
        if (decision.disposition() == ToolDecision.Disposition.REQUIRE_APPROVAL) {
            throw new SecurityException("Tool requires approval before execution: " + tool
                    + (decision.approvalId().isBlank() ? "" : " (approval " + decision.approvalId() + ")"));
        }
        if (decision.disposition() != ToolDecision.Disposition.ALLOW) {
            throw new SecurityException(decision.reason().isBlank() ? "Tool execution denied: " + tool
                    : decision.reason());
        }
    }

    // ------------------------------------------------------------------ node property reading

    private static String string(GraphNode node, String name, String defaultValue) {
        Object value = node.properties().get(name);
        return value == null ? defaultValue : value.toString();
    }

    private static String required(GraphNode node, String name) {
        String value = string(node, name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Node " + node.id() + " requires property '" + name + "'");
        }
        return value;
    }

    private static Map<String, Object> prefixed(GraphNode node, String prefix) {
        var result = new LinkedHashMap<String, Object>();
        node.properties().forEach((name, value) -> {
            if (name.startsWith(prefix)) {
                result.put(name.substring(prefix.length()), value);
            }
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> attributes(NodeMessage message, Object... additions) {
        var result = new LinkedHashMap<String, Object>(message.attributes());
        for (int index = 0; index + 1 < additions.length; index += 2) {
            if (additions[index + 1] != null) {
                result.put(String.valueOf(additions[index]), additions[index + 1]);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Renders the prompt against the value that arrived on the incoming edge.
     *
     * <p>An unresolvable path fails the node with a message naming the node, the token and the reason.
     * It does not render an empty string and does not leave the token in the output: both are silent,
     * and a prompt that quietly sends {@code Summarize } to a model is the same class of defect as a
     * run reporting success having done nothing.</p>
     */
    static String render(String template, NodeMessage message, GraphNode node) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PAYLOAD_TOKEN.matcher(template);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            Object resolved = path == null || path.isEmpty()
                    ? requirePresent(message.payload(), matcher.group(), node)
                    : resolve(message.payload(), path.substring(1), matcher.group(), node);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(resolved)));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        for (var entry : message.attributes().entrySet()) {
            result = result.replace("{{attributes." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        for (var entry : node.properties().entrySet()) {
            result = result.replace("{{properties." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Object requirePresent(Object payload, String token, GraphNode node) {
        if (payload == null) {
            throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                    + "': no value arrived on its incoming edge");
        }
        return payload;
    }

    private static Object resolve(Object payload, String path, String token, GraphNode node) {
        Object current = requirePresent(payload, token, node);
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': the path has an empty segment");
            }
            current = step(current, segment, token, node);
        }
        return current;
    }

    private static Object step(Object current, String segment, String token, GraphNode node) {
        if (current instanceof Map<?, ?> map) {
            if (!map.containsKey(segment)) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': the value has no entry '" + segment + "'");
            }
            Object next = map.get(segment);
            if (next == null) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': entry '" + segment + "' is null");
            }
            return next;
        }
        // Arrays are indexed alongside lists deliberately: a CEL list literal arrives as an Object[],
        // not a java.util.List, so treating only List would make {{payload.items.0}} work or not
        // depending on which upstream behavior produced the sequence.
        boolean isList = current instanceof List<?>;
        if (isList || current.getClass().isArray()) {
            int size = isList ? ((List<?>) current).size() : java.lang.reflect.Array.getLength(current);
            int index;
            try {
                index = Integer.parseInt(segment);
            } catch (NumberFormatException notAnIndex) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': '" + segment + "' is not a list index");
            }
            if (index < 0 || index >= size) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': index " + index + " is outside a list of " + size);
            }
            Object next = isList ? ((List<?>) current).get(index) : java.lang.reflect.Array.get(current, index);
            if (next == null) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': index " + index + " is null");
            }
            return next;
        }
        throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token + "': '" + segment
                + "' cannot be addressed on a " + current.getClass().getSimpleName() + ", which has no fields");
    }
}
