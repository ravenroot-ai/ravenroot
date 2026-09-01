package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/**
 * The AI node bundle: the node types that invoke a model, with their own adapter inside.
 *
 * <p>Two node types, and the second is not a variant of the first. {@code llm-prompt} makes one
 * call; {@code agent} runs a bounded loop of model turns and tool calls. They share this bundle,
 * the model profile, the managed channel and the provenance marking, and share no behaviour.</p>
 *
 * <p>Never shipped with the product jar and never with its default image. An operator compiles it,
 * installs it, names it in
 * {@code RAVENROOT_ENABLED_PLUGINS} and rebuilds their own image -- four deliberate acts. See this
 * module's {@code pom.xml} for what enforces that rather than merely stating it.</p>
 */
public final class AiNodePackage implements NodePackage {
    /** The package id an operator names in {@code RAVENROOT_ENABLED_PLUGINS} and in a service grant. */
    public static final String ID = "ai.ravenroot.extensions.ai";

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() {
        return List.of(new LlmPromptNodeBehavior(), new AgentNodeBehavior());
    }
}
