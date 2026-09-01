package ai.ravenroot.extensions.openapi.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApiAdmissionRegistryTest {
    @Test void activePermitKeepsOneGateAcrossSourceStopAndSameKeyRestart() {
        OpenApiAdmissionRegistry registry = new OpenApiAdmissionRegistry();
        OpenApiAdmissionRegistry.Handle first = registry.open("tenant\u0000profile", 1);
        OpenApiAdmissionRegistry.Permit permit = first.tryAcquire();
        assertNotNull(permit);
        first.close();
        assertEquals(1, registry.size());
        OpenApiAdmissionRegistry.Handle replacement = registry.open("tenant\u0000profile", 1);
        assertNull(replacement.tryAcquire());
        permit.close();
        OpenApiAdmissionRegistry.Permit replacementPermit = replacement.tryAcquire();
        assertNotNull(replacementPermit);
        replacementPermit.close(); replacement.close();
        assertEquals(0, registry.size());
    }

    @Test void activeProfileConfigurationMismatchIsDeterministic() {
        OpenApiAdmissionRegistry registry = new OpenApiAdmissionRegistry();
        OpenApiAdmissionRegistry.Handle first = registry.open("tenant\u0000profile", 1);
        assertThrows(OpenApiServerException.class, () -> registry.open("tenant\u0000profile", 2));
        first.close();
        OpenApiAdmissionRegistry.Handle replacement = registry.open("tenant\u0000profile", 2);
        OpenApiAdmissionRegistry.Permit permit = replacement.tryAcquire();
        assertNotNull(permit);
        permit.close();
        replacement.close();
    }
}
