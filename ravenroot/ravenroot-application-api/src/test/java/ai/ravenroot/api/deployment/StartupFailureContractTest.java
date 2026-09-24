package ai.ravenroot.api.deployment;

import ai.ravenroot.api.application.GraphAdmissionPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupFailureContractTest {
    @Test
    void declaredFailureIsBoundedAndCarriesOpaqueLocator() {
        StartupFailure failure = StartupFailure.declared(GraphAdmissionPhase.SOURCE_START,
                "imap-folder-not-authorized", "node\nsecret=password=hunter2", "incident:1234");
        assertEquals("imap-folder-not-authorized", failure.reason());
        assertTrue(failure.nodeRef().orElseThrow().matches("sha256:[0-9a-f]{32}"));
        assertFalse(failure.nodeId().orElseThrow().contains("hunter2"));
    }

    @Test
    void arbitraryCodesCannotEnterTheContract() {
        assertThrows(IllegalArgumentException.class, () -> StartupFailure.declared(
                GraphAdmissionPhase.SOURCE_START, "host.example.com/password=hunter2", "n", "incident:1"));
    }
}
