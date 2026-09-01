package ai.ravenroot.core.graph;

import java.util.Map;

/**
 * A safe, classified GraphML rejection that does not expose parser internals.
 *
 * <p>Construction is package-private and goes through {@link GraphMlRejection}, which is the single
 * declared sanitisation policy (FIX-03). Nothing else can build one, so no call site can assemble a
 * message of its own.</p>
 */
public final class GraphMlParseException extends IllegalArgumentException
        implements GraphMlRejectionDetail {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        DOCUMENT_TOO_LARGE,
        RESOURCE_LIMIT,
        UNSAFE_XML,
        INVALID_GRAPH,
        MALFORMED_XML,
        /**
         * The submitted bytes carry a compressed-archive container signature (gzip or zip) instead of
         * XML (FIX-09). Named for what the magic bytes identify, not for what the archive might have
         * contained if expanded: expansion is exactly what this check avoids doing, so nothing is known
         * about its payload beyond that it is not GraphML.
         */
        COMPRESSED_ARCHIVE
    }

    private final Reason reason;
    private final String incidentId;
    private final Map<String, String> diagnosticDetail;

    GraphMlParseException(Reason reason, String message, String incidentId,
                          Map<String, String> diagnosticDetail, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.incidentId = incidentId;
        this.diagnosticDetail = diagnosticDetail;
    }

    @Override
    public Reason reason() {
        return reason;
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
