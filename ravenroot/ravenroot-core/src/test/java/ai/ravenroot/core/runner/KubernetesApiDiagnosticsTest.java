package ai.ravenroot.core.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual original subprocess failures; no cluster, remote retry or real credential is used. */
class KubernetesApiDiagnosticsTest {
    @TempDir Path directory;
    private static final String MARKER = "RR_ATTESTATION_FAILURE_V1:CGROUP:CPU_THROTTLING_MISSING\n";

    @Test void originalExitOneRetainsOnlyFiniteUnverifiedDiagnosticAndNeverRetries() throws Exception {
        var api = api(MARKER + "command terminated with exit code 1\n", "secret-stdout");
        var failure = assertThrows(KubernetesApi.OperationFailure.class, () -> api.attest("owned-pod"));
        assertEquals(KubernetesApi.FailureKind.UNAVAILABLE, failure.kind);
        assertEquals("Kubernetes operation UNAVAILABLE (client exit 1); effect remains unconfirmed; command=ATTEST diagnostic=ATTESTER_MARKER phase=CGROUP reason=CPU_THROTTLING_MISSING", failure.getMessage());
        assertEquals("1", Files.readString(directory.resolve("calls")));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.toString().contains(directory.toString()));
    }

    @Test void markerAbsenceDoesNotAssertTransportAndExistingAuthorizationCategoryIsPreserved() throws Exception {
        var api = api("Error from server (Forbidden): secret-token /private/path\n", "secret-stdout");
        var failure = assertThrows(KubernetesApi.OperationFailure.class, () -> api.attest("owned-pod"));
        assertEquals(KubernetesApi.FailureKind.AUTHORIZATION, failure.kind);
        assertTrue(failure.getMessage().contains("diagnostic=UNKNOWN phase=UNKNOWN reason=UNKNOWN"));
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("private"));
        assertFalse(failure.getMessage().contains("TRANSPORT"));
        assertEquals("1", Files.readString(directory.resolve("calls")));
    }

    @Test void malformedDuplicateForgedWrongOperationAndOversizedMarkersStayUnknown() throws Exception {
        var invalid = List.of("", "secret\n" + MARKER, MARKER + "secret\n", MARKER + MARKER,
                MARKER.strip(), MARKER.replace("V1", "V2"), MARKER.replace("CGROUP", "UNREVIEWED"),
                MARKER.replace("CPU_THROTTLING_MISSING", "SECRET"), MARKER.replace("CGROUP", "STORAGE"),
                MARKER.replace("CGROUP", "CONTROL"), MARKER.replace("CGROUP", "ARGUMENTS"),
                MARKER + "command terminated with exit code 2\n", "X".repeat(513),
                MARKER.replace("CGROUP", "CGROUP\u0000"));
        for (String value : invalid) {
            assertEquals(KubernetesApi.Diagnostic.UNKNOWN, KubernetesApi.attestationDiagnostic(
                    KubernetesApi.CommandKind.ATTEST, value.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(KubernetesApi.Diagnostic.UNKNOWN, KubernetesApi.attestationDiagnostic(
                KubernetesApi.CommandKind.OTHER, MARKER.getBytes(StandardCharsets.UTF_8)));
        assertEquals(KubernetesApi.Diagnostic.UNKNOWN, KubernetesApi.attestationDiagnostic(
                KubernetesApi.CommandKind.NETWORK_CONTROL, MARKER.getBytes(StandardCharsets.UTF_8)));
        var api = api(MARKER + "secret-extra\n", "secret-stdout");
        var original = assertThrows(KubernetesApi.OperationFailure.class, () -> api.attest("owned-pod"));
        assertTrue(original.getMessage().contains("diagnostic=UNKNOWN"));
        assertFalse(original.toString().contains("secret"));
        Files.writeString(directory.resolve("stderr"), MARKER);
        var wrongCommand = assertThrows(KubernetesApi.OperationFailure.class, () -> api.get("pod", "owned-pod"));
        assertTrue(wrongCommand.getMessage().contains("command=OTHER diagnostic=UNKNOWN"));
        assertEquals("11", Files.readString(directory.resolve("calls")));
    }

    @Test void networkMarkerIsDiagnosticOnlyForTheOriginalFixedNetworkCommand() throws Exception {
        var api = api("RR_ATTESTATION_FAILURE_V1:CONTROL:REFUSED\n", "");
        var failure = assertThrows(KubernetesApi.OperationFailure.class, () -> api.networkControl("owned-pod"));
        assertEquals(KubernetesApi.FailureKind.UNAVAILABLE, failure.kind);
        assertTrue(failure.getMessage().contains("command=NETWORK_CONTROL diagnostic=ATTESTER_MARKER phase=CONTROL reason=REFUSED"));
        assertEquals("1", Files.readString(directory.resolve("calls")));
    }

    @Test void existingStreamCeilingStillRefusesOversizedOriginalOutputWithoutDisclosingIt() throws Exception {
        var api = api("secret".repeat(11_000), "");
        var failure = assertThrows(Exception.class, () -> api.attest("owned-pod"));
        String messages = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) messages += cause.getMessage();
        assertTrue(messages.contains("Kubernetes stream exceeds its configured bound"));
        assertFalse(messages.contains("secret"));
        assertEquals("1", Files.readString(directory.resolve("calls")));
    }

    @Test void observedClientDeadlineDoesNotClaimAttesterRefusalOrAuthorizeRetry() throws Exception {
        var api = api("", "");
        Files.writeString(directory.resolve("wait"), "");
        var failure = assertThrows(KubernetesApi.OperationFailure.class, () -> api.attest("owned-pod"));
        assertEquals(KubernetesApi.FailureKind.UNAVAILABLE, failure.kind);
        assertTrue(failure.getMessage().contains("client exit -1"));
        assertTrue(failure.getMessage().contains("command=ATTEST diagnostic=CLIENT_DEADLINE phase=UNKNOWN reason=UNKNOWN"));
        assertEquals("1", Files.readString(directory.resolve("calls")));
    }

    private KubernetesApi api(String stderr, String stdout) throws Exception {
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"system:serviceaccount:fixture:manager\"}".getBytes(StandardCharsets.UTF_8));
        var config = Map.of("clusters", List.of(Map.of("cluster", Map.of("server", "https://fixture.invalid", "certificate-authority-data", "fixture"))),
                "users", List.of(Map.of("user", Map.of("token", "fixture." + claims + ".fixture"))), "contexts", List.of(Map.of()));
        Path kubeconfig = directory.resolve("configuration");
        Files.write(kubeconfig, RunnerJson.write(config));
        Files.setPosixFilePermissions(kubeconfig, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(directory.resolve("stderr"), stderr); Files.writeString(directory.resolve("stdout"), stdout);
        Path executable = directory.resolve("kubectl");
        Files.writeString(executable, """
                #!/usr/bin/env python3
                import pathlib, signal, sys
                root = pathlib.Path(__file__).parent
                if "config" in sys.argv:
                    sys.stdout.buffer.write((root / "configuration").read_bytes())
                else:
                    with (root / "calls").open("a") as stream:
                        stream.write("1")
                    if (root / "wait").exists():
                        signal.pause()
                    sys.stdout.buffer.write((root / "stdout").read_bytes())
                    sys.stderr.buffer.write((root / "stderr").read_bytes())
                    sys.exit(1)
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new KubernetesApi(executable, kubeconfig, "fixture", Duration.ofSeconds(2), 65_536);
    }
}
