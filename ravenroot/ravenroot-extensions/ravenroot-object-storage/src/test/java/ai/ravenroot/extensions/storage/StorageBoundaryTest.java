package ai.ravenroot.extensions.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StorageBoundaryTest {
    @Test void productionImportsOnlyPublishedApplicationApiAndJdkTypes() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("import ai.ravenroot.core"), file.toString());
                assertFalse(text.contains("import ai.ravenroot.server"), file.toString());
                assertFalse(text.contains("java.net.http.HttpClient"), file.toString());
                assertFalse(text.contains("aws-sdk"), file.toString());
                assertFalse(text.contains("Authorization"), file.toString());
            }
        }
    }

    @Test void packageDeclaresOnlyManagedHttpAndIsAbsentFromDefaultDistribution() throws Exception {
        StorageNodePackage nodePackage = new StorageNodePackage();
        assertEquals("ai.ravenroot.extensions.storage", nodePackage.id());
        assertEquals("ravenroot.node-sdk/2", nodePackage.sdkContract());
        nodePackage.behaviors().forEach(behavior -> assertEquals(
                Set.of(ai.ravenroot.api.node.service.NodePackageCapability.OUTBOUND_HTTP),
                behavior.requiredServices()));
        String distribution = Files.readString(Path.of("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-object-storage"));
        assertFalse(distribution.contains("ai.ravenroot.extensions.storage"));
    }

    @Test void packageExposesOnlyGetAndPut() {
        assertEquals(Set.of("object.get", "object.put"), new StorageNodePackage().behaviors().stream()
                .map(behavior -> behavior.descriptor().behavior()).collect(java.util.stream.Collectors.toSet()));
    }
}
