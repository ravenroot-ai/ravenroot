package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A ready, passive real SOURCE for tests of unsolicited arrivals, independent from initial Run. */
final class ManualSourceFixture {
    private ManualSourceFixture() { }
    static BehaviorRegistry register(BehaviorRegistry registry) {
        class Source implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("fixture-source", "Fixture source", "Test", "Passive ingress", "actor", false,
                        List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                throw new AssertionError("SOURCE emission is runtime-owned; its ordinary action must not be composed");
            }
            @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
                return new InboundSource() {
                    @Override public CompletionStage<Void> start(InboundSourceContext active) { return CompletableFuture.completedFuture(null); }
                    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                };
            }
        }
        return NodePackages.register(registry, new NodePackage() {
            @Override public String id() { return "test.manual-source"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(new Source()); }
        });
    }
    /** Keeps the old entry ID and outgoing topology, with a separate, disconnected real START. */
    static String graph(String original) {
        String start = "<node id=\"start\"><data key=\"node-kind\">START</data></node>";
        if (original.indexOf(start) < 0 || original.indexOf(start) != original.lastIndexOf(start))
            throw new IllegalArgumentException("review the manual SOURCE graph fixture after changing its START shape");
        var key = java.util.regex.Pattern.compile("<key id=\"([^\"]+)\"[^>]*attr.name=\"behavior\"").matcher(original);
        String behaviorKey;
        if (key.find()) behaviorKey = key.group(1);
        else {
            behaviorKey = "fixture-behavior";
            original = original.replace("<graph id=", "<key id=\"fixture-behavior\" for=\"node\" attr.name=\"behavior\" attr.type=\"string\"/><graph id=");
        }
        return original.replace(start, "<node id=\"initial-start\"><data key=\"node-kind\">START</data></node>"
                + "<node id=\"start\"><data key=\"node-kind\">BEHAVIOR</data><data key=\"" + behaviorKey
                + "\">fixture-source</data></node>");
    }
}
