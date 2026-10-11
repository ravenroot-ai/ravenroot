package ai.ravenroot.extensions.systemone;

import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorPropertySchema;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOneEmailTriageExampleTest {
    @Test
    void runnableEmailTriageGraphParsesAndComposesAgainstTheRealCatalog() throws Exception {
        Path example = Path.of("..", "..", "..", "docs", "examples", "system-one",
                "email-triage.graphml").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(example), example.toString());
        SystemOneProfile profile = SystemOneTestSupport.profile("fixture-model");
        var services = new SystemOneTestSupport.HttpDouble();
        BehaviorRegistry registry = NodePackages.registerAll(BehaviorRegistry.standard(),
                List.of(new SystemOneNodePackage(name -> Optional.of(profile))),
                NodePackageServiceRegistry.builder().grant(SystemOneNodePackage.ID, services).build());

        try (var input = Files.newInputStream(example); var graph = GraphManager.readGraphMl(input)) {
            new BehaviorPropertySchema(registry).validate(graph.definition());
            graph.definition().nodes().stream()
                    .filter(node -> node.kind() == NodeKind.BEHAVIOR)
                    .forEach(node -> assertTrue(registry.create(node).isPresent(), node.id()));
        }
    }
}
