package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Set;

/** Immutable tenant authority for one Teams Workflow and Outgoing Webhook. */
record TeamsProfile(String tenantId, String name, URI workflowEndpoint, String microsoftTenantId,
                    String teamId, Set<String> channelIds, String credentialBindingId,
                    String credentialReference, String signingSecretReference, String webhookRoute,
                    int requestTimeoutMs, int maxRequestBytes, int maxResponseBytes, int maxTextChars,
                    int maxConcurrency, int maxPerSecond, int ackTimeoutMs, int signatureMaxAgeSeconds) {
    /** Leaves transport and serialization time inside Teams' five-second acknowledgement window. */
    static final int MAX_ACK_TIMEOUT_MS = 4_500;

    TeamsProfile {
        tenantId = token(tenantId, 160); name = token(name, 64);
        workflowEndpoint = endpoint(workflowEndpoint);
        microsoftTenantId = guid(microsoftTenantId); teamId = providerId(teamId, 160);
        channelIds = boundedIds(channelIds);
        credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        signingSecretReference = token(signingSecretReference, 256);
        webhookRoute = route(webhookRoute);
        if (requestTimeoutMs < 100 || requestTimeoutMs > 30_000
                || maxRequestBytes < 1 || maxRequestBytes > 1024 * 1024
                || maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024
                || maxTextChars < 1 || maxTextChars > 28_000
                || maxConcurrency < 1 || maxConcurrency > 64
                || maxPerSecond < 1 || maxPerSecond > 50
                || ackTimeoutMs < 100 || ackTimeoutMs > MAX_ACK_TIMEOUT_MS
                || signatureMaxAgeSeconds < 1 || signatureMaxAgeSeconds > 300) throw configuration();
    }

    boolean permitsChannel(String channelId) { return channelIds.contains(channelId); }

    OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }

    private static URI endpoint(URI value) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getFragment() != null || value.getPort() != -1
                || value.getRawQuery() != null || value.getRawPath() == null || value.getRawPath().isBlank()
                || value.getRawPath().contains("..")) throw configuration();
        return value.normalize();
    }

    static String providerId(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x21 || c == 0x7f)) throw configuration();
        return value;
    }

    private static String guid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
            throw configuration();
        return value.toLowerCase(java.util.Locale.ROOT);
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

    private static Set<String> boundedIds(Set<String> values) {
        values = Set.copyOf(values);
        if (values.isEmpty() || values.size() > 256) throw configuration();
        values.forEach(value -> providerId(value, 160));
        return values;
    }

    private static TeamsException configuration() {
        return new TeamsException(TeamsException.Code.CONFIGURATION);
    }
}
