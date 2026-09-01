package ai.ravenroot.core.graph;

import java.util.Map;

/**
 * What every public GraphML rejection exposes, whichever layer refused the document (FIX-03).
 *
 * <p>{@link SecureGraphMlParser} and {@link GraphMlDocument} are two rejection paths that a caller
 * cannot tell apart and should not have to. Giving them a common type is what lets an adapter — the
 * HTTP server, the CLI — handle both with one branch instead of two that drift, and it is where the
 * split between the two channels is stated:</p>
 *
 * <ul>
 *   <li>{@link Throwable#getMessage()} is <b>public</b>. It is assembled by {@link GraphMlRejection}
 *       from server-authored text only and is safe to return to whoever submitted the document.</li>
 *   <li>{@link #diagnosticDetail()} is <b>not public</b>. It carries the fragments of the document
 *       that explain the rejection and must only ever reach a server-side sink.</li>
 *   <li>{@link #incidentId()} appears in both, and is the only thing that joins them.</li>
 * </ul>
 */
public sealed interface GraphMlRejectionDetail
        permits GraphMlCompatibilityException, GraphMlParseException {

    /** The classification an adapter maps to a status code. */
    GraphMlParseException.Reason reason();

    /** Correlation handle. Safe to return to the caller; it reveals nothing about the document. */
    String incidentId();

    /**
     * The document-derived explanation of the rejection.
     *
     * <p>Never place this in a response, a redirect, an error page or any other artefact the
     * submitter can observe. It exists so that the detail removed from the message is not lost.</p>
     */
    Map<String, String> diagnosticDetail();
}
