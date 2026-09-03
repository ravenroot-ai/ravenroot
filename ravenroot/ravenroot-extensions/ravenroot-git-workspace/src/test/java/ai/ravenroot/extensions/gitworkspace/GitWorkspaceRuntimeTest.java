package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorkspaceRuntimeTest {
    @TempDir Path temporary;

    @Test
    void cancellationReapsOnlyOwnedTreeBeforeCompletionAndReleasesPermit() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path remote = Files.createDirectory(temporary.resolve("remote"));
        Path pidFile = temporary.resolve("owned-pids");
        Path git = Path.of(GitWorkspaceTestSupport.run(temporary, "sh", "-c", "command -v git").trim());
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "profile", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                "sha1", null, null, Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceRuntime runtime = new GitWorkspaceRuntime(System::nanoTime);
        TestCancellation cancellation = new TestCancellation();
        Process sibling = new ProcessBuilder("/bin/sleep", "60").start();
        try {
            var future = runtime.submit(profile, cancellation, control -> {
                Process owned;
                try {
                    owned = new ProcessBuilder("/bin/sh", "-c",
                            "(/bin/sh -c 'trap \"\" TERM; /bin/sleep 60') & child=$!; "
                                    + "echo $$ $child > \"$1\"; wait", "owned", pidFile.toString()).start();
                    control.own(owned);
                    owned.waitFor();
                    return NodeResult.continueWith(Map.of());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    control.check();
                    throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
                } catch (java.io.IOException failed) {
                    throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
                }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() - until < 0) Thread.sleep(5);
            assertTrue(Files.exists(pidFile));
            long[] ownedPids = java.util.Arrays.stream(Files.readString(pidFile).trim().split(" "))
                    .mapToLong(Long::parseLong).toArray();

            cancellation.cancel();
            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class, future::join);
            assertEquals(GitWorkspaceFailure.Code.CANCELLED,
                    assertInstanceOf(GitWorkspaceFailure.class, failure.getCause()).code());
            for (long pid : ownedPids) {
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
            assertTrue(sibling.isAlive());

            NodeResult next = runtime.submit(profile, new TestCancellation(),
                    control -> NodeResult.continueWith("released")).join();
            assertEquals("released", next.payload());
        } finally {
            sibling.destroyForcibly();
            sibling.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void deadlineReapsProcessBeforeReportingTimeout() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("deadline-root"));
        Path remote = Files.createDirectory(temporary.resolve("deadline-remote"));
        Path git = Path.of(GitWorkspaceTestSupport.run(temporary, "sh", "-c", "command -v git").trim());
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "deadline", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                "sha1", null, null, Duration.ofMillis(200), 1, 64 * 1024, 10);
        GitWorkspaceRuntime runtime = new GitWorkspaceRuntime(System::nanoTime);
        java.util.concurrent.atomic.AtomicLong pid = new java.util.concurrent.atomic.AtomicLong();
        var future = runtime.submit(profile, new TestCancellation(), control -> {
            try {
                Process process = new ProcessBuilder("/bin/sleep", "60").start();
                pid.set(process.pid());
                control.own(process);
                process.waitFor();
                return NodeResult.continueWith(Map.of());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                control.check();
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
            } catch (java.io.IOException failed) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            }
        });
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, future::join);
        assertEquals(GitWorkspaceFailure.Code.DEADLINE_EXCEEDED,
                assertInstanceOf(GitWorkspaceFailure.class, failure.getCause()).code());
        assertTrue(pid.get() > 0);
        assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    private static final class TestCancellation implements CancellationSignal {
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) {
            if (cancelled) listener.run(); else listeners.add(listener);
        }
        void cancel() {
            cancelled = true;
            listeners.forEach(Runnable::run);
        }
    }
}
