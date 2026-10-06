package ai.ravenroot.extensions.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamsConfigurationTest {
    @TempDir Path directory;

    @Test void strictEnvironmentDocumentBuildsTenantScopedAuthority() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("tenantId", TeamsTestSupport.TENANT);
        profile.put("workflowEndpoint", TeamsTestSupport.WORKFLOW.toString());
        profile.put("microsoftTenantId", TeamsTestSupport.MICROSOFT_TENANT);
        profile.put("teamId", TeamsTestSupport.TEAM);
        profile.put("channels", List.of(TeamsTestSupport.CHANNEL));
        profile.put("credentialBindingId", "teams-workflow");
        profile.put("credentialReference", "teams-workflow-token");
        profile.put("signingSecretReference", "teams-signing-secret");
        profile.put("webhookRoute", "/outgoing");
        profile.put("limits", Map.of("requestTimeoutMs", 2500L, "maxRequestBytes", 1048576L,
                "maxResponseBytes", 65536L, "maxTextChars", 4000L, "maxConcurrency", 2L,
                "maxPerSecond", 20L, "ackTimeoutMs", 4000L, "signatureMaxAgeSeconds", 300L));
        Map<String, Object> root = Map.of(
                "authority", Map.of("listenerId", "main", "pathPrefix", "/managed/teams",
                        "requiredScopes", List.of("teams:callbacks"), "maxRoutes", 8L,
                        "maxConcurrentRequests", 32L, "maxRequestBytes", 1048576L,
                        "maxResponseBytes", 65536L, "requestTimeoutMs", 4500L),
                "projection", Map.of("maxRelativePathBytes", 256L, "maxQueryParameters", 1L,
                        "maxQueryBytes", 256L, "maxHeaderCount", 2L, "maxHeaderBytes", 1024L,
                        "maxHeaderValueBytes", 512L),
                "store", Map.of("path", directory.resolve("deliveries.db").toString(),
                        "maxDeliveries", 100L, "retentionHours", 24L),
                "profiles", Map.of(TeamsTestSupport.PROFILE, profile));
        String encoded = Base64.getEncoder().encodeToString(TeamsValues.jsonBytes(root));
        TeamsConfiguration configuration = TeamsConfiguration.fromEnvironment(
                Map.of(TeamsConfiguration.ENVIRONMENT, encoded));
        TeamsProfile actual = configuration.profile(TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE).orElseThrow();
        assertEquals(TeamsTestSupport.WORKFLOW, actual.workflowEndpoint());
        assertEquals(TeamsTestSupport.MICROSOFT_TENANT, actual.microsoftTenantId());
        assertEquals("/outgoing", actual.webhookRoute());
        assertEquals(java.util.Set.of("content-type", "x-ravenroot-teams-signature"),
                configuration.projection().allowedHeaders());
    }

    @Test void rejectsUnknownFieldsQuerySecretsAndAuthorityWidening() {
        TeamsConfiguration configuration = TeamsTestSupport.configuration(directory.resolve("base.db"));
        TeamsProfile first = configuration.profiles().values().iterator().next();
        assertThrows(TeamsException.class, () -> new TeamsProfile(first.tenantId(), first.name(),
                java.net.URI.create(first.workflowEndpoint() + "?sig=secret"), first.microsoftTenantId(), first.teamId(),
                first.channelIds(), first.credentialBindingId(), first.credentialReference(),
                first.signingSecretReference(), first.webhookRoute(), first.requestTimeoutMs(),
                first.maxRequestBytes(), first.maxResponseBytes(), first.maxTextChars(), first.maxConcurrency(),
                first.maxPerSecond(), first.ackTimeoutMs(), first.signatureMaxAgeSeconds()));
        assertDoesNotThrow(() -> new TeamsProfile(first.tenantId(), first.name(), first.workflowEndpoint(),
                first.microsoftTenantId(), first.teamId(), first.channelIds(), first.credentialBindingId(),
                first.credentialReference(), first.signingSecretReference(), first.webhookRoute(),
                first.requestTimeoutMs(), first.maxRequestBytes(), first.maxResponseBytes(), first.maxTextChars(),
                first.maxConcurrency(), first.maxPerSecond(), TeamsProfile.MAX_ACK_TIMEOUT_MS,
                first.signatureMaxAgeSeconds()));
        assertThrows(TeamsException.class, () -> new TeamsProfile(first.tenantId(), first.name(),
                first.workflowEndpoint(), first.microsoftTenantId(), first.teamId(), first.channelIds(),
                first.credentialBindingId(), first.credentialReference(), first.signingSecretReference(),
                first.webhookRoute(), first.requestTimeoutMs(), first.maxRequestBytes(), first.maxResponseBytes(),
                first.maxTextChars(), first.maxConcurrency(), first.maxPerSecond(),
                TeamsProfile.MAX_ACK_TIMEOUT_MS + 1, first.signatureMaxAgeSeconds()));
        String invalid = Base64.getEncoder().encodeToString(
                "{\"authority\":{},\"projection\":{},\"store\":{},\"profiles\":{},\"extra\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(TeamsException.class, () -> TeamsConfiguration.fromEnvironment(
                Map.of(TeamsConfiguration.ENVIRONMENT, invalid)));
        assertThrows(TeamsException.class, () -> TeamsTestSupport.configuration(directory.resolve("wide.db"),
                1024, 512));
    }

    @Test void administratorRuleAuthorizesOnlyConfigurationBuiltHttpProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("tenantId", TeamsTestSupport.TENANT);
        profile.put("workflowEndpoint", "http://127.0.0.1/workflows/operations");
        profile.put("microsoftTenantId", TeamsTestSupport.MICROSOFT_TENANT);
        profile.put("teamId", TeamsTestSupport.TEAM);
        profile.put("channels", List.of(TeamsTestSupport.CHANNEL));
        profile.put("credentialBindingId", "teams-workflow");
        profile.put("credentialReference", "teams-workflow-token");
        profile.put("signingSecretReference", "teams-signing-secret");
        profile.put("webhookRoute", "/outgoing");
        profile.put("limits", Map.of("requestTimeoutMs", 2500L, "maxRequestBytes", 1048576L,
                "maxResponseBytes", 65536L, "maxTextChars", 4000L, "maxConcurrency", 2L,
                "maxPerSecond", 20L, "ackTimeoutMs", 4000L, "signatureMaxAgeSeconds", 300L));
        Map<String, Object> root = Map.of(
                "authority", Map.of("listenerId", "main", "pathPrefix", "/managed/teams",
                        "requiredScopes", List.of("teams:callbacks"), "maxRoutes", 8L,
                        "maxConcurrentRequests", 32L, "maxRequestBytes", 1048576L,
                        "maxResponseBytes", 65536L, "requestTimeoutMs", 4500L),
                "projection", Map.of("maxRelativePathBytes", 256L, "maxQueryParameters", 1L,
                        "maxQueryBytes", 256L, "maxHeaderCount", 2L, "maxHeaderBytes", 1024L,
                        "maxHeaderValueBytes", 512L),
                "store", Map.of("path", directory.resolve("http.db").toString(),
                        "maxDeliveries", 100L, "retentionHours", 24L),
                "profiles", Map.of(TeamsTestSupport.PROFILE, profile));
        String policy = """
                {"version":1,"rules":[{"name":"teams-mesh","protocols":["http"],"ports":[80],
                "hosts":["127.0.0.1"],"addresses":["127.0.0.0/8"],
                "profiles":["ai.ravenroot.extensions.teams"],"allowPlaintext":true}]}
                """.replaceAll("\\s+", "");
        TeamsConfiguration configuration = TeamsConfiguration.fromEnvironment(Map.of(
                TeamsConfiguration.ENVIRONMENT,
                Base64.getEncoder().encodeToString(TeamsValues.jsonBytes(root)),
                "RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY",
                Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8))));
        assertEquals(java.net.URI.create("http://127.0.0.1/workflows/operations"),
                configuration.profile(TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE)
                        .orElseThrow().workflowEndpoint());
    }
}
