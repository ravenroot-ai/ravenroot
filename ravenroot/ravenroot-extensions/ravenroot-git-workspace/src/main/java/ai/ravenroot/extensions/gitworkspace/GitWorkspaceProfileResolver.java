package ai.ravenroot.extensions.gitworkspace;

import java.util.Optional;

/** Resolves tenant-scoped, operator-owned Git workspace authority. */
@FunctionalInterface
public interface GitWorkspaceProfileResolver {
    Optional<GitWorkspaceProfile> resolve(String tenant, String profile);
}
