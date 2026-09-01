package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentOcrProfileResolverTest {
    @Test void tenantAndProfileAreInjectivelyEncodedAndResolvedAsOperatorAuthority() {
        String key = EnvironmentOcrProfileResolver.variableName("tenant-a", "local");
        String collisionCandidate = EnvironmentOcrProfileResolver.variableName("tenant", "a_local");
        assertNotEquals(key, collisionCandidate);
        String value = "/opt/ocr/tesseract;/opt/ocr/tessdata;eng,ita;/var/run/ravenroot-ocr;5000;4096;8192;2;100";

        OcrProfile profile = new EnvironmentOcrProfileResolver(Map.of(key, value))
                .resolve("tenant-a", "local").orElseThrow();

        assertEquals("tenant-a", profile.tenantId());
        assertTrue(profile.permitsLanguage("eng"));
        assertFalse(profile.permitsLanguage("fra"));
        assertEquals(4096, profile.maxInputBytes());
        assertEquals(2, profile.maxConcurrency());
    }

    @Test void malformedRelativeOrOutOfRangeProfilesAreUnavailableWithoutLeakingDetails() {
        String key = EnvironmentOcrProfileResolver.variableName("tenant-a", "local");
        assertTrue(new EnvironmentOcrProfileResolver(Map.of(key,
                "relative;tessdata;eng;tmp;5000;4096;8192;2;100"))
                .resolve("tenant-a", "local").isEmpty());
        assertTrue(new EnvironmentOcrProfileResolver(Map.of(key,
                "/bin/tesseract;/data;eng;/tmp;5000;4096;8192;999;100"))
                .resolve("tenant-a", "local").isEmpty());
        assertTrue(new EnvironmentOcrProfileResolver(Map.of(key,
                "/bin/tesseract;/data;eng,/tmp;5000;4096;8192;2;100"))
                .resolve("tenant-a", "local").isEmpty());
    }

    @Test void unsafeTenantOrProfileNeverIndexesTheEnvironment() {
        var resolver = new EnvironmentOcrProfileResolver(Map.of());
        assertTrue(resolver.resolve("../tenant", "local").isEmpty());
        assertTrue(resolver.resolve("tenant-a", "local;inject").isEmpty());
    }
}
