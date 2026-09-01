package ai.ravenroot.api.execution;

import java.util.Map;

/**
 * What a node produced.
 *
 * <p>This record deliberately has <strong>no identity component</strong>, and none may be added. A
 * node influences payload, outcome and attributes; it does not influence who it is running as. See
 * {@link NodeMessage} for why the absence of the syntax — rather than a filter over
 * {@link #attributes()} — is what makes tenant and principal non-overridable by graph content
 *.</p>
 * @param outcome the named graph outcome selected by the node; {@code null} means normal continuation
 * @param payload the data value forwarded to downstream graph processing
 * @param attributes immutable supplemental values that the node deliberately exposes to its caller
 * @param actionDiagnostic a trusted node action for author display only; the runtime consumes and
 * strips it before downstream graph processing
 */
public record NodeResult(String outcome, Object payload, Map<String, Object> attributes,
                         NodeActionDiagnostic actionDiagnostic) {
    /** Validates the boundary between a node implementation and the graph runtime. */
    public NodeResult {
        outcome = outcome == null || outcome.isBlank() ? "continue" : outcome;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
 * Compatibility constructor preserving the shape before typed author-display actions.
* @param outcome named graph outcome; {@code null} selects normal continuation
* @param payload payload to encode, inspect, or forward
* @param attributes immutable supplemental values exposed to the caller
 */
    public NodeResult(String outcome, Object payload, Map<String, Object> attributes) {
        this(outcome, payload, attributes, null);
    }

    /**
 * Creates a result that continues through the default graph outcome with the supplied payload.
 *
 * @param payload the value made available to the next graph node
 * @return a result with no explicit outcome and no additional attributes
 */
    public static NodeResult continueWith(Object payload) {
        return new NodeResult("continue", payload, Map.of(), null);
    }
}
