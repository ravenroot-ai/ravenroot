package ai.ravenroot.extensions.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SlackConfigurationTest {
    @TempDir Path directory;
    @Test void strictEnvironmentRoundTripRetainsOperatorAuthority() {
        Map<String, Object> profile = Map.ofEntries(Map.entry("tenantId", SlackTestSupport.TENANT),
                Map.entry("apiOrigin", "https://slack.com"), Map.entry("teamId", SlackTestSupport.TEAM),
                Map.entry("applicationId", SlackTestSupport.APPLICATION),
                Map.entry("credentialBindingId", "slack-bot"), Map.entry("credentialReference", "slack-bot-token"),
                Map.entry("signingSecretReference", "slack-signing-secret"), Map.entry("eventsRoute", "/events"),
                Map.entry("commandsRoute", "/commands"), Map.entry("channels", List.of(SlackTestSupport.CHANNEL)),
                Map.entry("eventTypes", List.of("message")), Map.entry("commands", List.of("/deploy")),
                Map.entry("scopes", List.of("chat:write", "commands")),
                Map.entry("limits", Map.of("requestTimeoutMs", 2_500L, "maxRequestBytes", 1_048_576L,
                        "maxResponseBytes", 65_536L, "maxTextChars", 4_000L, "maxConcurrency", 2L,
                        "maxPerSecond", 20L, "retries", 2L, "signatureMaxAgeSeconds", 300L)));
        Map<String, Object> root = Map.of("authority", Map.of("listenerId", "main", "pathPrefix", "/managed/slack",
                        "requiredScopes", List.of("slack:callbacks"), "maxRoutes", 8L, "maxConcurrentRequests", 32L,
                        "maxRequestBytes", 1_048_576L, "maxResponseBytes", 65_536L, "requestTimeoutMs", 2_800L),
                "projection", Map.of("maxRelativePathBytes", 256L, "maxQueryParameters", 1L,
                        "maxQueryBytes", 256L, "maxHeaderCount", 5L, "maxHeaderBytes", 1024L,
                        "maxHeaderValueBytes", 512L),
                "store", Map.of("path", directory.resolve("environment.db").toString(),
                        "maxDeliveries", 100L, "retentionHours", 24L),
                "profiles", Map.of(SlackTestSupport.PROFILE, profile));
        String encoded = Base64.getEncoder().encodeToString(SlackValues.jsonBytes(root));
        SlackProfile resolved = SlackConfiguration.fromEnvironment(Map.of(SlackConfiguration.ENVIRONMENT, encoded))
                .profile(SlackTestSupport.TENANT, SlackTestSupport.PROFILE).orElseThrow();
        assertEquals(SlackTestSupport.TEAM, resolved.teamId());
        assertEquals(Set.of("chat:write", "commands"), resolved.scopes());
    }
    @Test void profileIsTenantBoundAndRequiresProductionAuthority() {
        SlackConfiguration configuration = SlackTestSupport.configuration(directory.resolve("slack.db"));
        SlackProfile profile = configuration.profile(SlackTestSupport.TENANT, SlackTestSupport.PROFILE).orElseThrow();
        assertEquals(SlackProfile.PRODUCTION_ORIGIN, profile.apiOrigin());
        assertTrue(profile.permitsChannel(SlackTestSupport.CHANNEL));
        assertEquals("slack-bot-token", profile.credential().reference());
        assertTrue(configuration.profile("tenant-b", SlackTestSupport.PROFILE).isEmpty());
        assertThrows(SlackException.class, () -> new SlackProfile(profile.tenantId(), profile.name(),
                java.net.URI.create("https://example.invalid"), profile.teamId(), profile.applicationId(),
                profile.credentialBindingId(), profile.credentialReference(), profile.signingSecretReference(),
                profile.eventsRoute(), profile.commandsRoute(), profile.channelIds(), profile.eventTypes(),
                profile.commands(), profile.scopes(), profile.requestTimeoutMs(), profile.maxRequestBytes(),
                profile.maxResponseBytes(), profile.maxTextChars(), profile.maxConcurrency(), profile.maxPerSecond(),
                profile.retries(), profile.signatureMaxAgeSeconds()));
    }
    @Test void rejectsProfileRequestCeilingAboveIngressAuthority() {
        SlackException failure = assertThrows(SlackException.class,
                () -> SlackTestSupport.configuration(directory.resolve("oversized.db"), 1024, 512));
        assertEquals(SlackException.Code.CONFIGURATION, failure.code());
    }
    @Test void rejectsRoutesDuplicatedAcrossProfiles() {
        SlackConfiguration original = SlackTestSupport.configuration(directory.resolve("duplicate.db"));
        SlackProfile first = original.profile(SlackTestSupport.TENANT, SlackTestSupport.PROFILE).orElseThrow();
        SlackProfile second = copy(first, "secondary", first.eventsRoute(), "/secondary-commands");
        assertThrows(SlackException.class, () -> new SlackConfiguration(original.authority(), original.projection(),
                original.store(), Map.of(SlackTestSupport.TENANT + "\u0000" + SlackTestSupport.PROFILE, first,
                        SlackTestSupport.TENANT + "\u0000secondary", second)));
    }
    @Test void rejectsEventTypesWithoutAnExplicitAuthoritySchema() {
        SlackConfiguration original = SlackTestSupport.configuration(directory.resolve("event-schema.db"));
        SlackProfile profile = original.profile(SlackTestSupport.TENANT, SlackTestSupport.PROFILE).orElseThrow();
        assertThrows(SlackException.class, () -> new SlackProfile(profile.tenantId(), profile.name(),
                profile.apiOrigin(), profile.teamId(), profile.applicationId(), profile.credentialBindingId(),
                profile.credentialReference(), profile.signingSecretReference(), profile.eventsRoute(),
                profile.commandsRoute(), profile.channelIds(), Set.of("reaction_added"), profile.commands(),
                profile.scopes(), profile.requestTimeoutMs(), profile.maxRequestBytes(), profile.maxResponseBytes(),
                profile.maxTextChars(), profile.maxConcurrency(), profile.maxPerSecond(), profile.retries(),
                profile.signatureMaxAgeSeconds()));
    }
    private static SlackProfile copy(SlackProfile profile, String name, String eventsRoute, String commandsRoute) {
        return new SlackProfile(profile.tenantId(), name, profile.apiOrigin(), profile.teamId(), profile.applicationId(),
                profile.credentialBindingId(), profile.credentialReference(), profile.signingSecretReference(),
                eventsRoute, commandsRoute, profile.channelIds(), profile.eventTypes(), profile.commands(), profile.scopes(),
                profile.requestTimeoutMs(), profile.maxRequestBytes(), profile.maxResponseBytes(), profile.maxTextChars(),
                profile.maxConcurrency(), profile.maxPerSecond(), profile.retries(), profile.signatureMaxAgeSeconds());
    }
}
