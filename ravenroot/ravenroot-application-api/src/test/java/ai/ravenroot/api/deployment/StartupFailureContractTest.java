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

    @Test
    void defaultStructuredLogProjectionContainsOnlySafeFields() {
        StartupFailure failure = StartupFailure.declared(GraphAdmissionPhase.SOURCE_START,
                "imap-folder-not-authorized",
                "listener;host=private.example;profile=prod;url=https://operator:pw@inside.example/path;"
                        + "password=hunter2",
                "incident:1234");
        String message = StartupFailureSink.safeLogMessage(
                DeploymentId.of("deploy;host=control.internal;profile=production"), failure,
                failure.nodeId());

        assertTrue(message.contains("phase=SOURCE_START"), message);
        assertTrue(message.contains("reason=imap-folder-not-authorized"), message);
        assertTrue(message.contains("incident=incident:1234"), message);
        assertTrue(message.contains("redacted:host"), message);
        assertTrue(message.contains("redacted:profile"), message);
        for (String forbidden : java.util.List.of("private.example", "control.internal", "production",
                "profile=prod", "inside.example", "operator", "hunter2")) {
            assertFalse(message.contains(forbidden), message);
        }
    }
}
