package ai.ravenroot.extensions.filesystem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemAccessTest {
    @TempDir Path root;
    private final FilesystemAccess access = new FilesystemAccess();

    @Test void providerWithoutSecureDirectoryStreamsFailsClosedBeforeLeafIo() throws Exception {
        Files.writeString(root.resolve("file.txt"), "secret");
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            if (directory instanceof SecureDirectoryStream<?>) return;
        }
        FilesystemNodeException failure = assertThrows(FilesystemNodeException.class, () -> access.read(
                profile(8), FilesystemPaths.parse(root, "file.txt"), 8, new FilesystemAccess.InvocationState()));
        assertEquals(FilesystemNodeException.Reason.SECURITY_UNSUPPORTED, failure.reason());
    }

    @Test void createReadReplaceAndExactByteCeilingsUseOnlyBoundedBodies() throws Exception {
        requireSecureProvider();
        Files.createDirectory(root.resolve("folder"));
        var path = FilesystemPaths.parse(root, "folder/file.txt");
        byte[] first = "sample-猫".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var created = access.write(profile(64), path, first, FilesystemAccess.WriteMode.CREATE_NEW,
                new FilesystemAccess.InvocationState());
        assertFalse(created.replaced());
        assertArrayEquals(first, access.read(profile(64), path, first.length,
                new FilesystemAccess.InvocationState()).body());
        assertEquals(FilesystemNodeException.Reason.TOO_LARGE,
                assertThrows(FilesystemNodeException.class, () -> access.read(profile(64), path, first.length - 1,
                        new FilesystemAccess.InvocationState())).reason());
        byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var replaced = access.write(profile(64), path, second, FilesystemAccess.WriteMode.REPLACE,
                new FilesystemAccess.InvocationState());
        assertTrue(replaced.replaced());
        assertArrayEquals(second, Files.readAllBytes(root.resolve("folder/file.txt")));
    }

    @Test void symlinkedParentAndLeafAreRefusedAndExternalTreeIsUntouched() throws Exception {
        requireSecureProvider();
        Path outside = Files.createTempDirectory("ravenroot-fs-outside");
        try {
            Files.writeString(outside.resolve("secret.txt"), "external");
            Files.createSymbolicLink(root.resolve("linked"), outside);
            var parentFailure = assertThrows(FilesystemNodeException.class, () -> access.read(profile(64),
                    FilesystemPaths.parse(root, "linked/secret.txt"), 64, new FilesystemAccess.InvocationState()));
            assertEquals(FilesystemNodeException.Reason.SYMLINK_REFUSED, parentFailure.reason());
            Files.createSymbolicLink(root.resolve("leaf.txt"), outside.resolve("secret.txt"));
            var leafFailure = assertThrows(FilesystemNodeException.class, () -> access.write(profile(64),
                    FilesystemPaths.parse(root, "leaf.txt"), new byte[]{1}, FilesystemAccess.WriteMode.REPLACE,
                    new FilesystemAccess.InvocationState()));
            assertEquals(FilesystemNodeException.Reason.SYMLINK_REFUSED, leafFailure.reason());
            assertEquals("external", Files.readString(outside.resolve("secret.txt")));
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test void swappingParentAfterDescriptorOpenCannotRedirectReadOutsideRoot() throws Exception {
        requireSecureProvider();
        Path inside = Files.createDirectory(root.resolve("folder"));
        Files.writeString(inside.resolve("file.txt"), "inside");
        Path outside = Files.createTempDirectory("ravenroot-fs-swap");
        try {
            Files.writeString(outside.resolve("file.txt"), "outside");
            FilesystemAccess raced = new FilesystemAccess(new FilesystemAccess.Hooks() {
                @Override public void afterParentOpened(FilesystemPaths.Parsed ignored) {
                    try {
                        Files.move(root.resolve("folder"), root.resolve("parked"));
                        Files.createSymbolicLink(root.resolve("folder"), outside);
                    } catch (Exception failed) { throw new IllegalStateException(failed); }
                }
            });
            byte[] value = raced.read(profile(64), FilesystemPaths.parse(root, "folder/file.txt"), 64,
                    new FilesystemAccess.InvocationState()).body();
            assertEquals("inside", new String(value, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("outside", Files.readString(outside.resolve("file.txt")));
        } finally {
            Files.deleteIfExists(outside.resolve("file.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test void swappingConfiguredRootDirectoryBeforeOpenIsRefusedByIdentity() throws Exception {
        requireSecureProvider();
        Files.writeString(root.resolve("inside.txt"), "inside");
        FilesystemProfile configured = profile(64);
        Path parked = root.resolveSibling(root.getFileName() + "-parked");
        try {
            FilesystemAccess raced = new FilesystemAccess(new FilesystemAccess.Hooks() {
                @Override public void beforeRootOpen(FilesystemPaths.Parsed ignored) {
                    try {
                        Files.move(root, parked);
                        Files.createDirectory(root);
                        Files.writeString(root.resolve("inside.txt"), "outside");
                    } catch (Exception failed) { throw new IllegalStateException(failed); }
                }
            });
            var failure = assertThrows(FilesystemNodeException.class, () -> raced.read(configured,
                    FilesystemPaths.parse(configured.root(), "inside.txt"), 64,
                    new FilesystemAccess.InvocationState()));
            assertEquals(FilesystemNodeException.Reason.OUTSIDE_ROOT, failure.reason());
            assertEquals("outside", Files.readString(root.resolve("inside.txt")));
        } finally {
            Files.deleteIfExists(root.resolve("inside.txt"));
            Files.deleteIfExists(root);
            if (Files.exists(parked)) Files.move(parked, root);
        }
    }

    @Test void swappingLeafToSymlinkAtMoveBoundaryRefusesAndPreservesExternalTarget() throws Exception {
        requireSecureProvider();
        Files.writeString(root.resolve("target.txt"), "inside");
        Path outside = Files.createTempFile("ravenroot-fs-target", ".txt");
        Files.writeString(outside, "external");
        try {
            FilesystemAccess raced = new FilesystemAccess(new FilesystemAccess.Hooks() {
                @Override public void beforeMove(FilesystemPaths.Parsed ignored) {
                    try {
                        Files.delete(root.resolve("target.txt"));
                        Files.createSymbolicLink(root.resolve("target.txt"), outside);
                    } catch (Exception failed) { throw new IllegalStateException(failed); }
                }
            });
            var failure = assertThrows(FilesystemNodeException.class, () -> raced.write(profile(64),
                    FilesystemPaths.parse(root, "target.txt"), "new".getBytes(),
                    FilesystemAccess.WriteMode.REPLACE, new FilesystemAccess.InvocationState()));
            assertEquals(FilesystemNodeException.Reason.SYMLINK_REFUSED, failure.reason());
            assertEquals("external", Files.readString(outside));
            try (var files = Files.list(root)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(FilesystemAccess.TEMP_PREFIX)));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void concurrentCreateHasOneWinnerAndConcurrentReplaceNeverPublishesPartialBytes() throws Exception {
        requireSecureProvider();
        Path target = FilesystemPaths.parse(root, "race.bin").relative();
        var parsed = FilesystemPaths.parse(root, "race.bin");
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<CompletableFuture<Boolean>> creates = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                byte[] body = new byte[512]; java.util.Arrays.fill(body, (byte) i);
                creates.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        access.write(profile(1024), parsed, body, FilesystemAccess.WriteMode.CREATE_NEW,
                                new FilesystemAccess.InvocationState());
                        return true;
                    } catch (FilesystemNodeException failure) {
                        assertEquals(FilesystemNodeException.Reason.CONFLICT, failure.reason());
                        return false;
                    }
                }, executor));
            }
            assertEquals(1, creates.stream().filter(CompletableFuture::join).count());

            byte[] a = new byte[1024]; java.util.Arrays.fill(a, (byte) 'A');
            byte[] b = new byte[1024]; java.util.Arrays.fill(b, (byte) 'B');
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> access.write(profile(2048), parsed, a,
                            FilesystemAccess.WriteMode.REPLACE, new FilesystemAccess.InvocationState()), executor),
                    CompletableFuture.runAsync(() -> access.write(profile(2048), parsed, b,
                            FilesystemAccess.WriteMode.REPLACE, new FilesystemAccess.InvocationState()), executor)
            ).join();
            byte[] finalBody = Files.readAllBytes(root.resolve(target));
            assertTrue(java.util.Arrays.equals(a, finalBody) || java.util.Arrays.equals(b, finalBody));
        }
    }

    @Test void timeoutBeforeCommitLeavesTargetAndCleansTemporaryFile() throws Exception {
        requireSecureProvider();
        Path target = root.resolve("unchanged.txt");
        Files.writeString(target, "old");
        var state = new FilesystemAccess.InvocationState();
        state.timeout();
        var failure = assertThrows(FilesystemNodeException.class, () -> access.write(profile(64),
                FilesystemPaths.parse(root, "unchanged.txt"), "new".getBytes(),
                FilesystemAccess.WriteMode.REPLACE, state));
        assertEquals(FilesystemNodeException.Reason.TIMEOUT, failure.reason());
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(FilesystemAccess.TEMP_PREFIX)));
        }
    }

    @Test void timeoutAfterMoveOwnershipBeginsIsReportedAsAmbiguous() throws Exception {
        requireSecureProvider();
        Path target = root.resolve("ambiguous.txt");
        Files.writeString(target, "old");
        CountDownLatch moving = new CountDownLatch(1);
        FilesystemAccess delayed = new FilesystemAccess(new FilesystemAccess.Hooks() {
            @Override public void afterMoveBegan(FilesystemPaths.Parsed ignored) {
                moving.countDown();
                try { Thread.sleep(5_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        FilesystemRuntime runtime = new FilesystemRuntime((tenant, name) -> java.util.Optional.of(profile(64)), delayed);
        FilesystemAccess.InvocationState state = new FilesystemAccess.InvocationState();
        CountDownLatch workerFinished = new CountDownLatch(1);
        CompletableFuture<ai.ravenroot.api.execution.NodeResult> result = runtime.execute("tenant", profile(64),
                Duration.ofMillis(25), () -> {
                    try {
                        delayed.write(profile(64), FilesystemPaths.parse(root, "ambiguous.txt"), "new".getBytes(),
                                FilesystemAccess.WriteMode.REPLACE, state);
                        return ai.ravenroot.api.execution.NodeResult.continueWith(java.util.Map.of());
                    } finally {
                        workerFinished.countDown();
                    }
                }, state);
        assertTrue(moving.await(1, TimeUnit.SECONDS));
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertEquals(FilesystemNodeException.Reason.AMBIGUOUS_FINAL_MOVE,
                ((FilesystemNodeException) failure.getCause()).reason());
        assertTrue(workerFinished.await(1, TimeUnit.SECONDS));
        assertTrue(Files.readString(target).equals("old") || Files.readString(target).equals("new"));
    }

    @Test void restartSweepDeletesOnlyExpiredTempsOwnedByTheActiveProfile() throws Exception {
        requireSecureProvider();
        Files.writeString(root.resolve("source.txt"), "ok");
        FileTime expired = FileTime.fromMillis(System.currentTimeMillis() - Duration.ofHours(25).toMillis());
        FilesystemProfile active = profile(64);
        FilesystemProfile otherProfile = profile("other", 64);
        String priorGeneration = "0".repeat(32);

        Path stale = root.resolve(FilesystemTempNames.create(active, root.getFileSystem().getPath("target.txt"),
                priorGeneration, "1".repeat(32)));
        Files.writeString(stale, "x");
        Files.setLastModifiedTime(stale, expired);
        Path recent = root.resolve(FilesystemTempNames.create(active, root.getFileSystem().getPath("target.txt"),
                priorGeneration, "2".repeat(32)));
        Files.writeString(recent, "x");
        Path otherOwner = root.resolve(FilesystemTempNames.create(otherProfile,
                root.getFileSystem().getPath("target.txt"), priorGeneration, "3".repeat(32)));
        Files.writeString(otherOwner, "x");
        Files.setLastModifiedTime(otherOwner, expired);
        Path legacyTarget = Files.writeString(root.resolve(".ravenroot-fs-report"), "legitimate");
        Files.setLastModifiedTime(legacyTarget, expired);
        Path malformedPrivate = Files.writeString(root.resolve(FilesystemAccess.TEMP_PREFIX + "stale.tmp"), "x");
        Files.setLastModifiedTime(malformedPrivate, expired);
        Path ordinary = Files.writeString(root.resolve("ordinary.tmp"), "x");
        Path symlinkTarget = Files.writeString(root.resolve("outside-name"), "x");
        Path symlink = root.resolve(FilesystemTempNames.create(active, root.getFileSystem().getPath("link.txt"),
                priorGeneration, "4".repeat(32)));
        Files.createSymbolicLink(symlink, symlinkTarget.getFileName());
        Path directory = root.resolve(FilesystemTempNames.create(active, root.getFileSystem().getPath("dir.txt"),
                priorGeneration, "5".repeat(32)));
        Files.createDirectory(directory);
        Files.setLastModifiedTime(directory, expired);

        access.read(active, FilesystemPaths.parse(root, "source.txt"), 64,
                new FilesystemAccess.InvocationState());
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(otherOwner));
        assertTrue(Files.exists(legacyTarget));
        assertTrue(Files.exists(malformedPrivate));
        assertTrue(Files.exists(ordinary));
        assertTrue(Files.isSymbolicLink(symlink));
        assertTrue(Files.exists(symlinkTarget));
        assertTrue(Files.isDirectory(directory));

        var legacy = FilesystemPaths.parse(root, ".ravenroot-fs-report");
        access.write(active, legacy, "updated".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                FilesystemAccess.WriteMode.REPLACE, new FilesystemAccess.InvocationState());
        assertEquals("updated", new String(access.read(active, legacy, 64,
                new FilesystemAccess.InvocationState()).body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private FilesystemProfile profile(long maxBytes) {
        return profile("p", maxBytes);
    }

    private FilesystemProfile profile(String name, long maxBytes) {
        return new FilesystemProfile(name, root.toAbsolutePath(), true, true, Set.of("**", "*"),
                maxBytes, 16, Duration.ofSeconds(5));
    }

    private void requireSecureProvider() throws Exception {
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(directory instanceof SecureDirectoryStream<?>,
                    "provider does not support SecureDirectoryStream; fail-closed behavior is tested separately");
        }
    }
}
