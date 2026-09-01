package ai.ravenroot.server.audit;

/**
 * Receives one record per rejected GraphML submission (FIX-03).
 *
 * <p>{@link ai.ravenroot.core.graph.GraphMlRejectionDetail#diagnosticDetail()}'s own contract is the
 * reason this exists as an injectable seam rather than a hardcoded field: its Javadoc requires the
 * detail to "only ever reach a server-side sink", and a fixed {@code System.out} field is not
 * pluggable — every deployment gets the same one, whatever "server-side" needs to mean for it. This
 * interface is what lets that requirement be satisfied by a governed channel instead of merely a
 * topologically-server-side one.
 */
@FunctionalInterface
public interface GraphMlRejectionAuditSink {
    void record(GraphMlRejectionAuditEvent event);

    /** Discards records; for tests and embedded uses that supply their own observability. */
    static GraphMlRejectionAuditSink discarding() {
        return event -> {
        };
    }
}
