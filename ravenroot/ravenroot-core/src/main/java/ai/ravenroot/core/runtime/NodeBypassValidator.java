package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed validation of every node's authored bypass flag, before any actor exists.
 *
 * <h2>Unconditional, and that is the whole reason it is a separate class</h2>
 * <p>{@link BehaviorPropertySchema} returns early for non-behavior nodes, which is correct for the
 * behavior properties it owns and is a hole here: this contract requires
 * {@code execution.bypass} refused on {@code START}, {@code END} and {@code ERROR} <em>with a message
 * that says why</em>, and those are exactly the nodes that class skips. This validator therefore
 * shares no early return with it, on the {@link NodeRuntimeNatureValidator} precedent.</p>
 *
 * <h2>Where it does <em>not</em> follow that precedent</h2>
 * <p>{@code NodeRuntimeNatureValidator} also refuses a declaration made by a behavior the trusted
 * catalog does not contain, because a nature is a privilege and an unknown behavior cannot be granted
 * one. This validator deliberately does not, and the difference is not an oversight: a bypass
 * subtracts execution instead of granting anything, and it must cover a node the deployment cannot
 * provision. Refusing the flag on an uncatalogued behavior would refuse it on precisely those graphs.
 * That is also why this class needs no {@code BehaviorRegistry}
 * at all — it consults no catalog, so there is no constructor dependency to keep in step with one.</p>
 *
 * <h2>Declared, never inferred</h2>
 * <p>Nothing here reads topology. Whether a node is switched off is a statement its author wrote on
 * that node, so edges, predecessors and reachability are not consulted and must not become consulted:
 * a bypass derived from graph shape would be the runtime deciding not to run something the author
 * never asked it to skip.</p>
 *
 * <h2>Where it runs</h2>
 * <p>From the {@link GraphRunner} constructor, in the same fail-first group as
 * {@link BehaviorPropertySchema} and {@link NodeRuntimeNatureValidator} — so before a single actor
 * exists and, on the deployment path, before {@code DefaultGraphDeployment} starts any inbound
 * source. Both the deployment start path and the one-shot submission path build a runner, so one call
 * site covers both.</p>
 */
public final class NodeBypassValidator {

    /** Node kinds that execute something a bypass could skip. Today exactly one, named rather than negated. */
    private static final Set<NodeKind> EXECUTES_A_BEHAVIOUR = Set.of(NodeKind.BEHAVIOR);

    /**
     * Validates every node in {@code graph}, throwing on the first violation.
     *
     * @param graph the materialised definition to check
     * @throws NodeBypassException on the first node whose declaration cannot be honoured
     */
    public void validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        for (GraphNode node : graph.nodes()) {
            validateNode(node);
        }
    }

    private static void validateNode(GraphNode node) {
        if (!NodeBypassProperty.declaredBy(node.properties())) {
            return;
        }
        Object raw = node.properties().get(NodeBypassProperty.NAME);
        if (NodeBypassProperty.parse(raw).isEmpty()) {
            throw refuse(NodeBypassException.Reason.UNPARSEABLE_VALUE, node, raw);
        }
        // Refused whatever the value is, `false` included. A `execution.bypass=false` on an END node
        // is inert today, but it is still the author writing a sentence about a node that cannot
        // answer it, and accepting it now means the editor may offer the control there — after which
        // flipping it to `true` becomes the refusal, on a node the author was already allowed to
        // configure. Refusing the key, not the value, keeps the surface honest in both states.
        if (!EXECUTES_A_BEHAVIOUR.contains(node.kind())) {
            throw refuse(NodeBypassException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE, node, raw);
        }
    }

    /**
     * Builds the refusal. Every value derived from the submitted graph goes to the diagnostic map and
     * none of it reaches the public message — see {@link NodeBypassException}.
     */
    private static NodeBypassException refuse(NodeBypassException.Reason reason, GraphNode node,
                                              Object declaredValue) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodeId", node.id());
        detail.put("nodeKind", node.kind().name());
        if (node.behavior() != null) {
            detail.put("behavior", node.behavior());
        }
        detail.put("declaredBypass", String.valueOf(declaredValue));
        return new NodeBypassException(reason, detail);
    }
}
