package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

class GitWorkspaceNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path temporary;

    @Override
    protected NodePackage nodePackage() {
        try {
            Path authority = Files.createDirectories(temporary.resolve("authority"));
            Path remote = Files.createDirectories(temporary.resolve("remote"));
            Path git = Path.of(GitWorkspaceTestSupport.run(temporary, "sh", "-c", "command -v git").trim());
            GitWorkspaceProfile profile = new GitWorkspaceProfile("tenant-a", "workspace", authority,
                    remote.toRealPath().toUri().toASCIIString(), "refs/heads/dev", "refs/heads/issues/", git,
                    "sha1", null, null, Duration.ofSeconds(5), 1, 64 * 1024, 10);
            return new GitWorkspaceNodePackage((tenant, name) -> Optional.of(profile));
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Override
    protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("conformance-node", descriptor.behavior(),
                Map.of("workspaceProfile", "workspace"));
    }
}
