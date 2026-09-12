package ai.ravenroot.extensions.discord;

interface DiscordDeliveryStore extends AutoCloseable {
    Decision bind(String tenant, String profile, String applicationId, String interactionId, String bodyDigest);
    @Override default void close() { }
    enum Decision { FIRST_SEEN, REPLAY }
}
