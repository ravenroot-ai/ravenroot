package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Turns JSON text on an edge into the structured value the rest of the engine already carries.
 *
 * <h2>The gap this closes</h2>
 * <p>{@code payload} reaches a CEL node as whatever the previous node produced. From the editor that
 * is a {@code String}: both of {@code ravenroot-ui/src/runtime-client.js}'s submissions,
 * {@code start()} and {@code run()}, carry the payload as {@code ?payload=<text>}, which becomes
 * {@code PayloadEnvelope.legacyText} and so a {@code PayloadValue.TextValue}. Only {@code run()} — the
 * editor's Run — executes behaviours at all; {@code start()}, behind Play, submits under the
 * passthrough test policy, where every node is bypassed. {@code run()} is therefore the submission
 * this node exists for, and the one {@code JsonPayloadDecisionHttpTest} reproduces.
 * {@code payload.field} is therefore not writable, and no other node
 * closes the gap: {@code cel-transform} builds a map from scratch but cannot decode one, because the
 * CEL environment is {@code ScriptHost.newBuilder().build()} with no extension library and so has no
 * JSON function to call. The same gap appears mid-graph, where {@code http-request} produces the raw
 * response body as text.</p>
 *
 * <h2>What it reads</h2>
 * <p>{@code source} is a template rendered by {@link NodeProperties#render}, defaulting to
 * {@code {{payload}}} — the whole incoming value. That single property answers both cases with one
 * mechanism rather than two: {@code {{payload}}} for a submitted JSON payload,
 * {@code {{payload.body}}} for JSON nested inside a value an earlier node produced. It is the same
 * property shape {@code http-request} already uses for its own body template.</p>
 *
 * <h3>The substitution runs over the document, and that is a real edge</h3>
 * <p>{@code render} performs the {@code {{payload…}}} substitution first, then a plain
 * {@code String.replace} pass for {@code {{attributes.X}}} and {@code {{properties.X}}} <em>over the
 * result</em> — that is, over the document text itself. So a JSON document that literally contains
 * {@code {{attributes.status}}} has that token replaced when an attribute named {@code status} is in
 * scope. The acute case is pinned by
 * {@code JsonParseNodeBehaviorTest.substitutesAttributeTokensInsideTheDocumentText}: the document
 * {@code {"status":"{{attributes.status}}"}} with the attribute {@code status=KO} parses to
 * {@code {status=KO}} — an attribute has decided the field the graph branches on.</p>
 *
 * <p>The behaviour is left as it is rather than special-cased, because it is the substitution
 * {@code template}, {@code log} and {@code http-request}'s body have always performed, the descriptor
 * declares those tokens as supported, and one node quietly disagreeing with the other four is the
 * defect rather than the repair. It is written down because it was in no shipped artefact: a token
 * that is absent from scope survives verbatim and leaves the document intact, and a substituted value
 * containing {@code "} breaks well-formedness and fails the node loudly — so the only silent case is
 * the one above, a token that resolves.</p>
 *
 * <h2>Limits are enforced while parsing, not afterwards</h2>
 * <p>{@link PayloadJson#read(byte[], PayloadLimits)} counts depth, value count, collection size, text
 * length and key length as it descends, so a document that exceeds a budget is refused
 * <em>before</em> its tree exists. Nesting in particular is refused at {@code maxDepth}, which is 32
 * in {@link PayloadLimits#DEFAULTS} — the parser therefore recurses a bounded number of frames
 * whatever the input, which is what makes a deeply nested document a stated rejection rather than a
 * {@code StackOverflowError}. {@code JsonParseNodeBehaviorTest} drives that case with 100 000 nested
 * brackets and asserts the classified rejection.</p>
 *
 * <p>The {@code byte[]} overload is chosen deliberately over the {@code String} one. Only the byte
 * overload checks {@code maxEncodedBytes}, and the interior budgets alone do not imply it: a
 * thousand-element array of one-kilobyte strings satisfies every per-element budget and still encodes
 * to just over a megabyte, close to four times the 256 KiB default. Encoding the rendered text to
 * UTF-8 costs one allocation and is what makes the size budget apply here at all.</p>
 *
 * <p>{@link PayloadLimits#DEFAULTS} is used rather than an environment-supplied profile because
 * {@code BehaviorEnvironment} carries no payload budget, and because it is the same profile the HTTP
 * ingress applies: {@code RavenrootServer} holds {@code payloadLimits = PayloadLimits.DEFAULTS} in a
 * final field. Text that was admitted at submission is therefore measured here against the budget it
 * was already measured against — not a second, different one.</p>
 *
 * <h2>Any JSON document, not only an object</h2>
 * <p>A valid scalar or array is accepted and becomes the corresponding interior value. RFC 8259 admits
 * any value as a document, {@link PayloadValue} models all of them, and refusing a top-level array
 * would make this node useless for exactly the {@code http-request} response body it exists to serve.
 * Addressing a field of a value that has none is then the author's error, reported by CEL where the
 * author wrote it.</p>
 *
 * <h2>Failure has no outcome of its own</h2>
 * <p>Malformed or over-budget input fails the node. It does <em>not</em> select a node-local failure
 * outcome, and no {@code failureOutcome} property is declared: the engine already expresses recovery
 * with the author-declared {@code failure.route} edge, and a second, node-local vocabulary for the
 * same thing would be a parallel mechanism to keep in step. {@code http-request}'s
 * {@code failureOutcome} is not the precedent it looks like — an HTTP non-2xx is a well-formed answer
 * from the remote system, whereas this is the node being unable to produce a value at all.</p>
 *
 * <p>The {@code PayloadException} is passed through unwrapped, and the reason is narrower than it
 * first looks. It is <em>not</em> that wrapping would lose the classification: {@code NodeFailurePayload.of}
 * unwraps to the deepest cause, so a wrapper carrying the exception as its cause would leave
 * {@code errorClass} and {@code message} unchanged on the failure route. It is that wrapping would
 * prepend a node id that both surfaces already carry — {@code NODE_FAILED} serializes {@code nodeId},
 * and {@code NodeFailurePayload} names it as its own component — in exchange for a frame no reader
 * ever sees.</p>
 *
 * <p>What must not be claimed here, because it was measured and is false: that the classification
 * reaches anyone. {@code code()} and {@code incidentId()} reach no surface at all for a node failure.
 * The exception message travels as {@code ExecutionEvent.detail}, which {@code executionEventJson}
 * deliberately never serializes — the event carries a sentence chosen from an allowlist by event
 * type. The one place the reason does travel to a graph author is the failure route's
 * {@code NodeFailurePayload}, which carries {@code nodeId}, {@code errorClass}, {@code message} and
 * the {@code input} the node had received.</p>
 *
 * <h2>The reserved-key guard is re-applied here</h2>
 * <p>{@code AuthorizedRavenrootApplication} walks every submitted payload with
 * {@link PayloadValue#requireNoReservedKeys} — but a JSON <em>document carried as text</em> presents
 * no keys to that walk, so a reserved name inside it passes ingress unseen and would first become a
 * key here. Repeating the check at the point the keys come into existence is what keeps the ingress
 * guarantee true rather than merely true of the shapes ingress could see.</p>
 */
final class JsonParseNodeBehaviorFactory implements NodeBehaviorFactory {

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("json-parse", "JSON parse", "Transformations",
                "Parses JSON text into a structured payload that later nodes can address field by field.",
                "flow", false, List.of(NodePropertyDescriptor.optional("source", "JSON source",
                NodePropertyType.TEXT,
                "Template producing the JSON text to parse. Supports {{payload}}, {{payload.a.b}}, "
                        + "{{attributes.name}} and {{properties.name}}.", "{{payload}}")),
                Set.of("deterministic"))
                // Every core behaviour must declare what it can emit. `json-parse` emits only
                // `continue` and deliberately has no failure outcome: non-JSON text makes the node
                // FAIL, and recovery is
                // declared through the `failure.route` edge that the engine already has -- not through
                // a second local mechanism on this node.
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The decoded value becomes the outgoing payload. Non-JSON text, or a document "
                                + "beyond the limits, fails the node instead of producing an outcome."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        String source = NodeProperties.string(node, "source", "{{payload}}");
        return message -> {
            try {
                PayloadValue parsed = PayloadJson.read(
                        NodeProperties.render(source, message, node).getBytes(StandardCharsets.UTF_8),
                        PayloadLimits.DEFAULTS);
                PayloadValue.requireNoReservedKeys(parsed);
                return CompletableFuture.completedFuture(
                        new NodeResult("continue", parsed.toJava(), message.attributes()));
            } catch (IllegalArgumentException rejected) {
                // PayloadException extends IllegalArgumentException, and NodeProperties.render raises
                // the same type for an unresolvable source template, so one catch covers both. It is
                // converted to a failed future rather than allowed to escape synchronously from the
                // handler, which is the hazard the CEL factories in this package document.
                return CompletableFuture.failedFuture(rejected);
            }
        };
    }
}
