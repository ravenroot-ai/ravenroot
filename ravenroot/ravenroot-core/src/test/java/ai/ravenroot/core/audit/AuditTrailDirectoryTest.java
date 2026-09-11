package ai.ravenroot.core.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditTrailDirectoryTest {

    @Test
    void absentAndBlankValuesResolveToTheSoleDefault() {
        for (String raw : new String[] {"", "  ", "\t"}) {
            assertEquals(Path.of(AuditTrailDirectory.DEFAULT_DIRECTORY),
                    AuditTrailDirectory.resolve(raw).path());
        }
        assertEquals(Path.of(AuditTrailDirectory.DEFAULT_DIRECTORY),
                AuditTrailDirectory.resolve(null).path());
    }

    @Test
    void aConfiguredPathIsTrimmedOnceBeforeParsing() {
        assertEquals(Path.of("/srv/ravenroot/audit"),
                AuditTrailDirectory.resolve("  /srv/ravenroot/audit  ").path());
    }

    @Test
    void anInvalidPathIsRefusedWithoutRepeatingIt() {
        String invalid = "secret-prefix\0secret-suffix";

        var failure = assertThrows(IllegalArgumentException.class,
                () -> AuditTrailDirectory.resolve(invalid));

        assertEquals("Invalid audit directory configuration", failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-prefix"));
        assertNull(failure.getCause());
    }
}
