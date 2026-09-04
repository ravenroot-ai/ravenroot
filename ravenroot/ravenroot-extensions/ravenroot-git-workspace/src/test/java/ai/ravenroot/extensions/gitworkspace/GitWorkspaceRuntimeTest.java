package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
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
        Path git = executable("waiter", """
                #!/bin/sh
                (trap '' TERM HUP INT; /bin/sleep 60) &
                child=$!
                printf '%s %s\n' "$$" "$child" > "$1"
                wait
                """);
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "profile", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                GitWorkspaceTestSupport.discoveredExecutable(temporary, "bash"), "sha1", null, null,
                Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceRuntime runtime = new GitWorkspaceRuntime(System::nanoTime);
        TestCancellation cancellation = new TestCancellation();
        Process sibling = new ProcessBuilder("/bin/sleep", "60").start();
        try {
            var future = runtime.submit(profile, cancellation, control -> {
                GitWorkspaceStore store = new GitWorkspaceStore(profile);
                new GitCommandRunner(profile, store.home(), store.hooks(), control)
                        .run(List.of(pidFile.toString()));
                return NodeResult.continueWith(Map.of());
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (!Files.exists(pidFile) && !future.isDone() && System.nanoTime() - until < 0) Thread.sleep(5);
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
        Path pidFile = temporary.resolve("deadline-pid");
        Path git = executable("deadline-waiter", """
                #!/bin/sh
                printf '%s\n' "$$" > "$1"
                exec /bin/sleep 60
                """);
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "deadline", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                GitWorkspaceTestSupport.discoveredExecutable(temporary, "bash"), "sha1", null, null,
                Duration.ofSeconds(3), 1, 64 * 1024, 10);
        GitWorkspaceRuntime runtime = new GitWorkspaceRuntime(System::nanoTime);
        var future = runtime.submit(profile, new TestCancellation(), control -> {
            GitWorkspaceStore store = new GitWorkspaceStore(profile);
            new GitCommandRunner(profile, store.home(), store.hooks(), control)
                    .run(List.of(pidFile.toString()));
            return NodeResult.continueWith(Map.of());
        });
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, future::join);
        assertEquals(GitWorkspaceFailure.Code.DEADLINE_EXCEEDED,
                assertInstanceOf(GitWorkspaceFailure.class, failure.getCause()).code());
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void forkAndExitOrphanIsGoneBeforeSuccessfulCompletion() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("orphan-root"));
        Path remote = Files.createDirectory(temporary.resolve("orphan-remote"));
        Path pidFile = temporary.resolve("orphan-pid");
        Path git = executable("fork-and-exit", """
                #!/bin/sh
                (trap '' TERM HUP INT; /bin/sleep 60) &
                printf '%s\n' "$!" > "$1"
                exit 0
                """);
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "orphan", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                GitWorkspaceTestSupport.discoveredExecutable(temporary, "bash"), "sha1", null, null,
                Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceRuntime runtime = new GitWorkspaceRuntime(System::nanoTime);

        runtime.submit(profile, new TestCancellation(), control -> {
            GitWorkspaceStore store = new GitWorkspaceStore(profile);
            new GitCommandRunner(profile, store.home(), store.hooks(), control)
                    .run(List.of(pidFile.toString())).requireSuccess();
            return NodeResult.continueWith(Map.of());
        }).join();

        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private Path executable(String name, String contents) throws Exception {
        Path executable = temporary.resolve(name);
        Files.writeString(executable, contents);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return GitWorkspaceTestSupport.realExecutable(executable);
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
