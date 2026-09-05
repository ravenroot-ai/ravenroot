package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the reusable node-package contract against both model-backed AI behaviors. */
class AiNodePackageConformanceTest extends NodeBehaviorContract {

    @Override
    protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override
            public String id() {
                return AiNodePackage.ID;
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
            public List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                return List.of(
                        new LlmPromptNodeBehavior(name -> Optional.empty()),
                        new AgentNodeBehavior(name -> Optional.empty(), name -> Optional.empty()));
            }
        };
    }

    @Override
    protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        Map<String, Object> properties = switch (descriptor.behavior()) {
            case LlmPromptNodeBehavior.BEHAVIOR -> Map.of(
                    "provider", "", "prompt", "conformance");
            case AgentNodeBehavior.BEHAVIOR -> Map.of(
                    "provider", "", "instructions", "conformance", "objective", "conformance");
            default -> throw new AssertionError("unexpected AI behavior " + descriptor.behavior());
        };
        return new NodeConfiguration("conformance-node", descriptor.behavior(), properties);
    }

    @Test
    void coversThePromptAndAgentBehaviors() {
        assertEquals(Set.of("llm-prompt", "agent"), nodePackage().behaviors().stream()
                .map(behavior -> behavior.descriptor().behavior())
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
