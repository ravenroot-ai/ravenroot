package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.node.service.NodePackageCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorkspaceContractTest {
    @TempDir Path temporary;

    @Test
    void descriptorExposesOnlyOpaqueProfileAndStableOutcomes() {
        GitWorkspaceNodeBehavior behavior = new GitWorkspaceNodeBehavior();
        assertEquals(Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION), behavior.requiredServices());
        assertEquals(Set.of("workspaceProfile"), behavior.descriptor().properties().stream()
                .map(property -> property.name()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("continue", "conflict", "unmerged"), behavior.descriptor().outcomes().stream()
                .map(outcome -> outcome.name()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void payloadCannotSupplyAuthorityOrVerificationHints() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        Map<String, Object> valid = fixture.request("verify", fixture.base);
        GitWorkspaceRequest.parse(valid, fixture.profile(10));
        for (String forbidden : Set.of("root", "remote", "credentialRef", "reviewedTree", "patchId",
                "providerStatus")) {
            Map<String, Object> hostile = new LinkedHashMap<>(valid);
            hostile.put(forbidden, "attacker-controlled");
            GitWorkspaceFailure failure = assertThrows(GitWorkspaceFailure.class,
                    () -> GitWorkspaceRequest.parse(hostile, fixture.profile(10)));
            assertEquals(GitWorkspaceFailure.Code.INVALID_INPUT, failure.code());
        }
    }

    @Test
    void maliciousAndAmbiguousRefsAreRefused() {
        for (String ref : Set.of("refs/heads/../escape", "refs/heads/topic.lock", "refs/heads/a@{1}",
                "refs/heads/a//b", "refs/tags/not-a-branch", "refs/heads/line\nfeed", "-option")) {
            assertFalse(GitWorkspaceProfile.safeRef(ref), ref);
        }
    }

    @Test
    void fixtureResolvesExecutableSymlinksBeforeBuildingAProfile() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("authority"));
        Path remote = Files.createDirectory(temporary.resolve("remote"));
        Path git = GitWorkspaceTestSupport.discoveredExecutable(temporary, "git");
        Path shellAlias = temporary.resolve("bin-sh-alias");
        Files.createSymbolicLink(shellAlias, Path.of("/bin/sh"));
        assertTrue(Files.isSymbolicLink(shellAlias));

        assertThrows(IllegalArgumentException.class, () -> new GitWorkspaceProfile("tenant", "profile", root,
                remote.toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git, shellAlias,
                "sha1", null, null, Duration.ofSeconds(5), 1, 64 * 1024, 10));

        Path shell = GitWorkspaceTestSupport.realExecutable(shellAlias);
        assertEquals(Path.of("/bin/sh").toRealPath(), shell);
        assertTrue(shell.isAbsolute());
        assertTrue(Files.isRegularFile(shell, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isExecutable(shell));
        assertFalse(Files.isSymbolicLink(shell));
        assertFalse(Files.isSymbolicLink(git));
        assertTrue(Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isExecutable(git));

        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "profile", root,
                remote.toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git, shell,
                "sha1", null, null, Duration.ofSeconds(5), 1, 64 * 1024, 10);
        assertEquals(shell, profile.processShellExecutable());
        assertEquals(git, profile.gitExecutable());
    }
}
