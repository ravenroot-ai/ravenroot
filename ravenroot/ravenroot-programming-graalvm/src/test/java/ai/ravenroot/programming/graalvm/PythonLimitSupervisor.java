package ai.ravenroot.programming.graalvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A supervisor that actually ENFORCES the numeric limits it is handed,
 * around a real {@link GraalVmWorkerMain} child JVM, so a Python artifact engineered to breach one
 * of them has something to be stopped by.
 *
 * <p><b>Why {@link FakeSupervisor} could not be used.</b> That fake enforces nothing: it spawns the
 * worker and reports {@code COMPLETED} whatever comes back. It is the right fixture for the tests it
 * serves, which assert what the RUNTIME does with a supervisor's answer. It is the wrong fixture for
 * a test asking whether a breach is stoppable at all, because it would answer {@code COMPLETED} for
 * every one of them and the suite would pass while proving nothing. Extending it instead of adding
 * this one would have put enforcement into a fixture whose Javadoc documents at length that it has
 * none.
 *
 * <p><b>What this is not.</b> It is not a model of the integrator's real supervisor, and no
 * assertion here should be read as a statement about one. {@code SandboxSupervisorContract} in
 * ravenroot-sandbox-supervisor-testkit is where a real supervisor is held to the limits, in a
 * deliberately language-neutral way. This class exists for the half that contract cannot cover: the
 * limits it enforces are the ones a JVM can enforce on its own child (a heap ceiling, a wall-clock
 * deadline, a byte count on the response), because the question here is not "does a supervisor
 * enforce this" but "can a PYTHON artifact reach this limit at all, or does something refuse it
 * earlier" -- and for three of the five, the measured answer turns out to be the second.
 */
final class PythonLimitSupervisor implements SandboxSupervisorLauncher {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * Hang guard, not a limit under test: how long this fixture is willing to wait for a child that
     * the enforced deadline should already have stopped. Same distinction {@link RealWorkerRun}'s
     * {@code CALLER_PATIENCE} draws -- breaching it raises a self-describing environment failure and
     * cannot change any outcome a test asserts on.
     */
    private static final Duration PATIENCE = Duration.ofSeconds(90);

    private final java.util.List<Session> sessions = new ArrayList<>();

    /** Non-zero exit codes of every child this supervisor started, in order. */
    final java.util.List<Integer> exitCodes = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void verifyCapability() {
    }

    @Override
    public SandboxSupervisorSession launch(SandboxPolicy policy) {
        var session = new Session(policy);
        sessions.add(session);
        return session;
    }

    private final class Session implements SandboxSupervisorSession {
        private final SandboxPolicy policy;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private Process process;
        private byte[] control = new byte[0];
        private SandboxOutcome resolved;

        private Session(SandboxPolicy policy) {
            this.policy = policy;
        }

        @Override
        public OutputStream workerInput() {
            return input;
        }

        @Override
        public InputStream supervisorControl() {
            return new ByteArrayInputStream(control);
        }

        @Override
        public InputStream diagnostics() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void terminate(SandboxTermination termination) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (resolved == null) {
                resolved = SandboxOutcome.CANCELLED;
            }
        }

        @Override
        public synchronized SandboxOutcome await(Duration remaining) throws Exception {
            if (resolved != null) {
                return resolved;
            }
            // memoryMiB is enforced the only way a parent JVM can enforce it on a child: as that
            // child's heap ceiling. A Python allocation beyond it therefore has a real bound to hit
            // rather than the host's free memory.
            process = new ProcessBuilder(JAVA.toString(), "-Xmx" + policy.memoryMiB() + "m",
                    "-cp", System.getProperty("java.class.path"), GraalVmWorkerMain.class.getName())
                    .start();
            process.getOutputStream().write(input.toByteArray());
            process.getOutputStream().close();

            var collected = new ByteArrayOutputStream();
            var overflowed = new java.util.concurrent.atomic.AtomicBoolean();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stdout = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stdout.read(buffer)) >= 0) {
                        collected.write(buffer, 0, read);
                        if (collected.size() > policy.maxOutputBytes()) {
                            overflowed.set(true);
                            process.destroyForcibly();
                            return;
                        }
                    }
                } catch (IOException ignored) {
                    // A destroyed child closes the pipe under us; the flags above already carry the verdict.
                }
            });

            // The declared deadline, honestly enforced: whatever is left of it, never the caller's patience.
            long budget = Math.max(1, Math.min(remaining.toMillis(), policy.deadline().toMillis()));
            boolean exited = process.waitFor(budget, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                reader.join(PATIENCE.toMillis());
                return resolved = SandboxOutcome.DEADLINE_EXCEEDED;
            }
            reader.join(PATIENCE.toMillis());
            exitCodes.add(process.exitValue());
            if (overflowed.get()) {
                return resolved = SandboxOutcome.PROTOCOL_FAILURE;
            }
            if (process.exitValue() != 0) {
                return resolved = SandboxOutcome.SETUP_FAILURE;
            }

            byte[] worker = collected.toByteArray();
            var envelope = new ByteArrayOutputStream();
            var data = new DataOutputStream(envelope);
            data.writeInt(0x52525331);
            data.writeInt(1);
            data.writeByte(SandboxOutcome.COMPLETED.ordinal());
            data.writeInt(worker.length);
            data.write(worker);
            data.flush();
            control = envelope.toByteArray();
            return resolved = SandboxOutcome.COMPLETED;
        }

        @Override
        public void close() {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
