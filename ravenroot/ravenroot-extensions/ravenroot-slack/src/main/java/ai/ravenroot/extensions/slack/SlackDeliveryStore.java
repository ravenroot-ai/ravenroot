package ai.ravenroot.extensions.slack;

interface SlackDeliveryStore extends AutoCloseable {
    Decision bind(String tenant, String profile, String kind, String deliveryId, String bodyDigest);
    @Override default void close() { }
    enum Decision { FIRST_SEEN, REPLAY }
}
