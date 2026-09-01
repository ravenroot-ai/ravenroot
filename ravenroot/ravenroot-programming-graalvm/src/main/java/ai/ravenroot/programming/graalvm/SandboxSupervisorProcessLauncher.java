package ai.ravenroot.programming.graalvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process adapter for the process-boundary design's external supervisor. It never launches a worker JVM itself. */
final class SandboxSupervisorProcessLauncher implements SandboxSupervisorLauncher {
    private final Path executable;

    SandboxSupervisorProcessLauncher(Path executable) { this.executable = executable.toAbsolutePath().normalize(); }

    /** The resolved, absolute path this launcher was configured with -- the "where" for a log line. */
    @Override public String describe() { return executable.toString(); }

    @Override public void verifyCapability() throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) throw new IOException("SANDBOX_LAUNCHER_MISSING");
        Process process = start(List.of("--ravenroot-sandbox-supervisor-capabilities=v1"));
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0
                    || !new String(process.getInputStream().readAllBytes()).strip().equals("ravenroot-sandbox-supervisor/1")) {
                throw new IOException("SANDBOX_CAPABILITY_UNSUPPORTED");
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("SANDBOX_CAPABILITY_UNSUPPORTED", error); }
        finally { process.destroyForcibly(); }
    }

    @Override public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException {
        return new Session(start(policy.arguments()));
    }

    private Process start(List<String> arguments) throws IOException {
        var command = new ArrayList<String>(); command.add(executable.toString()); command.addAll(arguments);
        var builder = new ProcessBuilder(command); builder.environment().clear(); return builder.start();
    }

    private static final class Session implements SandboxSupervisorSession {
        private final Process process;
        Session(Process process) { this.process = process; }
        @Override public java.io.OutputStream workerInput() { return process.getOutputStream(); }
        @Override public java.io.InputStream supervisorControl() { return process.getInputStream(); }
        @Override public java.io.InputStream diagnostics() { return process.getErrorStream(); }
        @Override public void terminate(SandboxTermination termination) throws IOException {
            process.destroy();
            try {
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("SANDBOX_REAP_FAILED");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("SANDBOX_REAP_FAILED", error);
            }
        }
        @Override public SandboxOutcome await(Duration remaining) throws Exception {
            if (!process.waitFor(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS)) return SandboxOutcome.DEADLINE_EXCEEDED;
            return process.exitValue() == 0 ? SandboxOutcome.COMPLETED : SandboxOutcome.SETUP_FAILURE;
        }
        @Override public void close() { process.destroyForcibly(); }
    }
}
