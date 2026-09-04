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
 * @param connectorAttempts how many times a connector attempted the underlying operation inside this
 * one orchestration attempt, or {@link ConnectorRetryReport#NOT_REPORTED} when the node said nothing.
 * A trusted count, never routed downstream: like {@code actionDiagnostic} the runtime consumes it and
 * it does not reach the next node. See {@link ConnectorRetryReport} for why the distinction between
 * "nothing reported" and "one attempt" is kept
 */
public record NodeResult(String outcome, Object payload, Map<String, Object> attributes,
                         NodeActionDiagnostic actionDiagnostic, int connectorAttempts) {
    /** Validates the boundary between a node implementation and the graph runtime. */
    public NodeResult {
        outcome = outcome == null || outcome.isBlank() ? "continue" : outcome;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (connectorAttempts < 0) {
            // A negative count is not a weaker claim than zero, it is an unreadable one, and it would
            // reach a metric as a value no consumer has a rule for.
            throw new IllegalArgumentException("connectorAttempts cannot be negative: " + connectorAttempts);
        }
    }

    /**
 * Compatibility constructor for a result that reports nothing about connector-internal retries.
 *
 * <p>Every node written before {@link ConnectorRetryReport} existed reaches this, and reporting
 * {@link ConnectorRetryReport#NOT_REPORTED} is what keeps its events honest: silence, not a claim
 * that the connector attempted exactly once.</p>
* @param outcome named graph outcome; {@code null} selects normal continuation
* @param payload payload to encode, inspect, or forward
* @param attributes immutable supplemental values exposed to the caller
* @param actionDiagnostic trusted author-display action, or {@code null}
 */
    public NodeResult(String outcome, Object payload, Map<String, Object> attributes,
                      NodeActionDiagnostic actionDiagnostic) {
        this(outcome, payload, attributes, actionDiagnostic, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
 * Compatibility constructor preserving the shape before typed author-display actions.
* @param outcome named graph outcome; {@code null} selects normal continuation
* @param payload payload to encode, inspect, or forward
* @param attributes immutable supplemental values exposed to the caller
 */
    public NodeResult(String outcome, Object payload, Map<String, Object> attributes) {
        this(outcome, payload, attributes, null, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
 * Returns a copy of this result reporting how many times a connector attempted the operation.
 *
 * <p>A wither rather than a further constructor, because the count is discovered by the connector
 * while it works and is attached to a result the node has already composed. It does not change
 * outcome, payload or attributes, so it cannot be used to alter routing.</p>
* @param connectorAttempts connector-level attempt count, at least one, or
* {@link ConnectorRetryReport#NOT_REPORTED}
* @return an identical result carrying the reported count
 */
    public NodeResult reportingConnectorAttempts(int connectorAttempts) {
        return new NodeResult(outcome, payload, attributes, actionDiagnostic, connectorAttempts);
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
