package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Map;
import java.util.Set;

record DiscordProfile(String tenantId, String name, URI apiOrigin, String applicationId,
                      byte[] publicKey, Set<String> guildIds, Map<String, Set<String>> channels,
                      Set<String> commands, String credentialBindingId, String credentialReference,
                      String route, int requestTimeoutMs, int maxRequestBytes, int maxResponseBytes,
                      int maxContentChars, int maxAttachmentBytes, int maxAttachments,
                      int maxConcurrency, int maxPerSecond, int retries,
                      int signatureMaxAgeSeconds, int futureSkewSeconds) {
    static final URI PRODUCTION_ORIGIN = URI.create("https://discord.com/api/v10");

    DiscordProfile {
        tenantId = token(tenantId, 160); name = token(name, 64);
        if (!PRODUCTION_ORIGIN.equals(apiOrigin)) throw configuration();
        applicationId = snowflake(applicationId);
        publicKey = publicKey == null ? new byte[0] : publicKey.clone();
        if (publicKey.length != 32) throw configuration();
        guildIds = Set.copyOf(guildIds);
        Map<String, Set<String>> copiedChannels = new java.util.LinkedHashMap<>();
        channels.forEach((guild, values) -> copiedChannels.put(guild, Set.copyOf(values)));
        channels = Map.copyOf(copiedChannels); commands = Set.copyOf(commands);
        if (guildIds.isEmpty() || channels.isEmpty() || commands.isEmpty()
                || !guildIds.containsAll(channels.keySet())) throw configuration();
        channels.forEach((guild, values) -> {
            snowflake(guild); if (values == null || values.isEmpty()) throw configuration();
            values.forEach(DiscordProfile::snowflake);
        });
        guildIds.forEach(DiscordProfile::snowflake);
        commands.forEach(command -> { if (!command.matches("[a-z0-9_-]{1,32}")) throw configuration(); });
        credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        if (route == null || !route.matches("/[A-Za-z0-9._~/-]{1,159}") || route.contains("//")
                || route.contains("..")) throw configuration();
        if (requestTimeoutMs < 100 || requestTimeoutMs > 2_800 || maxRequestBytes < 1
                || maxRequestBytes > 1024 * 1024 || maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024
                || maxContentChars < 1 || maxContentChars > 2_000 || maxAttachmentBytes < 1
                || maxAttachmentBytes > 8 * 1024 * 1024 || maxAttachments < 0 || maxAttachments > 10
                || maxConcurrency < 1 || maxConcurrency > 64 || maxPerSecond < 1 || maxPerSecond > 50
                || retries < 0 || retries > 3 || signatureMaxAgeSeconds < 1 || signatureMaxAgeSeconds > 300
                || futureSkewSeconds < 0 || futureSkewSeconds > 60) throw configuration();
    }

    @Override public byte[] publicKey() { return publicKey.clone(); }
    boolean permits(String guild, String channel, String command) {
        return guildIds.contains(guild) && channels.getOrDefault(guild, Set.of()).contains(channel)
                && commands.contains(command);
    }
    boolean permitsChannel(String channel) { return channels.values().stream().anyMatch(set -> set.contains(channel)); }
    OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }
    URI channelMessages(String channel) { return apiOrigin.resolve(apiOrigin.getPath() + "/channels/" + channel + "/messages"); }

    private static String token(String value, int maximum) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximum - 1) + "}")) throw configuration();
        return value;
    }
    static String snowflake(String value) {
        if (value == null || !value.matches("[0-9]{1,20}")) throw configuration();
        try {
            java.math.BigInteger number = new java.math.BigInteger(value);
            if (number.signum() <= 0 || number.bitLength() > 64) throw configuration();
        }
        catch (NumberFormatException failure) { throw configuration(); }
        return value;
    }
    private static DiscordException configuration() { return new DiscordException(DiscordException.Code.CONFIGURATION); }
}
