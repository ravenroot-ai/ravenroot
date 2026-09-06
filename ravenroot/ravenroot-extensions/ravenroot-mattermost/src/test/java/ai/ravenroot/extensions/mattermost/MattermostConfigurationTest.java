package ai.ravenroot.extensions.mattermost;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MattermostConfigurationTest {
    @Test void parsesMultipleStrictTenantProfilesAndDeclaresBodyOnlyAuthenticationProjection() {
        String json = configuration("operations", MattermostTestSupport.TENANT,
                MattermostTestSupport.TEAM, MattermostTestSupport.CHANNEL, "/outgoing");
        MattermostConfiguration value = MattermostConfiguration.fromEnvironment(Map.of(
                MattermostConfiguration.ENVIRONMENT,
                Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        assertEquals("operations", value.profiles().values().iterator().next().name());
        assertEquals(Set.of("content-type"), value.projection().allowedHeaders());
        assertFalse(value.projection().allowedHeaders().contains("authorization"));
    }

    @Test void rejectsUnknownFieldsDuplicateRoutesAndOriginPaths() {
        String unknown = configuration("operations", MattermostTestSupport.TENANT,
                MattermostTestSupport.TEAM, MattermostTestSupport.CHANNEL, "/outgoing")
                .replace("\"store\":{", "\"unknown\":true,\"store\":{");
        assertInvalid(unknown);
        assertThrows(MattermostException.class, () -> new MattermostProfile(MattermostTestSupport.TENANT,
                "operations", java.net.URI.create("https://mattermost.example.test/prefix"),
                MattermostTestSupport.TEAM, Set.of(MattermostTestSupport.CHANNEL), "binding", "credential",
                "webhook", "/outgoing", 4_000, 1_024, 1_024, 1, 1, 1_000, 0));
    }

    static String configuration(String name, String tenant, String team, String channel, String route) {
        return """
                {"authority":{"listenerId":"main","pathPrefix":"/managed/mattermost",
                "requiredScopes":["mattermost:callbacks"],"maxRoutes":8,"maxConcurrentRequests":32,
                "maxRequestBytes":1048576,"maxResponseBytes":65536,"requestTimeoutMs":2800},
                "projection":{"maxRelativePathBytes":256,"maxQueryParameters":1,"maxQueryBytes":256,
                "maxHeaderCount":1,"maxHeaderBytes":1024,"maxHeaderValueBytes":512},
                "store":{"path":"deliveries.db","maxDeliveries":100,"retentionHours":24},
                "profiles":{"%s":{"tenantId":"%s","origin":"https://mattermost.example.test",
                "teamId":"%s","publicChannels":["%s"],"credentialBindingId":"mattermost-bearer",
                "credentialReference":"mattermost-bot-token","webhookTokenReference":"mattermost-outgoing-token",
                "outgoingWebhookRoute":"%s","limits":{"maxTextChars":4000,"maxRequestBytes":1048576,
                "maxResponseBytes":65536,"maxConcurrency":2,"maxPerSecond":20,"requestTimeoutMs":2500,
                "retries":1}}}}
                """.formatted(name, tenant, team, channel, route);
    }
    private static void assertInvalid(String json) {
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        MattermostException failure = assertThrows(MattermostException.class,
                () -> MattermostConfiguration.fromEnvironment(Map.of(MattermostConfiguration.ENVIRONMENT, encoded)));
        assertEquals(MattermostException.Code.CONFIGURATION, failure.code());
    }
}
