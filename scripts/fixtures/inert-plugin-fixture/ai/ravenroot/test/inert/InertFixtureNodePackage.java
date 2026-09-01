package ai.ravenroot.test.inert;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Purpose-built for {@code scripts/verify-plugin-activation-on-image.sh}
 * only. Not a {@code plugin.sh} known extension, not a reactor module, never named in
 * {@code PUBLISHED_PLUGINS} -- this must never actually ship. Its only job is to be a second, real,
 * independently-built bundle staged alongside the real dogfooding {@code ravenroot-mail} bundle in a
 * Dockerfile.ci-built image, left OUT of {@code RAVENROOT_ENABLED_PLUGINS}, so the script can prove --
 * on a real published image, via a running server's own {@code /v1/node-types} API, not by reading
 * logs alone -- that a second bundle merely present on disk never registers its behavior. If
 * {@code test.inert.probe} ever appears in that endpoint's response during that script's run, this
 * fixture activated when it should not have.
 */
public final class InertFixtureNodePackage implements NodePackage {

    @Override
    public String id() {
        return "ai.ravenroot.test.inert-fixture";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String sdkContract() {
        return NodeSdk.CONTRACT;
    }

    @Override
    public List<NodeBehavior> behaviors() {
        return List.of(new InertProbeBehavior());
    }

    private static final class InertProbeBehavior implements NodeBehavior {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.inert.probe", "Inert Probe", "Test",
                    "Must never be registered unless explicitly enabled via RAVENROOT_ENABLED_PLUGINS",
                    "actor", false, List.of(), Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(null));
        }
    }
}
