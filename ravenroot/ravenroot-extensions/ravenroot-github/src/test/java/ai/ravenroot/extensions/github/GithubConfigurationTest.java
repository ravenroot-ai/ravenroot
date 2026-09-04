package ai.ravenroot.extensions.github;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
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

    private static String json() {
        return """
                {"authority":{"listenerId":"main","pathPrefix":"/managed/github","requiredScopes":["github:webhook"],"maxRoutes":8,"maxConcurrentRequests":32,"maxRequestBytes":1048576,"maxResponseBytes":4096,"requestTimeoutMs":5000},"projection":{"maxRelativePathBytes":256,"maxQueryParameters":1,"maxQueryBytes":256,"maxHeaderCount":3,"maxHeaderBytes":1024,"maxHeaderValueBytes":512},"store":{"path":"target/github-config-test.db","maxOperations":100,"retentionHours":24,"leaseMs":1000},"profiles":{"automation":{"tenantId":"tenant-a","apiOrigin":"https://api.github.com","owner":"example","repository":"service","repositoryId":1234,"installationId":5678,"reviewerLogin":"example-reviewer[bot]","credentialBindingId":"github-installation","credentialReference":"github-installation-token","webhookSecretReference":"github-webhook-secret","route":"/automation","events":{"pull_request":["opened"],"workflow_run":["completed"]},"project":{"projectId":"PVT_example","statusFieldId":"PVTSSF_status","attemptsFieldId":"PVTF_attempts","generationFieldId":"PVTF_generation","statusOptions":{"Todo":"todo-id","InProgress":"progress-id","Done":"done-id"},"allowedTransitions":["Todo->InProgress","InProgress->Done"],"claimTransition":"Todo->InProgress"},"workflowIds":[1001,1002],"release":{"branch":"main","versionPath":"ravenroot/pom.xml","fragmentsPath":".changes","allowedKinds":["none","patch","minor","major"],"maxFiles":256},"limits":{"timeoutMs":5000,"maxRequestBytes":1048576,"maxResponseBytes":1048576,"maxConcurrency":8,"maxPolls":4,"pollIntervalMs":1}}}}
                """;
    }
}
