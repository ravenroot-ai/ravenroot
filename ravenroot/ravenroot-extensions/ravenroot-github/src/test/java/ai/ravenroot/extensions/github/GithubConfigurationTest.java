package ai.ravenroot.extensions.github;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GithubConfigurationTest {
    @Test void documentedShapeAcceptsNumericWorkflowIdsAndBotLogin() {
        GithubConfiguration parsed = GithubConfiguration.fromEnvironment(Map.of(GithubConfiguration.ENVIRONMENT,
                Base64.getEncoder().encodeToString(json().getBytes(StandardCharsets.UTF_8))));
        GithubProfile profile = parsed.profile("tenant-a", "automation").orElseThrow();
        assertEquals(Set.of(1001L, 1002L), profile.workflowIds());
        assertEquals("Todo->InProgress", profile.project().claimTransition());
        assertEquals("example-reviewer[bot]", profile.reviewerLogin());
    }

    @Test void tenantCannotSelectAnotherTenantsProfile() {
        GithubConfiguration parsed = GithubConfiguration.fromEnvironment(Map.of(GithubConfiguration.ENVIRONMENT,
                Base64.getEncoder().encodeToString(json().getBytes(StandardCharsets.UTF_8))));
        assertTrue(parsed.profile("tenant-a", "automation").isPresent());
        assertTrue(parsed.profile("tenant-b", "automation").isEmpty());
    }

    @Test void unknownFieldsAndNonCanonicalBase64FailClosed() {
        String encoded = Base64.getEncoder().encodeToString(json().getBytes(StandardCharsets.UTF_8));
        assertThrows(GithubException.class, () -> GithubConfiguration.fromEnvironment(Map.of(
                GithubConfiguration.ENVIRONMENT, encoded.substring(0, encoded.length() - 1))));
        String widened = json().replace("\"profiles\":", "\"credential\":\"secret\",\"profiles\":");
        assertThrows(GithubException.class, () -> GithubConfiguration.fromEnvironment(Map.of(
                GithubConfiguration.ENVIRONMENT, Base64.getEncoder().encodeToString(widened.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test void propertyPresenceShadowsEnvironmentAndBlankSelectedValueRefuses() {
        String encoded = Base64.getEncoder().encodeToString(json().getBytes(StandardCharsets.UTF_8));
        var properties = new Properties();
        properties.setProperty(GithubConfiguration.PROPERTY, "");
        assertEquals(GithubException.Code.CONFIGURATION, assertThrows(GithubException.class,
                () -> GithubConfiguration.fromSystem(properties,
                        Map.of(GithubConfiguration.ENVIRONMENT, encoded))).code());
        properties.setProperty(GithubConfiguration.PROPERTY, encoded);
        assertTrue(GithubConfiguration.fromSystem(properties,
                Map.of(GithubConfiguration.ENVIRONMENT, "not-base64")).profile("tenant-a", "automation").isPresent());
        assertTrue(GithubConfiguration.fromSystem(new Properties(),
                Map.of(GithubConfiguration.ENVIRONMENT, encoded)).profile("tenant-a", "automation").isPresent());
        properties.put(GithubConfiguration.PROPERTY, new Object());
        assertEquals(GithubException.Code.CONFIGURATION, assertThrows(GithubException.class,
                () -> GithubConfiguration.fromSystem(properties,
                        Map.of(GithubConfiguration.ENVIRONMENT, encoded))).code());
    }

    @Test void storePolicyDirectConstructorEnforcesJsonBoundsAndPathRules() {
        assertThrows(GithubException.class, () -> new GithubConfiguration.StorePolicy(
                java.nio.file.Path.of("store.db"), 0, 24, 1_000));
        assertThrows(GithubException.class, () -> new GithubConfiguration.StorePolicy(
                java.nio.file.Path.of("store.db"), 1, 0, 1_000));
        assertThrows(GithubException.class, () -> new GithubConfiguration.StorePolicy(
                java.nio.file.Path.of("store.db"), 1, 24, 999));
        assertThrows(GithubException.class, () -> new GithubConfiguration.StorePolicy(
                java.nio.file.Path.of(""), 1, 24, 1_000));
        assertTrue(new GithubConfiguration.StorePolicy(java.nio.file.Path.of("target/../store.db"),
                1, 24, 1_000).path().isAbsolute());
    }

    @Test void profileContractDigestHasTaggedCanonicalCollectionsAndExcludesCredentials() {
        GithubProfile original = GithubTestSupport.configuration(java.nio.file.Path.of("target/contract.db"))
                .profile("tenant-a", "automation").orElseThrow();
        GithubProfile reordered = copy(original, original.owner(), "rotated-binding", "rotated-reference",
                Map.of("Done", "done-id", "Todo", "todo-id", "InProgress", "progress-id"));
        assertEquals(original.semanticContractDigest(), reordered.semanticContractDigest(),
                "map order and credential rotation do not alter durable operation semantics");
        GithubProfile changedRepository = copy(original, "another-owner", original.credentialBindingId(),
                original.credentialReference(), original.project().statusOptions());
        assertNotEquals(original.semanticContractDigest(), changedRepository.semanticContractDigest());
        GithubProfile changedProjectMap = copy(original, original.owner(), original.credentialBindingId(),
                original.credentialReference(), Map.of("Todo", "done-id", "InProgress", "progress-id",
                        "Done", "todo-id"));
        assertNotEquals(original.semanticContractDigest(), changedProjectMap.semanticContractDigest(),
                "named nested sections prevent collection-boundary collisions");
    }

    private static GithubProfile copy(GithubProfile value, String owner, String binding, String reference,
                                      Map<String, String> statusOptions) {
        var project = new GithubProfile.ProjectPolicy(value.project().projectId(), value.project().statusFieldId(),
                value.project().attemptsFieldId(), value.project().generationFieldId(), statusOptions,
                value.project().allowedTransitions(), value.project().claimTransition());
        return new GithubProfile(value.name(), value.tenantId(), value.apiOrigin(), owner, value.repository(),
                value.repositoryId(), value.installationId(), value.reviewerLogin(), binding, reference,
                value.webhookSecretReference(), value.route(), value.webhookEvents(), project, value.workflowIds(),
                value.release(), value.timeoutMs(), value.maxRequestBytes(), value.maxResponseBytes(),
                value.maxConcurrency(), value.maxPolls(), value.pollIntervalMs());
    }

    private static String json() {
        return """
                {"authority":{"listenerId":"main","pathPrefix":"/managed/github","requiredScopes":["github:webhook"],"maxRoutes":8,"maxConcurrentRequests":32,"maxRequestBytes":1048576,"maxResponseBytes":4096,"requestTimeoutMs":5000},"projection":{"maxRelativePathBytes":256,"maxQueryParameters":1,"maxQueryBytes":256,"maxHeaderCount":3,"maxHeaderBytes":1024,"maxHeaderValueBytes":512},"store":{"path":"target/github-config-test.db","maxOperations":100,"retentionHours":24,"leaseMs":1000},"profiles":{"automation":{"tenantId":"tenant-a","apiOrigin":"https://api.github.com","owner":"example","repository":"service","repositoryId":1234,"installationId":5678,"reviewerLogin":"example-reviewer[bot]","credentialBindingId":"github-installation","credentialReference":"github-installation-token","webhookSecretReference":"github-webhook-secret","route":"/automation","events":{"pull_request":["opened"],"workflow_run":["completed"]},"project":{"projectId":"PVT_example","statusFieldId":"PVTSSF_status","attemptsFieldId":"PVTF_attempts","generationFieldId":"PVTF_generation","statusOptions":{"Todo":"todo-id","InProgress":"progress-id","Done":"done-id"},"allowedTransitions":["Todo->InProgress","InProgress->Done"],"claimTransition":"Todo->InProgress"},"workflowIds":[1001,1002],"release":{"branch":"main","versionPath":"ravenroot/pom.xml","fragmentsPath":".changes","allowedKinds":["none","patch","minor","major"],"maxFiles":256},"limits":{"timeoutMs":5000,"maxRequestBytes":1048576,"maxResponseBytes":1048576,"maxConcurrency":8,"maxPolls":4,"pollIntervalMs":1}}}}
                """;
    }
}
