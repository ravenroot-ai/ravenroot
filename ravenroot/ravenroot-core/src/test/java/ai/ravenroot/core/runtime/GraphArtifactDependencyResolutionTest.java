package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.GraphAdmissionException;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphArtifactDependencyResolutionTest {
    @Test void resolvesAliasedInlineProgramAndInstalledPackageWithoutExecutionOrCreation() {
        var artifacts = new InMemoryArtifactRegistry();
        var runtime = new ObservationalRuntime();
        String source = "return payload";
        GeneratedArtifact active = artifacts.create("javascript", source,
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a"));
        for (ArtifactState target : List.of(ArtifactState.VALIDATED, ArtifactState.TESTED,
                ArtifactState.APPROVED, ArtifactState.ACTIVE)) {
            active = artifacts.transition(active.id(), active.state(), target);
        }
        var environment = new BehaviorEnvironment(null, null, artifacts, runtime, null, null,
                OutboundHttpPolicy.disabled());
        var behaviors = NodePackages.register(BehaviorRegistry.standard(environment), package_());
        var application = new DefaultRavenrootApplication(null, new ExecutionMonitor(), behaviors,
                artifacts, runtime);
        try {
            int before = artifacts.list().size();
            var dependencies = application.resolveGraphArtifactDependencies(TestIdentities.TENANT_A,
                    new ByteArrayInputStream(graph(source)));

            assertEquals(List.of("published-package"), dependencies.nodePackages().stream()
                    .map(ai.ravenroot.api.persistence.PinnedNodePackage::packageId).toList());
            assertEquals(List.of(active.id()), dependencies.programs().stream()
                    .map(ai.ravenroot.api.application.GraphProgramDependency::artifactId).toList());
            assertEquals(before, artifacts.list().size());
            assertEquals(0, runtime.executionCount);

            assertThrows(GraphAdmissionException.class, () -> application.resolveGraphArtifactDependencies(
                    TestIdentities.TENANT_A, new ByteArrayInputStream(
                    replace(graph(source), "published-behavior", "missing-behavior"))));
            assertThrows(GraphAdmissionException.class, () -> application.resolveGraphArtifactDependencies(
                    TestIdentities.TENANT_A, new ByteArrayInputStream(graph("changed source"))));

            artifacts.transition(active.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED);
            assertThrows(GraphAdmissionException.class, () -> application.resolveGraphArtifactDependencies(
                    TestIdentities.TENANT_A, new ByteArrayInputStream(graph(source))));
        } finally {
            application.close();
        }
    }

    private static NodePackage package_() {
        NodeBehavior behavior = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("published-behavior", "Published", "Test", "Test package",
                        "box", false, List.of(), Set.of());
            }
            @Override public ai.ravenroot.api.node.NodeAction create(NodeConfiguration configuration) {
                return message -> CompletableFuture.completedFuture(
                        new NodeResult("continue", message.payload(), message.attributes()));
            }
        };
        return new NodePackage() {
            @Override public String id() { return "published-package"; }
            @Override public String version() { return "1.2.3"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static byte[] graph(String source) {
        return ("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="a" for="node" attr.name="kind" attr.type="string"/>
                  <key id="b" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="c" for="node" attr.name="language" attr.type="string"/>
                  <key id="d" for="node" attr.name="source" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="start"><data key="a">START</data></node>
                    <node id="program"><data key="a">BEHAVIOR</data><data key="b">program</data>
                      <data key="c">javascript</data><data key="d">%s</data></node>
                    <node id="package"><data key="a">BEHAVIOR</data><data key="b">published-behavior</data></node>
                    <node id="end"><data key="a">END</data></node>
                    <edge source="start" target="program"/><edge source="program" target="package"/>
                    <edge source="package" target="end"/>
                  </graph>
                </graphml>
                """).formatted(source).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] replace(byte[] value, String from, String to) {
        return new String(value, StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    private static final class ObservationalRuntime implements ProgramRuntime {
        private int executionCount;
        @Override public String id() { return "observational-runtime"; }
        @Override public String compatibilityFingerprint() { return "observational-runtime-v1"; }
        @Override public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
            executionCount++;
            return CompletableFuture.completedFuture(request.payload());
        }
    }
}
