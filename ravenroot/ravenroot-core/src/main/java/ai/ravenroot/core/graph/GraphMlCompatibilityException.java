package ai.ravenroot.core.graph;

import java.util.Map;

/**
 * Signals GraphML that cannot be mapped to Ravenroot's directed property-graph model without
 * silently discarding or changing information.
 *
 * <p>Construction is package-private and goes through {@link GraphMlRejection}, the single declared
 * sanitisation policy (FIX-03). Before that, this class took a free-form message and the
 * compatibility layer interpolated the submitted document into it, while the sibling security layer
 * did not. Narrowing the constructor is what makes the two layers share one policy rather than two
 * implementations that are expected to agree.</p>
 *
 * <p>The classification is always {@link GraphMlParseException.Reason#INVALID_GRAPH}: these
 * documents are well-formed XML that cleared every security budget and were refused on
 * property-graph semantics.</p>
 */
public final class GraphMlCompatibilityException extends IllegalArgumentException
        implements GraphMlRejectionDetail {
    private static final long serialVersionUID = 1L;

    private final String incidentId;
    private final Map<String, String> diagnosticDetail;

    GraphMlCompatibilityException(String message, String incidentId,
                                  Map<String, String> diagnosticDetail, Throwable cause) {
        super(message, cause);
        this.incidentId = incidentId;
        this.diagnosticDetail = diagnosticDetail;
    }

    @Override
    public GraphMlParseException.Reason reason() {
        return GraphMlParseException.Reason.INVALID_GRAPH;
    }

    @Override
    public String incidentId() {
        return incidentId;
    }

    @Override
    public Map<String, String> diagnosticDetail() {
        return diagnosticDetail;
    }
}
