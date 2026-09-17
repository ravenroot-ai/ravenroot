package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceNativeEvidenceProbeTest {
    @Test void processListRetainsPidRequiredByDocker(@TempDir Path directory) throws Exception {
        Path docker = directory.resolve("docker");
        Files.writeString(docker, """
                #!/bin/sh
                test "$1" = top && test "$2" = owned-runtime && test "$3" = -eo && test "$4" = pid,args || exit 1
                printf 'PID COMMAND\\n123 python3 -m unittest discover -v\\n'
                """);
        assertTrue(docker.toFile().setExecutable(true));
        assertTrue(WorkspaceAgentRuntimeTest.nativeProcesses(directory, docker, "owned-runtime")
                .contains("123 python3 -m unittest discover -v"));
    }

    @Test void failedProbePreservesBoundedDiagnosticAndOperation(@TempDir Path directory) throws Exception {
        Path docker = directory.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\nprintf \"Couldn't find PID field in ps output\\n\" >&2\nexit 1\n");
        assertTrue(docker.toFile().setExecutable(true));
        var failure = assertThrows(AssertionError.class,
                () -> WorkspaceAgentRuntimeTest.nativeDocker(directory, docker, "top", "owned-runtime", "-eo", "args"));
        assertTrue(failure.getMessage().contains("native evidence probe failed (top)"));
        assertTrue(failure.getMessage().contains("Couldn't find PID field in ps output"));
    }

    @Test void actualLinuxContainerProcessListExposesTestChild(@TempDir Path directory) throws Exception {
        String runtime = System.getProperty("ravenroot.runner.testProbeContainer", "");
        assumeTrue(runtime.matches("[0-9a-f]{64}"), "requires the exact owned Linux process-probe fixture container");
        String processes = WorkspaceAgentRuntimeTest.nativeProcesses(directory,
                Path.of(System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker")), runtime);
        assertTrue(processes.matches("(?s)PID\\s+COMMAND.*"), processes);
        assertTrue(processes.contains("-m unittest discover -v"), "the real test child must be observable: " + processes);
    }

    @Test void failedProbeCannotFloodAssertionWithStderr(@TempDir Path directory) throws Exception {
        Path docker = directory.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\nprintf '" + "x".repeat(32_768) + "' >&2\nexit 1\n");
        assertTrue(docker.toFile().setExecutable(true));
        var failure = assertThrows(AssertionError.class,
                () -> WorkspaceAgentRuntimeTest.nativeDocker(directory, docker, "top", "owned-runtime"));
        assertTrue(failure.getMessage().contains("x".repeat(16_384)));
        assertTrue(failure.getMessage().length() < 17_000);
    }
}
