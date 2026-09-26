package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionException;
import ai.ravenroot.api.application.GraphAdmissionFinding;
import ai.ravenroot.api.application.GraphAdmissionPurpose;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Representative proof that inspection and mutation use one admission decision. */
class GraphAdmissionParityTest {
    @TestFactory
    Stream<DynamicTest> representativeRefusalsMatchInspectionAndMutation() {
        var validator = new GraphAdmissionValidator(registry(), GraphExecutionLimits.DEFAULTS);
        return Stream.of(
                new RefusalCase("semantic structure", WORKER_GRAPH.replace(">BEHAVIOR<", ">SUBGRAPH<"),
                        GraphAdmissionPurpose.EXECUTION),
                new RefusalCase("property schema", WORKER_GRAPH
                        .replace("<key id=\"outcome\"", "<key id=\"setting\" for=\"node\" "
                                + "attr.name=\"setting\" attr.type=\"string\"/>\n  <key id=\"outcome\"")
                        .replace("</data></node>\n    <node id=\"end\"",
                                "</data><data key=\"setting\">not-an-integer</data></node>\n"
                                        + "    <node id=\"end\""),
                        GraphAdmissionPurpose.EXECUTION),
                new RefusalCase("runtime nature", WORKER_GRAPH
                        .replace("<key id=\"outcome\"", "<key id=\"nature\" for=\"node\" "
                                + "attr.name=\"runtime.nature\" attr.type=\"string\"/>\n  <key id=\"outcome\"")
                        .replace("</data></node>\n    <node id=\"end\"",
                                "</data><data key=\"nature\">SOURCE</data></node>\n"
                                        + "    <node id=\"end\""),
                        GraphAdmissionPurpose.EXECUTION),
                new RefusalCase("capability", WORKER_GRAPH.replace("test.worker", "test.capability"),
                        GraphAdmissionPurpose.EXECUTION),
                new RefusalCase("source-session purpose", WORKER_GRAPH,
                        GraphAdmissionPurpose.SOURCE_SESSION))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    byte[] exactBytes = testCase.graphMl().getBytes(StandardCharsets.UTF_8);
                    var inspected = validator.inspect(exactBytes, testCase.purpose());
                    assertFalse(inspected.valid());
                    GraphAdmissionFinding inspectionFinding = inspected.findings().getFirst();

                    GraphAdmissionException refused = assertThrows(GraphAdmissionException.class,
                            () -> validator.require(exactBytes, testCase.purpose()));
                    GraphAdmissionFinding mutationFinding = refused.findings().getFirst();

                    assertEquals(inspectionFinding.phase(), mutationFinding.phase());
                    assertEquals(inspectionFinding.reason(), mutationFinding.reason());
                    assertEquals(inspectionFinding.nodeId(), mutationFinding.nodeId());
                    assertEquals(inspectionFinding.nodeRef(), mutationFinding.nodeRef());
                    assertEquals(inspectionFinding.propertyName(), mutationFinding.propertyName());
                }));
    }

    private static BehaviorRegistry registry() {
        NodeTypeDescriptor worker = new NodeTypeDescriptor("test.worker", "Worker", "Test", "Worker",
                "actor", false, List.of(NodePropertyDescriptor.optional("setting", "Setting",
                NodePropertyType.INTEGER, "Test setting", "1")), Set.of(),
                NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER));
        NodeTypeDescriptor unavailable = new NodeTypeDescriptor("test.capability", "Unavailable", "Test",
                "Unavailable", "actor", false, List.of(), Set.of(),
                NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER));
        return new BehaviorRegistry()
                .registerFactory(factory(worker, false))
                .registerFactory(factory(unavailable, true));
    }

    private static NodeBehaviorFactory factory(NodeTypeDescriptor descriptor, boolean refuseValidation) {
        return new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() { return descriptor; }
            @Override public void validate(ai.ravenroot.core.graph.GraphNode node) {
                if (refuseValidation) throw new IllegalStateException("trusted capability unavailable");
            }
            @Override public NodeHandler create(ai.ravenroot.core.graph.GraphNode node) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        };
    }

    private record RefusalCase(String name, String graphMl, GraphAdmissionPurpose purpose) { }

    private static final String WORKER_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="worker"><data key="kind">BEHAVIOR</data><data key="behavior">test.worker</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="worker"><data key="outcome">continue</data></edge>
                <edge source="worker" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;
}
