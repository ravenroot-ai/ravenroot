package ai.ravenroot.extensions.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiscordConfigurationTest {
    @TempDir Path directory;

    @Test void strictEnvironmentRoundTripRetainsOnlyOperatorAuthority() {
        DiscordConfiguration expected = DiscordTestSupport.configuration(directory.resolve("discord.db"));
        Map<String, Object> profile = profile(directory);
        Map<String, Object> root = Map.of("authority", authority(), "projection", projection(),
                "store", Map.of("path", expected.store().path().toString(), "maxDeliveries", 100L, "retentionHours", 24L),
                "profiles", Map.of(DiscordTestSupport.PROFILE, profile));
        String encoded = Base64.getEncoder().encodeToString(DiscordValues.jsonBytes(root));
        DiscordConfiguration actual = DiscordConfiguration.fromEnvironment(Map.of(DiscordConfiguration.ENVIRONMENT, encoded));
        DiscordProfile resolved = actual.profile(DiscordTestSupport.TENANT, DiscordTestSupport.PROFILE).orElseThrow();
        assertEquals(DiscordProfile.PRODUCTION_ORIGIN, resolved.apiOrigin());
        assertEquals(DiscordTestSupport.APPLICATION, resolved.applicationId());
        assertTrue(resolved.permits(DiscordTestSupport.GUILD, DiscordTestSupport.CHANNEL, "deploy"));
        assertEquals("discord-bot-token", resolved.credential().reference());
    }

    @Test void rejectsUnknownFieldsNonProductionOriginAndCrossTenantLookup() {
        Map<String, Object> bad = new LinkedHashMap<>(profile(directory)); bad.put("unknown", "value");
        assertInvalid(bad);
        bad = new LinkedHashMap<>(profile(directory)); bad.put("apiOrigin", "https://example.invalid");
        assertInvalid(bad);
        assertTrue(DiscordTestSupport.configuration(directory.resolve("direct.db"))
                .profile("tenant-b", DiscordTestSupport.PROFILE).isEmpty());
    }

    private void assertInvalid(Map<String, Object> profile) {
        Map<String, Object> root = Map.of("authority", authority(), "projection", projection(),
                "store", Map.of("path", directory.resolve("bad.db").toString(), "maxDeliveries", 100L, "retentionHours", 24L),
                "profiles", Map.of(DiscordTestSupport.PROFILE, profile));
        String encoded = Base64.getEncoder().encodeToString(DiscordValues.jsonBytes(root));
        assertThrows(DiscordException.class,
                () -> DiscordConfiguration.fromEnvironment(Map.of(DiscordConfiguration.ENVIRONMENT, encoded)));
    }

    private static Map<String, Object> authority() {
        return Map.of("listenerId", "main", "pathPrefix", "/managed/discord",
                "requiredScopes", java.util.List.of("discord:interactions"), "maxRoutes", 8L,
                "maxConcurrentRequests", 32L, "maxRequestBytes", 1_048_576L,
                "maxResponseBytes", 65_536L, "requestTimeoutMs", 2_500L);
    }
    private static Map<String, Object> projection() {
        return Map.of("maxRelativePathBytes", 256L, "maxQueryParameters", 1L, "maxQueryBytes", 256L,
                "maxHeaderCount", 2L, "maxHeaderBytes", 512L, "maxHeaderValueBytes", 256L);
    }
    private static Map<String, Object> profile(Path directory) {
        return Map.ofEntries(Map.entry("tenantId", DiscordTestSupport.TENANT),
                Map.entry("apiOrigin", "https://discord.com/api/v10"),
                Map.entry("applicationId", DiscordTestSupport.APPLICATION),
                Map.entry("publicKeyHex", "eb6fa3a04b766ee3ef693301641cc4f20870b87b0c9d077665ca1339106585b3"),
                Map.entry("guilds", Map.of(DiscordTestSupport.GUILD, java.util.List.of(DiscordTestSupport.CHANNEL))),
                Map.entry("commands", java.util.List.of("deploy")), Map.entry("credentialBindingId", "discord-bot"),
                Map.entry("credentialReference", "discord-bot-token"), Map.entry("route", "/interactions"),
                Map.entry("limits", Map.ofEntries(Map.entry("requestTimeoutMs", 2_000L),
                        Map.entry("maxRequestBytes", 1_048_576L), Map.entry("maxResponseBytes", 65_536L),
                        Map.entry("maxContentChars", 2_000L), Map.entry("maxAttachmentBytes", 1_048_576L),
                        Map.entry("maxAttachments", 4L), Map.entry("maxConcurrency", 2L),
                        Map.entry("maxPerSecond", 20L), Map.entry("retries", 2L),
                        Map.entry("signatureMaxAgeSeconds", 300L), Map.entry("futureSkewSeconds", 30L))));
    }
}
