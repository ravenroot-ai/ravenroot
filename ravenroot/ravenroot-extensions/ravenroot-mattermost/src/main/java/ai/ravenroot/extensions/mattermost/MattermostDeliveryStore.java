package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.execution.CancellationSignal;

interface MattermostDeliveryStore extends AutoCloseable {
    Decision bind(String tenant, String profile, String source, String postId, String bodyDigest,
                  long deadlineNanos, CancellationSignal cancellation);
    @Override default void close() { }
    enum Decision { FIRST_SEEN, REPLAY }
}
