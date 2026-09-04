package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Set;

/** Immutable tenant authority for one Slack application and workspace. */
record SlackProfile(String tenantId, String name, URI apiOrigin, String teamId, String applicationId,
                    String credentialBindingId, String credentialReference, String signingSecretReference,
                    String eventsRoute, String commandsRoute, Set<String> channelIds, Set<String> eventTypes,
                    Set<String> commands, Set<String> scopes, int requestTimeoutMs, int maxRequestBytes,
                    int maxResponseBytes, int maxTextChars, int maxConcurrency, int maxPerSecond,
                    int retries, int signatureMaxAgeSeconds) {
    static final URI PRODUCTION_ORIGIN = URI.create("https://slack.com");

    SlackProfile {
        tenantId = token(tenantId, 160); name = token(name, 64);
        if (!PRODUCTION_ORIGIN.equals(apiOrigin)) throw configuration();
        teamId = slackId(teamId); applicationId = slackId(applicationId);
        credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        signingSecretReference = token(signingSecretReference, 256);
        eventsRoute = route(eventsRoute); commandsRoute = route(commandsRoute);
        if (eventsRoute.equals(commandsRoute)) throw configuration();
        channelIds = boundedTokens(channelIds, 256, SlackProfile::slackId);
        eventTypes = boundedTokens(eventTypes, 128, value -> providerToken(value, 80));
        commands = boundedTokens(commands, 100, value -> {
            if (!value.matches("/[a-z0-9_-]{1,32}")) throw configuration();
        });
        scopes = boundedTokens(scopes, 128, value -> providerToken(value, 80));
        if (!scopes.containsAll(Set.of("chat:write", "commands"))) throw configuration();
        if (requestTimeoutMs < 100 || requestTimeoutMs > 2_800 || maxRequestBytes < 1
                || maxRequestBytes > 1024 * 1024 || maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024
                || maxTextChars < 1 || maxTextChars > 4_000 || maxConcurrency < 1 || maxConcurrency > 64
                || maxPerSecond < 1 || maxPerSecond > 50 || retries < 0 || retries > 3
                || signatureMaxAgeSeconds < 1 || signatureMaxAgeSeconds > 300) throw configuration();
    }

    boolean permitsChannel(String channel) { return channelIds.contains(channel); }
    OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }
    URI postMessage() { return apiOrigin.resolve("/api/chat.postMessage"); }

    static String slackId(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9]{1,31}")) throw configuration();
        return value;
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
    private static void providerToken(String value, int maximum) {
        if (value == null || value.length() > maximum || !value.matches("[A-Za-z0-9._:-]+")) throw configuration();
    }
    private static Set<String> boundedTokens(Set<String> values, int maximum, java.util.function.Consumer<String> check) {
        values = Set.copyOf(values);
        if (values.isEmpty() || values.size() > maximum) throw configuration();
        values.forEach(check); return values;
    }
    private static SlackException configuration() { return new SlackException(SlackException.Code.CONFIGURATION); }
}
