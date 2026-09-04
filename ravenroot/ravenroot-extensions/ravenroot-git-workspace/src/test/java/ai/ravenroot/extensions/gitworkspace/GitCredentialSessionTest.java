package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodeCredentialService;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpService;
import ai.ravenroot.api.node.service.OutboundWebSocketService;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GitCredentialSessionTest {
    @TempDir Path temporary;

    @Test
    void secretUsesPrivateSocketAndNeverAppearsInDaemonArgumentsOrEnvironment() throws Exception {
        String secret = "opaque ' $ ; ☃ test secret 170";
        Path root = Files.createDirectory(temporary.resolve("authority"));
        Path gitExecutable = GitWorkspaceTestSupport.discoveredExecutable(temporary, "git");
        GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant", "profile", root,
                "https://example.invalid/org/repository.git", "refs/heads/dev", "refs/heads/issues/",
                gitExecutable, GitWorkspaceTestSupport.discoveredExecutable(temporary, "bash"), "sha1",
                "credential-ref", "git-user", Duration.ofSeconds(8), 1,
                64 * 1024, 10);
        GitWorkspaceStore store = new GitWorkspaceStore(profile);
        GitWorkspaceRuntime.Control control = new GitWorkspaceRuntime.Control(
                System.nanoTime() + Duration.ofSeconds(8).toNanos(), System::nanoTime);
        GitCommandRunner runner = new GitCommandRunner(profile, store.home(), store.hooks(), control);

        AtomicReference<CredentialLease> issued = new AtomicReference<>();
        try (GitCredentialSession session = GitCredentialSession.open(profile, store, services(secret, issued),
                message(), runner, control)) {
            assertTrue(session.helper().contains("credential-"));
            ProcessHandle daemon = ProcessHandle.of(session.daemonPid()).orElseThrow();
            assertTrue(daemon.isAlive());
            assertFalse(daemon.info().commandLine().orElse("").contains(secret));
            String processView = GitWorkspaceTestSupport.run(temporary, "/bin/ps", "eww", "-p",
                    Long.toString(session.daemonPid()));
            assertFalse(processView.contains(secret));
            byte[] binding = GitCredentialSession.credentialInput(profile, new char[0], false);
            assertEquals("protocol=https\nhost=example.invalid\npath=org/repository.git\nusername=git-user\n\n",
                    new String(binding, java.nio.charset.StandardCharsets.UTF_8));
            java.util.Arrays.fill(binding, (byte) 0);
        }
        var valueField = CredentialLease.class.getDeclaredField("value");
        valueField.setAccessible(true);
        assertTrue(java.util.Arrays.equals(new char[secret.length()], (char[]) valueField.get(issued.get())));
        try (var entries = Files.list(store.home())) {
            assertTrue(entries.findAny().isEmpty());
        }
        assertTrue(control.reapOwned());
    }

    @Test
    void credentiallessProfileNeverResolvesALease() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        AtomicInteger resolutions = new AtomicInteger();
        NodePackageServices unavailable = NodePackageServices.unavailable();
        NodePackageServices services = new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() {
                return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
            }
            @Override public NodeCredentialService credentials() {
                return (message, reference, deadline) -> {
                    resolutions.incrementAndGet();
                    throw new AssertionError("credentialless profile resolved a lease");
                };
            }
            @Override public OutboundHttpService outboundHttp() { return unavailable.outboundHttp(); }
            @Override public OutboundWebSocketService outboundWebSocket() {
                return unavailable.outboundWebSocket();
            }
        };
        NodeAction action = new GitWorkspaceNodeBehavior((tenant, name) -> Optional.of(fixture.profile(10)))
                .create(new NodeConfiguration("git", GitWorkspaceNodeBehavior.BEHAVIOR,
                        Map.of("workspaceProfile", GitWorkspaceTestSupport.PROFILE)), services);

        assertEquals("continue", fixture.invoke(action, fixture.request("provision", null)).outcome());
        assertEquals(0, resolutions.get());
    }

    private static NodePackageServices services(String secret, AtomicReference<CredentialLease> issued) {
        NodePackageServices unavailable = NodePackageServices.unavailable();
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() {
                return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
            }
            @Override public NodeCredentialService credentials() {
                return (message, reference, deadline) -> {
                    CredentialLease lease = new CredentialLease(secret.toCharArray());
                    issued.set(lease);
                    return OutboundCall.completed(lease);
                };
            }
            @Override public OutboundHttpService outboundHttp() { return unavailable.outboundHttp(); }
            @Override public OutboundWebSocketService outboundWebSocket() {
                return unavailable.outboundWebSocket();
            }
        };
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "tenant", "subject", PrincipalType.WORKLOAD,
                "issuer"), id, id, id, id, Set.of(), "node", Map.of(), Map.of());
    }
}
