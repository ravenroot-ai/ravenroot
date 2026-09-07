package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxOutcome;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxSupervisorSession;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxTermination;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.opentest4j.AssertionFailedError;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Explicitly selected auxiliary real-probe carrier for issue 138. Its name intentionally does not
 * match Surefire's default patterns. The acceptance sample always executes the original outer red
 * control separately; this carrier supplies environmental outcome detail and is never conflated
 * with that outer result.
 */
final class CpuRedControlMeasurement {

    private static final String CPU_DESCRIPTOR = "theSupervisorEnforcesTheDeclaredCpuBudget()";
    private static final String CPU_FAILURE_MESSAGE = "a workload that spends roughly 4s of real CPU time "
            + "against a 250ms CPU budget, well inside an 8s deadline, must not be reported as COMPLETED";
    private static final AtomicReference<SandboxOutcome> OBSERVED_OUTCOME = new AtomicReference<>();
    private static final AtomicReference<String> OBSERVED_RECIPE = new AtomicReference<>();

    @Test
    void measureOneFreshEngineInvocation() {
        String mode = requiredProperty("ravenroot.cpu.measurement.mode");
        String sample = requiredProperty("ravenroot.cpu.measurement.sample");
        String condition = requiredProperty("ravenroot.cpu.measurement.condition");
        String commit = requiredProperty("ravenroot.cpu.measurement.commit");
        if (!"base-real-auxiliary".equals(mode)) {
            throw new IllegalArgumentException("unknown auxiliary measurement mode: " + mode);
        }

        OBSERVED_OUTCOME.set(null);
        OBSERVED_RECIPE.set(null);
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ObservedRealProcessNonCompliantOnCpu.class))
                .execute();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        long started = results.testEvents().started().count();
        long succeeded = results.testEvents().succeeded().count();
        List<Event> failures = results.testEvents().failed().list();
        Event onlyFailure = failures.size() == 1 ? failures.get(0) : null;
        String descriptor = onlyFailure == null ? null : onlyFailure.getTestDescriptor().getDisplayName();
        Throwable throwable = onlyFailure == null ? null : onlyFailure
                .getRequiredPayload(TestExecutionResult.class).getThrowable().orElse(null);
        AssertionFailedError assertion = throwable != null
                && throwable.getClass() == AssertionFailedError.class ? (AssertionFailedError) throwable : null;
        Object expected = assertion != null && assertion.isExpectedDefined()
                ? assertion.getExpected().getValue() : null;
        Object actual = assertion != null && assertion.isActualDefined()
                ? assertion.getActual().getValue() : null;
        SandboxOutcome observed = OBSERVED_OUTCOME.get();
        String recipe = OBSERVED_RECIPE.get();

        boolean intendedFailure = started == 11 && succeeded == 10 && failures.size() == 1
                && CPU_DESCRIPTOR.equals(descriptor)
                && assertion != null
                && assertion.getCause() == null
                && assertion.getMessage() != null
                && assertion.getMessage().contains(CPU_FAILURE_MESSAGE)
                && observed == SandboxOutcome.COMPLETED && "BUSY 4000\n".equals(recipe);

        String category;
        if (intendedFailure) {
            category = "INTENDED_CPU_ASSERTION";
        } else if (started == 11 && succeeded == 11 && failures.isEmpty()
                && observed == SandboxOutcome.DEADLINE_EXCEEDED) {
            category = "FALSE_CLEAN_DEADLINE";
        } else if (started == 11 && succeeded == 11 && failures.isEmpty()) {
            category = "FALSE_CLEAN_OTHER_OUTCOME";
        } else {
            category = "WRONG_FAILURE_SHAPE";
        }

        System.out.println("RAVENROOT_CPU_AUXILIARY=" + jsonObject(
                "commit", commit,
                "mode", mode,
                "condition", condition,
                "sample", sample,
                "startedAt", startedAt.toString(),
                "elapsedMillis", Long.toString(elapsedMillis),
                "category", category,
                "nestedStarted", Long.toString(started),
                "nestedSucceeded", Long.toString(succeeded),
                "nestedFailed", Integer.toString(failures.size()),
                "descriptor", descriptor,
                "throwable", throwable == null ? null : throwable.getClass().getName(),
                "message", throwable == null ? null : throwable.getMessage(),
                "cause", throwable == null || throwable.getCause() == null
                        ? null : throwable.getCause().getClass().getName(),
                "expected", expected == null ? null : expected.toString(),
                "actual", actual == null ? null : actual.toString(),
                "observedOutcome", observed == null ? null : observed.name(),
                "observedRecipe", recipe));

    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required system property " + name);
        }
        return value;
    }

    private static String jsonObject(String... pairs) {
        var json = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(jsonEscape(pairs[i])).append("\":");
            String value = pairs[i + 1];
            if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(jsonEscape(value)).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String jsonEscape(String value) {
        var escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** Real process path retained solely for the explicitly selected characterization carrier. */
    static final class ObservedRealProcessNonCompliantOnCpu extends SandboxSupervisorContract {
        @Override
        protected SandboxSupervisorLauncher launcher() {
            return new RecordingLauncher(
                    RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.CPU));
        }
    }

    private record RecordingLauncher(SandboxSupervisorLauncher delegate)
            implements SandboxSupervisorLauncher {

        @Override
        public void verifyCapability() throws IOException {
            delegate.verifyCapability();
        }

        @Override
        public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException {
            return new RecordingSession(delegate.launch(policy), isCpuPolicy(policy));
        }

        private static boolean isCpuPolicy(SandboxPolicy policy) {
            return policy.deadline().equals(Duration.ofSeconds(8))
                    && policy.cpuMillis() == 250
                    && policy.memoryMiB() == 128
                    && policy.maxPids() == 8
                    && policy.maxFiles() == 8
                    && policy.tmpfsMiB() == 64
                    && policy.maxOutputBytes() == 4 * 1024 * 1024;
        }
    }

    private static final class RecordingSession implements SandboxSupervisorSession {
        private final SandboxSupervisorSession delegate;
        private final boolean target;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();

        private RecordingSession(SandboxSupervisorSession delegate, boolean target) {
            this.delegate = delegate;
            this.target = target;
        }

        @Override
        public OutputStream workerInput() {
            OutputStream output = delegate.workerInput();
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    output.write(value);
                    input.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    output.write(bytes, offset, length);
                    input.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    output.flush();
                }

                @Override
                public void close() throws IOException {
                    output.close();
                }
            };
        }

        @Override
        public InputStream supervisorControl() {
            return delegate.supervisorControl();
        }

        @Override
        public InputStream diagnostics() {
            return delegate.diagnostics();
        }

        @Override
        public void terminate(SandboxTermination termination) throws IOException {
            delegate.terminate(termination);
        }

        @Override
        public SandboxOutcome await(Duration remaining) throws Exception {
            SandboxOutcome outcome = delegate.await(remaining);
            if (target) {
                OBSERVED_OUTCOME.set(outcome);
                OBSERVED_RECIPE.set(input.toString(StandardCharsets.UTF_8));
            }
            return outcome;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
