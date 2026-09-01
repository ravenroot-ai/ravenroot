package ai.ravenroot.server.error;

/**
 * Receives one record per rejected structured payload (API-01).
 *
 * <p>{@link ai.ravenroot.api.payload.PayloadException#diagnosticDetail()}'s own contract is the reason
 * this exists as an injectable seam rather than a hardcoded field: its Javadoc requires the detail to
 * only reach a server-side sink, and a fixed {@code System.out} field cannot be governed per
 * deployment. Mirrors {@code GraphMlRejectionAuditSink} exactly; kept in its own package for the same
 * reason {@code StructuredPayloadRejectionLogger} already is (see that class's Javadoc).
 */
@FunctionalInterface
public interface PayloadRejectionAuditSink {
    void record(PayloadRejectionAuditEvent event);

    /** Discards records; for tests and embedded uses that supply their own observability. */
    static PayloadRejectionAuditSink discarding() {
        return event -> {
        };
    }
}
