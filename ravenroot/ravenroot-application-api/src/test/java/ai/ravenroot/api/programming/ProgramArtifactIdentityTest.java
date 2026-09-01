package ai.ravenroot.api.programming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramArtifactIdentityTest {
    @Test
    void bindsLanguageAndExactUtf8SourceWithDomainSeparation() {
        String canonical = ProgramArtifactIdentity.sha256("python", "print('€')");
        assertEquals(canonical, ProgramArtifactIdentity.sha256("python", "print('€')"));
        assertNotEquals(canonical, ProgramArtifactIdentity.sha256("javascript", "print('€')"));
        assertNotEquals(canonical, ProgramArtifactIdentity.sha256("python", "print('E')"));
        assertNotEquals(ProgramArtifactIdentity.sha256("ab", "c"), ProgramArtifactIdentity.sha256("a", "bc"));
    }

    @Test
    void enforcesTheSourceCeilingOnExactUtf8Bytes() {
        ProgramArtifactIdentity.sha256("javascript", "a".repeat(ProgramArtifactIdentity.MAX_SOURCE_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> ProgramArtifactIdentity.sha256("javascript",
                        "a".repeat(ProgramArtifactIdentity.MAX_SOURCE_BYTES - 1) + "€"));
        assertThrows(IllegalArgumentException.class,
                () -> ProgramArtifactIdentity.sha256("javascript", ""));
    }
}
