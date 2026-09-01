package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadException;
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

/** RFC 9535 selection over bounded JSON input, with an always-array output contract. */
final class JsonPathNodeBehaviorFactory implements NodeBehaviorFactory {
    private static final PayloadLimits LIMITS = PayloadLimits.DEFAULTS;

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("json-path", "JSONPath", "Transformations",
                "Selects RFC 9535 JSONPath matches from bounded structured JSON or JSON text.",
                "flow", false, List.of(NodePropertyDescriptor.required("path", "JSONPath",
                NodePropertyType.STRING, "One RFC 9535 query. The ordered matches are emitted as an array.")),
                Set.of("deterministic", "json", "rfc-9535"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The ordered match array becomes the outgoing payload. Invalid input, query, or resource "
                                + "usage fails the node instead."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        // RFC 9535 permits S before a segment, but not before '$' or after the last segment.
        // NodeProperties.required trims, so use the raw scalar here and let the compiler decide.
        String query = NodeProperties.string(node, "path", "");
        Rfc9535JsonPath path;
        try {
            path = Rfc9535JsonPath.compile(query);
        } catch (JsonPathNodeException typedRefusal) {
            return failed(typedRefusal.reason());
        } catch (RuntimeException invalidPath) {
            return failed(JsonPathNodeException.Reason.INVALID_PATH);
        }

        return message -> {
            try {
                PayloadValue input = boundedInput(message.payload());
                List<Object> matches = path.select(input).stream().map(PayloadValue::toJava).toList();
                PayloadValue boundedOutput = PayloadValue.fromJava(matches, LIMITS);
                return CompletableFuture.completedFuture(
                        new NodeResult("continue", boundedOutput.toJava(), message.attributes()));
            } catch (PayloadException boundedRefusal) {
                return CompletableFuture.failedFuture(boundedRefusal);
            } catch (JsonPathNodeException boundedEvaluation) {
                return CompletableFuture.failedFuture(boundedEvaluation);
            } catch (RuntimeException invalidInput) {
                return CompletableFuture.failedFuture(
                        new JsonPathNodeException(JsonPathNodeException.Reason.INVALID_INPUT));
            }
        };
    }

    private static NodeHandler failed(JsonPathNodeException.Reason reason) {
        return ignored -> CompletableFuture.failedFuture(new JsonPathNodeException(reason));
    }

    private static PayloadValue boundedInput(Object payload) {
        if (payload instanceof CharSequence text) {
            if (text.length() > LIMITS.maxEncodedBytes()) {
                throw PayloadException.tooLarge(text.length(), LIMITS.maxEncodedBytes());
            }
            return PayloadJson.read(text.toString().getBytes(StandardCharsets.UTF_8), LIMITS);
        }
        return PayloadValue.fromJava(payload, LIMITS);
    }

}
