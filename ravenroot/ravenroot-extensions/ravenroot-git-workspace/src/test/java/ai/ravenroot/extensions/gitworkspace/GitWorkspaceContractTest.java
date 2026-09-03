package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.node.service.NodePackageCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
