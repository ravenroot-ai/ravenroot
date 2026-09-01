package ai.ravenroot.extensions.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentFilesystemProfileResolverTest {
    @TempDir Path root;

    @Test void profileKeyIsInjectivelyTenantAndProfileScoped() throws Exception {
        String key = EnvironmentFilesystemProfileResolver.environmentVariableName("tenant-a", "workspace");
        var resolver = new EnvironmentFilesystemProfileResolver(Map.of(key,
                root.toAbsolutePath() + ";true;false;documents/**;4096;3;2500"));
        FilesystemProfile profile = resolver.resolve("tenant-a", "workspace").orElseThrow();
        assertEquals(root.toRealPath(), profile.root());
        assertEquals(4096, profile.maxBytes());
        assertEquals(3, profile.maxConcurrency());
        assertTrue(profile.read());
        assertTrue(resolver.resolve("tenant-b", "workspace").isEmpty());
    }

    @Test void malformedProfilesAndIdentifiersFailClosed() {
        String key = EnvironmentFilesystemProfileResolver.environmentVariableName("tenant-a", "workspace");
        var resolver = new EnvironmentFilesystemProfileResolver(Map.of(key, root + ";yes;true;**;1;1;1"));
        assertTrue(resolver.resolve("tenant-a", "workspace").isEmpty());
        assertTrue(resolver.resolve("../tenant", "workspace").isEmpty());
        assertTrue(resolver.resolve("tenant-a", "\ud800").isEmpty());
    }
}
