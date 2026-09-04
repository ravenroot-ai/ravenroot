package ai.ravenroot.testkit.api;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
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

/** Deliberately fails only the blank-adapter refusal contract; not discovered directly by Surefire. */
final class NonCompliantBlankAdapterBindingContract extends NodeBehaviorContract {
    @Override
    protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override
            public String id() {
                return "noncompliant-blank-adapter";
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
                return List.of(new PartlyCompliantBehavior());
            }
        };
    }

    private static final class PartlyCompliantBehavior implements NodeBehavior {
        private static final String REFUSING_BINDING = "deploymentGateway";
        private static final String NONCOMPLIANT_BINDING = "operatorChannel";

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("partial-adapter-guard", "Partial adapter guard", "Test",
                    "Refuses one blank adapter but deliberately runs without the other.", "actor", false,
                    List.of(
                            NodePropertyDescriptor.adapterId(REFUSING_BINDING, "Deployment gateway",
                                    NodePropertyType.STRING, "First deployment-owned binding."),
                            NodePropertyDescriptor.adapterId(NONCOMPLIANT_BINDING, "Operator channel",
                                    NodePropertyType.STRING, "Second deployment-owned binding.")),
                    Set.of()).withOutcomes(NodeOutcomeDescriptor.literal("continue", "Produced content."));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            boolean refuse = NodePropertyDescriptor.adapterIdOf(
                    configuration.properties().get(REFUSING_BINDING)).isEmpty();
            if (refuse) {
                return message -> CompletableFuture.failedFuture(
                        new IllegalStateException("deployment gateway is unconfigured"));
            }
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith("must not run"));
        }
    }
}
