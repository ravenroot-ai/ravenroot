package ai.ravenroot.extensions.github;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GithubBoundaryTest {
    @TempDir Path directory;

    @Test void packageExposesExactlyTheFiveIssueBehaviors() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        assertEquals(GithubConfiguration.PACKAGE_ID, nodePackage.id());
        assertEquals(NodeSdk.CONTRACT, nodePackage.sdkContract());
        assertEquals(Set.of("github-events-source", "project-transition", "github-app-review",
                        "github-workflow-watch", "release-prepare"), nodePackage.behaviors().stream()
                .map(value -> value.descriptor().behavior()).collect(Collectors.toSet()));
        assertEquals(Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                GithubTestSupport.behavior(nodePackage, "github-events-source").requiredServices());
        nodePackage.behaviors().stream().filter(value -> !value.descriptor().behavior().equals("github-events-source"))
                .forEach(value -> assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP), value.requiredServices()));
    }

    @Test void productionUsesOnlyTheSdkManagedServicesAndOneDiscoverablePackage() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertEquals(1, sources.stream().filter(path -> path.getFileName().toString().endsWith("NodePackage.java")).count());
            for (Path source : sources) {
                String text = Files.readString(source);
                assertFalse(text.contains("import ai.ravenroot.core"), source.toString());
                assertFalse(text.contains("import ai.ravenroot.server"), source.toString());
                assertFalse(text.contains("java.net.http.HttpClient"), source.toString());
                assertFalse(text.contains("ServiceLoader"), source.toString());
            }
        }
        String distribution = Files.readString(Path.of("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-github"));
    }

    @Test void releasePreparationHasNoMutatingWireMethod() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ai/ravenroot/extensions/github/ReleasePrepareBehavior.java"));
        assertFalse(source.contains("api.post("));
        assertFalse(source.contains("api.delete("));
        assertFalse(source.contains("api.graphql("));
        assertTrue(source.contains("api.get("));
    }
}
