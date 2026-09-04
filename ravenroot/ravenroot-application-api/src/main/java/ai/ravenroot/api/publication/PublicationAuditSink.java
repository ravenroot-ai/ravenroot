package ai.ravenroot.api.publication;

/** Operator-owned sink for payload-free publication decisions. */
@FunctionalInterface
public interface PublicationAuditSink {
    /**
     * Records one decision before a continue outcome becomes observable.
     *
     * @param event payload-free decision evidence
     */
    void record(PublicationAuditEvent event);

    /**
     * No-op sink for embedding and tests that do not retain audit evidence.
     *
     * @return a sink that discards every event
     */
    static PublicationAuditSink noop() {
        return ignored -> { };
    }
}
