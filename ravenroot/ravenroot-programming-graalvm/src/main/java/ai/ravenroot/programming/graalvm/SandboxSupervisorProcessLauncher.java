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

    // Fixed protocol/control-plane safety bounds, independent of execution budgets.
    private static final int MAX_CAPABILITY_BYTES = 256;
    private static final int CAPABILITY_TIMEOUT_SECONDS = 2;
    private static final String CACHE_QUERY = "--ravenroot-sandbox-supervisor-capabilities=resource-cache-v1";
    private static final String CACHE_BANNER = "ravenroot-sandbox-supervisor-resource-cache/1";

    @Override public void verifyCapability() throws IOException {
        probe("--ravenroot-sandbox-supervisor-capabilities=v1", "ravenroot-sandbox-supervisor/1", "SANDBOX_CAPABILITY_UNSUPPORTED");
    }

    @Override public void verifyPlacement(SandboxLaunchPlacement placement) throws IOException {
        if (placement == null) throw new IllegalArgumentException("Launch placement is required");
        if (placement.hasOverride()) probe(CACHE_QUERY, CACHE_BANNER, "SANDBOX_RESOURCE_CACHE_UNSUPPORTED");
    }

    private void probe(String query, String banner, String reason) throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) throw new IOException("SANDBOX_LAUNCHER_MISSING");
        Process process = start(List.of(query), true);
        var output = new java.io.ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CAPABILITY_TIMEOUT_SECONDS);
        try {
            while (true) {
                int available = process.getInputStream().available();
                if (available > 0) {
                    byte[] bytes = process.getInputStream().readNBytes(Math.min(available, MAX_CAPABILITY_BYTES + 1 - output.size()));
                    output.write(bytes);
                    if (output.size() > MAX_CAPABILITY_BYTES) throw new IOException(reason);
                    continue;
                }
                if (!process.isAlive()) break;
                if (System.nanoTime() >= deadline) throw new IOException(reason);
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
            if (process.exitValue() != 0 || !output.toString(java.nio.charset.StandardCharsets.UTF_8).strip().equals(banner))
                throw new IOException(reason);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(reason, interrupted);
        } finally {
            process.destroyForcibly();
            // An interrupted caller still owns the child until it is reaped. Clear the flag only
            // for this bounded cleanup, then restore it; otherwise waitFor would fail immediately.
            boolean interrupted = Thread.interrupted();
            long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CAPABILITY_TIMEOUT_SECONDS);
            try {
                while (process.isAlive()) {
                    long remaining = reapDeadline - System.nanoTime();
                    if (remaining <= 0) throw new IOException("SANDBOX_REAP_FAILED");
                    try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally {
                try { process.getInputStream().close(); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException {
        return new Session(start(policy.arguments(), false));
    }

    @Override public SandboxSupervisorSession launch(SandboxPolicy policy, SandboxLaunchPlacement placement) throws IOException {
        verifyPlacement(placement); // Direct callers cannot bypass extension attestation.
        if (!placement.hasOverride()) return launch(policy);
        var arguments = new ArrayList<>(policy.arguments());
        arguments.add("--ravenroot-sandbox-supervisor-extension=resource-cache-v1");
        arguments.add("--resource-cache-property=" + placement.resourceCachePropertyValue());
        return new Session(start(arguments, false));
    }

    private Process start(List<String> arguments, boolean probe) throws IOException {
        var command = new ArrayList<String>(); command.add(executable.toString()); command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        // A capability probe never needs diagnostics; discard them instead of creating another unbounded pipe.
        if (probe) builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
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
