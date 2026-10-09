package ai.ravenroot.server.authoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.net.http.HttpClient;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class GraphAuthoringConfigurationTest {
    @Test void localIsTheDefaultAndCarriesNoRemoteAuthority() {
        var configuration = GraphAuthoringConfiguration.fromEnvironment(Map.of());
        assertEquals(GraphAuthoringConfiguration.Mode.LOCAL, configuration.mode());
        assertEquals(GraphAuthoringConfiguration.Provider.NONE, configuration.provider());
        assertTrue(configuration.tenantNamespaces().isEmpty());
    }

    @Test void enterpriseApiPrefixAndExplicitTenantNamespacesArePreserved() {
        var configuration = GraphAuthoringConfiguration.fromEnvironment(gitEnvironment());
        assertEquals("https://github.example.test/api/v3", configuration.apiBase().toString());
        assertEquals("graphs/customers/a", configuration.tenantDirectory("tenant-a"));
        assertThrows(IllegalArgumentException.class, () -> configuration.tenantDirectory("tenant-b"));
        var source = new GithubTokenSource(configuration,
                ignored -> java.util.Optional.empty(), HttpClient.newHttpClient(), Clock.systemUTC());
        assertEquals("https://github.example.test/api/v3/app/installations/456/access_tokens",
                source.installationTokenUri().toString());
        assertTrue(configuration.capabilitiesJson().contains("artifact-import"));
        assertTrue(configuration.capabilitiesJson().contains("artifact-deploy"));
    }

    @Test void refusesCallerShapedTraversalInTrustedNamespaceConfiguration() {
        var environment = gitEnvironment();
        environment.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=../tenant-b");
        assertThrows(IllegalArgumentException.class,
                () -> GraphAuthoringConfiguration.fromEnvironment(environment));
    }

    @Test void refusesTenantMappingsThatWouldShareADraftBranch() {
        var environment = gitEnvironment();
        environment.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES",
                "tenant-a=customers/a,tenant-b=customers-a");
        assertThrows(IllegalArgumentException.class,
                () -> GraphAuthoringConfiguration.fromEnvironment(environment));
    }

    private static Map<String, String> gitEnvironment() {
        var value = new HashMap<String, String>();
        value.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        value.put("RAVENROOT_GRAPH_AUTHORING_PROVIDER", "GITHUB");
        value.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform");
        value.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs");
        value.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE", "https://github.example.test/api/v3");
        value.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "APP");
        value.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "git-authoring");
        value.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID", "123");
        value.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID", "456");
        value.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=customers/a");
        value.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/releases/");
        return value;
    }
}
