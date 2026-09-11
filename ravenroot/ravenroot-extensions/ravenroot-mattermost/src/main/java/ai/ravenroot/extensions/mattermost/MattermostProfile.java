package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Set;

/** Immutable tenant authority for one Mattermost team and its selected public channels. */
record MattermostProfile(String tenantId, String name, URI origin, String teamId, Set<String> publicChannelIds,
                         String credentialBindingId, String credentialReference, String webhookTokenReference,
                         String outgoingWebhookRoute, int maxTextChars, int maxRequestBytes, int maxResponseBytes,
                         int maxConcurrency, int maxPerSecond, int requestTimeoutMs, int retries) {
    /** Leaves transport and serialization time inside Mattermost's three-second acknowledgement window. */
    static final int MAX_ACK_TIMEOUT_MS = 2_800;

    MattermostProfile {
        tenantId = token(tenantId, 160); name = token(name, 64);
        if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) throw configuration();
        teamId = id(teamId); publicChannelIds = ids(publicChannelIds);
        credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        webhookTokenReference = token(webhookTokenReference, 256);
        outgoingWebhookRoute = route(outgoingWebhookRoute);
        if (maxTextChars < 1 || maxTextChars > 16_383 || maxRequestBytes < 1 || maxRequestBytes > 1024 * 1024
                || maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024 || maxConcurrency < 1
                || maxConcurrency > 64 || maxPerSecond < 1 || maxPerSecond > 100
                || requestTimeoutMs < 100 || requestTimeoutMs > MAX_ACK_TIMEOUT_MS
                || retries < 0 || retries > 3)
            throw configuration();
    }
    boolean permitsChannel(String channel) { return publicChannelIds.contains(channel); }
    OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }
    URI posts() { return origin.resolve("/api/v4/posts"); }
    static String id(String value) {
        if (value == null || !value.matches("[a-z0-9]{26}")) throw configuration();
        return value;
    }
    private static Set<String> ids(Set<String> values) {
        values = Set.copyOf(values);
        if (values.isEmpty() || values.size() > 256) throw configuration();
        values.forEach(MattermostProfile::id); return values;
    }
    private static String token(String value, int maximum) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0," + (maximum - 1) + "}"))
            throw configuration();
        return value;
    }
    private static String route(String value) {
        if (value == null || !value.matches("/[A-Za-z0-9._~/-]{1,159}") || value.contains("//")
                || value.contains("..") || value.endsWith("/")) throw configuration();
        return value;
    }
    private static MattermostException configuration() {
        return new MattermostException(MattermostException.Code.CONFIGURATION);
    }
}
