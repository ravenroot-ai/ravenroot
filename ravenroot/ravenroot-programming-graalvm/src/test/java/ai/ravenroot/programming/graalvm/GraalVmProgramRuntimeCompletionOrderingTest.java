package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests publication and resource lifetime with held callbacks, without racing a clock. */
@Timeout(20)
class GraalVmProgramRuntimeCompletionOrderingTest {
    @Test void successIsNotPublishedUntilAdmissionCloseReturns() throws Exception {
        assertAdmissionClosePrecedesPublication(false);
    }

    @Test void failureIsNotPublishedUntilAdmissionCloseReturns() throws Exception {
        assertAdmissionClosePrecedesPublication(true);
    }

    private void assertAdmissionClosePrecedesPublication(boolean refuse) throws Exception {
        var supervisor = new ControlledSupervisor();
        var admission = new RecordingAdmission();
        admission.refusal = refuse ? new SecurityException("revoked before redemption") : null;
        admission.releaseClose = new CountDownLatch(1);
        var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
        try {
            awaitBarrier(admission.closeEntered);
            assertFalse(future.isDone(), "ordinary completion must not escape while admission.close is held");
            assertEquals(1, supervisor.closes.get(), "session cleanup must precede admission closure");
        } finally {
            admission.releaseClose.countDown();
        }
        if (refuse) assertSame(admission.refusal, assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS)).getCause());
        else assertEquals("result", future.get(5, TimeUnit.SECONDS));
        assertEquals(1, admission.closes.get());
    }

    @Test void cancellationAndRevocationShareTheActualCleanupSettlement() throws Exception {
        for (boolean revoke : new boolean[] { false, true }) {
            var supervisor = new ControlledSupervisor();
            supervisor.releaseOutcome = new CountDownLatch(1);
            supervisor.releaseTerminate = new CountDownLatch(1);
            supervisor.releaseReap = new CountDownLatch(1);
            var admission = new RecordingAdmission();
            admission.beforeClose = () -> assertTrue(supervisor.cleanupFinished,
                    "registration must remain until the shared cleanup attempt settles");
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            awaitBarrier(supervisor.awaitEntered);
            var cancelled = new CompletableFuture<Boolean>();
            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    if (revoke) { admission.revocation.run(); cancelled.complete(future.isCancelled()); }
                    else cancelled.complete(future.cancel(true));
                } catch (Throwable error) { cancelled.completeExceptionally(error); }
            });
            try {
                awaitBarrier(supervisor.terminateEntered);
                assertEquals(0, admission.closes.get());
                supervisor.releaseOutcome.countDown();
                supervisor.releaseTerminate.countDown();
                awaitBarrier(supervisor.reapEntered);
                assertEquals(0, admission.closes.get(), "a held reap is not cleanup settlement");
                assertFalse(cancelled.isDone());
            } finally {
                supervisor.releaseOutcome.countDown(); supervisor.releaseTerminate.countDown();
                supervisor.releaseReap.countDown();
            }
            assertTrue(cancelled.get(5, TimeUnit.SECONDS));
            join(canceller); finish(admission);
            assertTrue(future.isCancelled());
            assertFalse(future.complete("late result"));
            assertFalse(future.completeExceptionally(new IOException("late failure")));
            assertEquals(1, supervisor.terminations.get());
            assertEquals(1, supervisor.closes.get());
            assertEquals(1, admission.closes.get());
        }
    }

    @Test void cancelledOrRevokedLateLaunchNeverRedeemsOrWritesSource() throws Exception {
        for (boolean revoke : new boolean[] { false, true }) {
            var supervisor = new ControlledSupervisor(); supervisor.releaseLaunch = new CountDownLatch(1);
            var admission = new RecordingAdmission();
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            try {
                awaitBarrier(supervisor.launchEntered);
                if (revoke) admission.revocation.run(); else assertTrue(future.cancel(true));
                assertTrue(future.isCancelled());
                assertEquals(0, admission.closes.get());
                assertEquals(0, supervisor.terminations.get());
            } finally { supervisor.releaseLaunch.countDown(); }
            finish(admission);
            assertEquals(0, admission.redemptions.get()); assertEquals(0, supervisor.input.size());
            assertEquals(1, supervisor.terminations.get()); assertEquals(1, supervisor.closes.get());
            assertEquals(1, admission.closes.get()); assertTrue(future.isCancelled());
        }
    }

    @Test void cancellationDuringAdmissionCloseCannotBeOverwrittenByTheReadyValue() throws Exception {
        var supervisor = new ControlledSupervisor(); var admission = new RecordingAdmission();
        admission.releaseClose = new CountDownLatch(1);
        var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
        try { awaitBarrier(admission.closeEntered); assertTrue(future.cancel(true)); }
        finally { admission.releaseClose.countDown(); }
        finish(admission);
        assertTrue(future.isCancelled()); assertEquals(1, admission.closes.get());
        assertEquals(1, supervisor.closes.get());
    }

    @Test void primaryFailureRetainsEveryCleanupAndAdmissionCloseFailure() throws Exception {
        var supervisor = new ControlledSupervisor(); supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.SETUP_FAILURE;
        supervisor.terminateFailure = new IOException("terminate failed");
        supervisor.reapFailure = new IOException("reap failed");
        supervisor.closeFailure = new IOException("session close failed");
        var admission = new RecordingAdmission(); admission.closeFailure = new IllegalStateException("admission close failed");
        var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
        Throwable failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertEquals("SANDBOX_SETUP_FAILURE", failure.getMessage());
        assertArrayEquals(new Throwable[] { supervisor.terminateFailure }, failure.getSuppressed());
        assertArrayEquals(new Throwable[] { supervisor.reapFailure, supervisor.closeFailure, admission.closeFailure },
                supervisor.terminateFailure.getSuppressed());
        assertEquals(2, supervisor.awaits.get(), "reap is still attempted after terminate throws");
        assertEquals(1, supervisor.closes.get()); assertEquals(1, admission.closes.get());
    }

    @Test void unknownReapOutcomesSettleAsFailuresAndStillCloseTheAdmission() throws Exception {
        for (var outcome : new SandboxSupervisorLauncher.SandboxOutcome[] {
                SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED,
                SandboxSupervisorLauncher.SandboxOutcome.REAP_FAILED, null }) {
            var supervisor = new ControlledSupervisor(); supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.SETUP_FAILURE;
            supervisor.reapOutcome = outcome; var admission = new RecordingAdmission();
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            Throwable failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
            assertEquals("SANDBOX_SETUP_FAILURE", failure.getMessage());
            assertEquals("SANDBOX_REAP_FAILED: " + outcome, failure.getSuppressed()[0].getMessage());
            assertEquals(1, supervisor.closes.get()); assertEquals(1, admission.closes.get());
        }
    }

    @Test void aCloseFailureCannotBeReportedAsAnOrdinarySuccess() throws Exception {
        for (boolean admissionClose : new boolean[] { false, true }) {
            var supervisor = new ControlledSupervisor(); var admission = new RecordingAdmission();
            var failure = new IllegalStateException("close failed");
            if (admissionClose) admission.closeFailure = failure;
            else supervisor.closeFailure = new IOException("session close failed");
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            assertSame(admissionClose ? failure : supervisor.closeFailure,
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, supervisor.closes.get()); assertEquals(1, admission.closes.get());
        }
    }

    @Test void failuresBeforeSessionPublicationStillReleaseTheAdmission() throws Exception {
        for (boolean capability : new boolean[] { false, true }) {
            var supervisor = new ControlledSupervisor(); var failure = new IOException("launcher unavailable");
            if (capability) supervisor.capabilityFailure = failure; else supervisor.launchFailure = failure;
            var admission = new RecordingAdmission();
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            Throwable refused = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
            assertSame(failure, refused.getCause());
            assertEquals(0, supervisor.closes.get()); assertEquals(0, admission.redemptions.get());
            assertEquals(1, admission.closes.get());
        }
    }

    @Test void failedRevocationRegistrationNeverLaunchesAndClosesOnce() throws Exception {
        var supervisor = new ControlledSupervisor(); var admission = new RecordingAdmission();
        admission.registrationFailure = new IllegalStateException("registration failed");
        var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
        assertSame(admission.registrationFailure,
                assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause());
        assertEquals(1, admission.closes.get()); assertEquals(0, admission.redemptions.get());
        assertEquals(1, supervisor.launchEntered.getCount());
    }

    @Test void cancelledCleanupFailureIsDiagnosticAndCannotPublishALateValue() throws Exception {
        var supervisor = new ControlledSupervisor(); var admission = new RecordingAdmission();
        supervisor.releaseLaunch = new CountDownLatch(1);
        supervisor.terminateFailure = new IOException("unconfirmed cleanup");
        supervisor.closeFailure = new IOException("session close failed");
        admission.closeFailure = new IllegalStateException("admission close failed");
        var captured = new ByteArrayOutputStream(); var previous = System.err;
        try (var diagnostics = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(diagnostics);
            var future = runtime(supervisor).execute(admission, request()).toCompletableFuture();
            try { awaitBarrier(supervisor.launchEntered); assertTrue(future.cancel(true)); }
            finally { supervisor.releaseLaunch.countDown(); }
            finish(admission);
            assertTrue(future.isCancelled()); assertEquals(1, admission.closes.get());
            assertEquals(1, supervisor.closes.get()); assertFalse(future.complete("late"));
        } finally { System.setErr(previous); }
        String diagnostic = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, diagnostic.lines().filter(line -> line.contains("program_cleanup_failed")).count());
        assertTrue(diagnostic.contains("IOException")); assertFalse(diagnostic.contains("unconfirmed cleanup"));
    }

    private static void finish(RecordingAdmission admission) throws Exception {
        awaitBarrier(admission.closeEntered); join(admission.closeThread);
    }

    private static void join(Thread thread) throws InterruptedException {
        assertNotNull(thread); thread.join(5000); assertFalse(thread.isAlive(), "controlled thread did not settle");
    }

    private static GraalVmProgramRuntime runtime(ControlledSupervisor supervisor) {
        return new GraalVmProgramRuntime(supervisor, new SandboxPolicy(Duration.ofSeconds(5),
                5000, 64, 32, 256, 64, 2 * 1024 * 1024,
                Path.of(System.getProperty("java.class.path")), "test-worker",
                Path.of(System.getProperty("java.home"), "bin", "java"), "test-jre"));
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "ordering", "input", Map.of());
    }

    private static void awaitBarrier(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled callback did not reach its barrier");
    }

    private static final class RecordingAdmission implements ProgramAdmission {
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger redemptions = new AtomicInteger();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseClose = new CountDownLatch(0);
        volatile Runnable revocation;
        volatile RuntimeException refusal;
        volatile RuntimeException closeFailure;
        volatile RuntimeException registrationFailure;
        volatile Thread closeThread;
        volatile Runnable beforeClose = () -> { };
        final GeneratedArtifact artifact = new GeneratedArtifact("ordering-artifact", "javascript",
                ProgramArtifactDigest.canonical("javascript", "() => 'result'"), "() => 'result'",
                ArtifactState.ACTIVE, 1, Instant.now(), Instant.now(), Map.of());
        @Override public String artifactId() { return artifact.id(); }
        @Override public GeneratedArtifact unverifiedSnapshot() { return artifact; }
        @Override public GeneratedArtifact redeem() {
            redemptions.incrementAndGet();
            if (refusal != null) throw refusal;
            return artifact;
        }
        @Override public void onRevoked(Runnable cancellation) {
            revocation = cancellation; if (registrationFailure != null) throw registrationFailure;
        }
        @Override public void close() {
            closeThread = Thread.currentThread(); beforeClose.run();
            closes.incrementAndGet(); closeEntered.countDown();
            try { awaitBarrier(releaseClose); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class ControlledSupervisor implements SandboxSupervisorLauncher {
        final AtomicInteger terminations = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger awaits = new AtomicInteger();
        final CountDownLatch awaitEntered = new CountDownLatch(1);
        final CountDownLatch terminateEntered = new CountDownLatch(1);
        final CountDownLatch reapEntered = new CountDownLatch(1);
        final CountDownLatch launchEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseOutcome = new CountDownLatch(0);
        volatile CountDownLatch releaseTerminate = new CountDownLatch(0);
        volatile CountDownLatch releaseReap = new CountDownLatch(0);
        volatile CountDownLatch releaseLaunch = new CountDownLatch(0);
        volatile IOException capabilityFailure;
        volatile IOException launchFailure;
        volatile boolean cleanupFinished;
        volatile IOException terminateFailure;
        volatile IOException reapFailure;
        volatile IOException closeFailure;
        volatile SandboxOutcome outcome = SandboxOutcome.COMPLETED;
        volatile SandboxOutcome reapOutcome = SandboxOutcome.CANCELLED;
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        @Override public void verifyCapability() throws IOException { if (capabilityFailure != null) throw capabilityFailure; }
        @Override public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException {
            launchEntered.countDown();
            try { awaitBarrier(releaseLaunch); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            if (launchFailure != null) throw launchFailure;
            return new SandboxSupervisorSession() {
                @Override public java.io.OutputStream workerInput() { return input; }
                @Override public java.io.InputStream diagnostics() { return new ByteArrayInputStream(new byte[0]); }
                @Override public java.io.InputStream supervisorControl() {
                    try {
                        var response = new ByteArrayOutputStream(); ProgramWireProtocol.writeSuccess(response, "result");
                        var envelope = new ByteArrayOutputStream(); var wire = new DataOutputStream(envelope);
                        wire.writeInt(0x52525331); wire.writeInt(1); wire.writeByte(SandboxOutcome.COMPLETED.ordinal());
                        wire.writeInt(response.size()); wire.write(response.toByteArray());
                        return new ByteArrayInputStream(envelope.toByteArray());
                    } catch (IOException error) { throw new AssertionError(error); }
                }
                @Override public void terminate(SandboxTermination reason) throws IOException {
                    terminations.incrementAndGet(); terminateEntered.countDown();
                    try { awaitBarrier(releaseTerminate); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    if (terminateFailure != null) throw terminateFailure;
                }
                @Override public SandboxOutcome await(Duration remaining) throws Exception {
                    if (awaits.incrementAndGet() == 1) { awaitEntered.countDown(); awaitBarrier(releaseOutcome); return outcome; }
                    reapEntered.countDown(); awaitBarrier(releaseReap);
                    if (reapFailure != null) throw reapFailure;
                    return reapOutcome;
                }
                @Override public void close() throws IOException {
                    closes.incrementAndGet(); cleanupFinished = true;
                    if (closeFailure != null) throw closeFailure;
                }
            };
        }
    }
}
