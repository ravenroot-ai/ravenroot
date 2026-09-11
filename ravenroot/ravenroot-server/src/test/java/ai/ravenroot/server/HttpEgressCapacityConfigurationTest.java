package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpEgressCapacityConfigurationTest {

    @Test
    void absentAndBlankDelegateWhilePositiveValuesAreTrimmed() {
        assertEquals(0, RavenrootServerMain.byteCeiling(Map.of(), "RAVENROOT_HTTP_MAX_REQUEST_BYTES"));
        assertEquals(0, RavenrootServerMain.byteCeiling(
                Map.of("RAVENROOT_HTTP_MAX_REQUEST_BYTES", " \t"),
                "RAVENROOT_HTTP_MAX_REQUEST_BYTES"));
        assertEquals(8192, RavenrootServerMain.byteCeiling(
                Map.of("RAVENROOT_HTTP_MAX_REQUEST_BYTES", " 8192 "),
                "RAVENROOT_HTTP_MAX_REQUEST_BYTES"));
    }

    @Test
    void malformedAndNonpositiveValuesRefuseWithoutEchoingRawInput() {
        for (String value : java.util.List.of("0", "-1", "not-a-number")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RavenrootServerMain.byteCeiling(
                            Map.of("RAVENROOT_HTTP_MAX_REQUEST_BYTES", value),
                            "RAVENROOT_HTTP_MAX_REQUEST_BYTES"));
            assertEquals("Invalid byte ceiling configuration: RAVENROOT_HTTP_MAX_REQUEST_BYTES",
                    failure.getMessage());
            assertFalse(failure.getMessage().contains(value));
        }
    }
}
