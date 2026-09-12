package ai.ravenroot.server.audit;

import ai.ravenroot.core.audit.AuditTrailDirectory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditTrailConfigurationTest {

    @Test
    void serverCompositionUsesTheSharedDefaultBlankAndTrimRules() {
        assertEquals(Path.of(AuditTrailDirectory.DEFAULT_DIRECTORY),
                AuditTrailConfiguration.fromEnvironment(Map.of()).directory().path());
        for (String blank : new String[] {"", "  ", "\t"}) {
            assertEquals(Path.of(AuditTrailDirectory.DEFAULT_DIRECTORY),
                    AuditTrailConfiguration.fromEnvironment(Map.of(
                            AuditTrailConfiguration.DIRECTORY_VARIABLE, blank)).directory().path());
        }
        assertEquals(Path.of("/srv/ravenroot/audit"),
                AuditTrailConfiguration.fromEnvironment(Map.of(
                        AuditTrailConfiguration.DIRECTORY_VARIABLE,
                        "  /srv/ravenroot/audit  ")).directory().path());
    }

    @Test
    void malformedServerAuditDirectoryIsRefusedBeforeOpeningTheTrail() {
        assertThrows(IllegalArgumentException.class, () -> AuditTrailConfiguration.fromEnvironment(
                Map.of(AuditTrailConfiguration.DIRECTORY_VARIABLE, "bad\0path")));
    }
}
