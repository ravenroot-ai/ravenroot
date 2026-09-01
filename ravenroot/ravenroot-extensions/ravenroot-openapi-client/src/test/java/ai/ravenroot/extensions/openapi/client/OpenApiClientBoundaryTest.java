package ai.ravenroot.extensions.openapi.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiClientBoundaryTest {
    @Test void productionImportsOnlyPublishedApplicationApiAndJdkTypes() throws Exception {
        Path source = Path.of("src/main/java");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("import ai.ravenroot.core"), file.toString());
                assertFalse(text.contains("import ai.ravenroot.server"), file.toString());
                assertFalse(text.contains("java.net.http.HttpClient"), file.toString());
                assertFalse(text.contains("Class.forName"), file.toString());
            }
        }
    }

    @Test void packageDeclaresTheManagedHttpCapabilityAndNoGeneratedClient() {
        var behavior = new OpenApiClientNodePackage().behaviors().getFirst();
        assertEquals(java.util.Set.of(ai.ravenroot.api.node.service.NodePackageCapability.OUTBOUND_HTTP),
                behavior.requiredServices());
        assertEquals("ravenroot.node-sdk/2", new OpenApiClientNodePackage().sdkContract());
        assertFalse(Files.exists(Path.of("src/main/generated")));
    }

    @Test void extensionIsAbsentFromTheDefaultDistribution() throws Exception {
        String distribution = Files.readString(Path.of("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-openapi-client"));
        assertFalse(distribution.contains("ai.ravenroot.extensions.openapi.client"));
    }
}
