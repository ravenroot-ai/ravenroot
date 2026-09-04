package ai.ravenroot.extensions.gitworkspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorkspaceSecurityTest {
    @TempDir Path temporary;

    @Test
    void refusesUnknownRepositoryConfigurationBeforeRemoteOrRefUse() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        var action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "config", "url.https://attacker.invalid/.insteadOf", fixture.remote.toUri().toASCIIString());

        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> fixture.invoke(action, fixture.request("provision", null)));
        assertEquals(GitWorkspaceFailure.Code.STATE_CORRUPT,
                ((GitWorkspaceFailure) failure.getCause()).code());
    }

    @Test
    void associationSchemaRejectsExtraKeysAndFilenameTaskMismatch() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        fixture.invoke(fixture.action(10), fixture.request("provision", null));
        GitWorkspaceStore store = new GitWorkspaceStore(fixture.profile(10));
        Path associations = store.home().getParent().resolve("associations");
        Path state;
        try (var files = Files.list(associations)) {
            state = files.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
        }
        String original = Files.readString(state);
        Files.writeString(state, original.substring(0, original.length() - 1) + ",\"extra\":\"x\"}");
        assertStateCorrupt(store, "task-170");

        Files.writeString(state, original);
        Path mismatched = associations.resolve("0".repeat(64) + ".json");
        Files.copy(state, mismatched);
        GitWorkspaceRequest another = new GitWorkspaceRequest(GitWorkspaceRequest.Operation.PROVISION,
                "task-other", fixture.base, "refs/heads/issues/other", null);
        org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                () -> store.reserve(GitWorkspaceStore.Association.initial(another)));
    }

    @Test
    void commandEnvironmentIsAnExplicitMinimalAllowlist() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("authority"));
        Path remote = Files.createDirectory(temporary.resolve("remote"));
        Path executable = temporary.resolve("git-sentinel");
        Files.writeString(executable, "#!/bin/sh\nexec /usr/bin/env\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "profile", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/",
                GitWorkspaceTestSupport.realExecutable(executable),
                GitWorkspaceTestSupport.realExecutable(Path.of("/bin/sh")), "sha1", null, null,
                Duration.ofSeconds(5), 1, 64 * 1024, 10);
        GitWorkspaceStore store = new GitWorkspaceStore(profile);
        GitWorkspaceRuntime.Control control = new GitWorkspaceRuntime.Control(
                System.nanoTime() + Duration.ofSeconds(5).toNanos(), System::nanoTime);
        String output = new GitCommandRunner(profile, store.home(), store.hooks(), control)
                .run(java.util.List.of("sentinel")).requireSuccess();
        Set<String> keys = output.lines().map(line -> line.substring(0, line.indexOf('=')))
                .collect(Collectors.toSet());
        assertTrue(keys.containsAll(Set.of("HOME", "XDG_CONFIG_HOME", "LC_ALL", "LANG",
                "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL", "GIT_TERMINAL_PROMPT", "GCM_INTERACTIVE",
                "GIT_OPTIONAL_LOCKS", "GIT_CONFIG_COUNT")));
        assertTrue(keys.stream().allMatch(key -> key.startsWith("GIT_CONFIG_KEY_")
                || key.startsWith("GIT_CONFIG_VALUE_") || Set.of("HOME", "XDG_CONFIG_HOME", "LC_ALL", "LANG",
                "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL", "GIT_TERMINAL_PROMPT", "GCM_INTERACTIVE",
                "GIT_OPTIONAL_LOCKS", "GIT_CONFIG_COUNT", "PWD", "SHLVL", "_").contains(key)), keys::toString);
        assertFalse(output.contains("GIT_ASKPASS="));
        assertFalse(output.contains("SSH_AUTH_SOCK="));
        assertTrue(control.reapOwned());
    }

    @Test
    void concurrentFirstUseReusesOnlyValidatedOwnedEntries() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 32)
                    .<java.util.concurrent.Callable<Path>>mapToObj(ignored -> () ->
                            new GitWorkspaceStore(fixture.profile(10)).home()).toList();
            Set<Path> homes = new java.util.HashSet<>();
            for (var result : executor.invokeAll(tasks)) homes.add(result.get());
            assertEquals(1, homes.size());
        }
    }

    @Test
    void outputLimitIsEnforcedAtTheFirstExcessByte() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("flood-root"));
        Path remote = Files.createDirectory(temporary.resolve("flood-remote"));
        Path executable = temporary.resolve("git-flood");
        Files.writeString(executable, "#!/bin/sh\ni=0; while [ $i -lt 65537 ]; do printf x; i=$((i+1)); done\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "flood", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/",
                GitWorkspaceTestSupport.realExecutable(executable),
                GitWorkspaceTestSupport.realExecutable(Path.of("/bin/sh")), "sha1", null, null,
                Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceStore store = new GitWorkspaceStore(profile);
        GitWorkspaceRuntime.Control control = new GitWorkspaceRuntime.Control(
                System.nanoTime() + Duration.ofSeconds(8).toNanos(), System::nanoTime);

        GitWorkspaceFailure failure = org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                () -> new GitCommandRunner(profile, store.home(), store.hooks(), control).run(java.util.List.of()));
        assertEquals(GitWorkspaceFailure.Code.OUTPUT_LIMIT_EXCEEDED, failure.code());
        assertTrue(control.reapOwned());
    }

    @Test
    void corruptFetchedObjectGraphFailsWithRedactedGitFailure() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        var action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        Path object = fixture.repository().resolve("objects").resolve(fixture.base.substring(0, 2))
                .resolve(fixture.base.substring(2));
        assertTrue(Files.isRegularFile(object));
        Files.setPosixFilePermissions(object, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(object, "corrupt");

        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> fixture.invoke(action, fixture.request("provision", null)));
        assertEquals(GitWorkspaceFailure.Code.GIT_FAILED,
                ((GitWorkspaceFailure) failure.getCause()).code());
        assertFalse(failure.toString().contains(fixture.base));
    }

    @Test
    void executableReplacementAfterRunnerConstructionIsRefused() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("swap-root"));
        Path remote = Files.createDirectory(temporary.resolve("swap-remote"));
        Path executable = temporary.resolve("git-swappable");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "swap", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/",
                GitWorkspaceTestSupport.realExecutable(executable),
                GitWorkspaceTestSupport.realExecutable(Path.of("/bin/sh")), "sha1", null, null,
                Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceStore store = new GitWorkspaceStore(profile);
        GitWorkspaceRuntime.Control control = new GitWorkspaceRuntime.Control(
                System.nanoTime() + Duration.ofSeconds(8).toNanos(), System::nanoTime);
        GitCommandRunner runner = new GitCommandRunner(profile, store.home(), store.hooks(), control);
        Files.move(executable, temporary.resolve("original-git"));
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));

        GitWorkspaceFailure failure = org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                () -> runner.run(java.util.List.of()));
        assertEquals(GitWorkspaceFailure.Code.AUTHORITY_REFUSED, failure.code());
    }

    @Test
    void authorityRootRenameAndSymlinkSwapIsRefused() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        GitWorkspaceStore store = new GitWorkspaceStore(fixture.profile(10));
        Path moved = temporary.resolve("moved-authority");
        Files.move(fixture.root, moved);
        Files.createSymbolicLink(fixture.root, moved);

        GitWorkspaceFailure failure = org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                store::home);
        assertEquals(GitWorkspaceFailure.Code.AUTHORITY_REFUSED, failure.code());
    }

    @Test
    void invalidUtf8OutputIsRefused() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("utf8-root"));
        Path remote = Files.createDirectory(temporary.resolve("utf8-remote"));
        Path executable = temporary.resolve("git-invalid-utf8");
        Files.writeString(executable, "#!/bin/sh\nprintf '\\377'\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "utf8", root,
                remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/",
                GitWorkspaceTestSupport.realExecutable(executable),
                GitWorkspaceTestSupport.realExecutable(Path.of("/bin/sh")), "sha1", null, null,
                Duration.ofSeconds(8), 1, 64 * 1024, 10);
        GitWorkspaceStore store = new GitWorkspaceStore(profile);
        GitWorkspaceRuntime.Control control = new GitWorkspaceRuntime.Control(
                System.nanoTime() + Duration.ofSeconds(8).toNanos(), System::nanoTime);
        GitWorkspaceFailure failure = org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                () -> new GitCommandRunner(profile, store.home(), store.hooks(), control).run(java.util.List.of()));
        assertEquals(GitWorkspaceFailure.Code.GIT_FAILED, failure.code());
    }

    private static void assertStateCorrupt(GitWorkspaceStore store, String task) {
        GitWorkspaceFailure failure = org.junit.jupiter.api.Assertions.assertThrows(GitWorkspaceFailure.class,
                () -> store.association(task));
        assertEquals(GitWorkspaceFailure.Code.STATE_CORRUPT, failure.code());
    }
}
