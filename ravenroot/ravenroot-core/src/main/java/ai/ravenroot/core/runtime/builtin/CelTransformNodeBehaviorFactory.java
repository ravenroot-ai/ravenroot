package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class CelTransformNodeBehaviorFactory implements NodeBehaviorFactory {

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("cel-transform", "CEL transform", "Transformations",
                "Evaluates a checked, non-Turing-complete CEL expression against payload, attributes and properties.",
                "flow", false, List.of(NodePropertyDescriptor.required("expression", "CEL expression",
                NodePropertyType.CEL_EXPRESSION, "Expression result becomes the outgoing payload.")),
                Set.of("deterministic", "cel"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The expression result becomes the outgoing payload. The only outcome this node "
                                + "produces: a CEL evaluation error fails the node instead. Note that "
                                + "cel-transform never branches on its result — cel-decision does."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        Script script = compile(node, NodeProperties.required(node, "expression"));
        return message -> {
            try {
                Object value = script.execute(Object.class, bindings(message, node, "transform"));
                return CompletableFuture.completedFuture(new NodeResult("continue", value, message.attributes()));
            } catch (IllegalArgumentException absent) {
                // The absent-value refusal above, converted to a failed future rather than allowed to
                // escape synchronously from the handler.
                return CompletableFuture.failedFuture(absent);
            } catch (ScriptException error) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "CEL transform failed at node " + node.id() + ": " + error.getMessage(), error));
            }
        };
    }

    /**
     * The CEL variable bindings, with an absent payload made explicit.
     *
     * <p>These used to be built with {@code Map.of}, which <strong>rejects null values</strong>. A
     * node handed no value therefore threw {@link NullPointerException} from the map constructor --
     * before the script ran, so it was not a {@code ScriptException} and the handler's own catch never
     * saw it. It escaped synchronously out of the node handler, which is the engine-dependent
     * synchronous-throw hazard this package documents elsewhere, and it named nothing useful.</p>
     *
     * <p>Now the absence is stated: a failed future carrying a message that names the node and says no
     * value arrived. That is one of three separate wrong behaviours around a missing value;
     * the other two are the template rendering the literal string {@code "null"} into a result, and a
     * join refusing a branch that produced nothing. Same cause, three different failures, fixed
     * separately because they fail in three different places for three different reasons.</p>
     */
    static Map<String, Object> bindings(NodeMessage message, GraphNode node, String kind) {
        if (message.payload() == null) {
            throw new IllegalArgumentException("CEL " + kind + " at node " + node.id()
                    + " received no value on its incoming edge");
        }
        return Map.of("payload", message.payload(),
                "attributes", message.attributes(),
                "properties", node.properties());
    }

    /**
     * Compiles one node's expression against a {@link ScriptHost} of its own.
     *
     * <h2>Why the host is no longer static</h2>
     * <p>It used to be one {@code ScriptHost} for the whole JVM, shared by every {@code cel-transform}
     * and {@code cel-decision} node in every graph in every deployment. Previously a logical node was
     * a single actor, so its evaluations were serialised by the mailbox; the current lifecycle removes that mailbox on
     * purpose. Narrowing a piece of process-wide shared state to the node that uses it, at the moment
     * concurrent use of it becomes normal, costs one allocation per graph node at composition time and
     * nothing per message.
     *
     * <h2>Defensive, not a fixed bug — and the difference is stated on purpose</h2>
     * <p>What is <em>verified</em>: {@code ProtoTypeRegistry} holds its {@code revTypeMap} in a plain
     * {@link java.util.HashMap}, and {@code registerMessage}, {@code registerDescriptor} and
     * {@code registerType} mutate it with no synchronisation.
     *
     * <p>No Ravenroot evaluation path to those mutators has been established. Evaluation calls
     * {@code nativeToValue}; inspection of that method shows no call to {@code registerMessage} or
     * the other mutators. Graph-controlled payload content therefore has no known reachable write
     * path into {@code revTypeMap}.
     *
     * <p>Per-node allocation therefore removes a class of risk rather than a demonstrated race. The
     * distinction matters because no reachable concurrent write has been established.
     *
     * <h2>Scope, so it is not overread</h2>
     * <p>Per <em>node</em>, not per invocation: two concurrent invocations of the same node still
     * share one host. Going further would mean compiling per invocation, on the dispatch path, which
     * is the cost ADR 0024 warns about — and there is no established reachable race to justify it.
     */
    static Script compile(GraphNode node, String expression) {
        ScriptHost host = ScriptHost.newBuilder().build();
        try {
            return host.buildScript(expression).withDeclarations(
                    Decls.newVar("payload", Decls.Dyn),
                    Decls.newVar("attributes", Decls.newMapType(Decls.String, Decls.Dyn)),
                    Decls.newVar("properties", Decls.newMapType(Decls.String, Decls.Dyn))).build();
        } catch (ScriptException error) {
            throw new IllegalArgumentException("Invalid CEL expression at node " + node.id() + ": "
                    + error.getMessage(), error);
        }
    }
}
