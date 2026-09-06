package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.execution.CancellationSignal;

interface MatrixSyncStore extends AutoCloseable {
    record SourceKey(String tenant, String profile, String deployment, String node) { }
    String cursor(SourceKey source);
    Decision bindEvent(SourceKey source, String eventId, String bodyDigest,
                       long deadlineNanos, CancellationSignal cancellation);
    void advance(SourceKey source, String expectedCursor, String nextCursor,
                 long deadlineNanos, CancellationSignal cancellation);
    @Override default void close() { }
    enum Decision { FIRST_SEEN, REPLAY }
}
