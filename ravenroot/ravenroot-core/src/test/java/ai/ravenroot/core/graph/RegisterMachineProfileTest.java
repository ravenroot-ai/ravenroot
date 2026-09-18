package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterMachineProfileTest {
    @Test
    void separatesStructuralErrorsFromConservativeWarningsDeterministically() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                bigint("test", "equal", "field:r", "literal:+0", "isZero"),
                new GraphNode("branch", NodeKind.BEHAVIOR, "cel-decision", Map.of(
                        "expression", "payload.isZero", "trueOutcome", "zero", "falseOutcome", "zero")),
                bigint("decrement", "subtract", "field:r", "literal:1", "other"),
                GraphNode.behavior("external", "program"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "test"), GraphEdge.to("test", "branch"),
                new GraphEdge("branch", "decrement", "zero"),
                GraphEdge.to("decrement", "end")));

        var first = RegisterMachineProfile.validate(graph);
        var second = RegisterMachineProfile.validate(graph);

        assertEquals(first, second);
        assertFalse(first.conforms());
        assertEquals(List.of("AMBIGUOUS_DECISION_OUTCOMES", "NODE_OUTSIDE_PROFILE"),
                first.errors().stream().map(RegisterMachineProfile.Diagnostic::code).toList());
        assertTrue(codes(first.warnings()).containsAll(List.of(
                "NONCANONICAL_DECIMAL", "READ_BEFORE_INITIALIZATION", "REGISTER_OVERWRITE",
                "UNGUARDED_DECREMENT", "UNREACHABLE_NODE")));
    }

    @Test
    void acceptsTheCanonicalDecjzExpansionAndStillNamesAnUnloggedCycle() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                bigint("initialize", "copy", "literal:2", null, "r"),
                bigint("zero-test", "equal", "field:r", "literal:0", "isZero"),
                new GraphNode("branch", NodeKind.BEHAVIOR, "cel-decision", Map.of(
                        "expression", "payload.isZero", "trueOutcome", "zero", "falseOutcome", "nonzero")),
                bigint("decrement", "subtract", "field:r", "literal:1", "r"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "initialize"), GraphEdge.to("initialize", "zero-test"),
                GraphEdge.to("zero-test", "branch"), new GraphEdge("branch", "end", "zero"),
                new GraphEdge("branch", "decrement", "nonzero"), GraphEdge.to("decrement", "zero-test")));

        var report = RegisterMachineProfile.validate(graph);

        assertTrue(report.conforms(), report.errors().toString());
        assertEquals(List.of("UNOBSERVABLE_CYCLE"), codes(report.warnings()));
    }

    @Test
    void boundsDiagnosticsAndReportsTruncation() {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to("start", "end"));
        for (int index = 0; index < RegisterMachineProfile.MAX_DIAGNOSTICS + 20; index++) {
            nodes.add(GraphNode.behavior("outside-" + index, "program"));
        }
        var report = RegisterMachineProfile.validate(new GraphDefinition(nodes, edges));
        assertTrue(report.truncated());
        assertEquals(RegisterMachineProfile.MAX_DIAGNOSTICS,
                report.errors().size() + report.warnings().size());
    }

    @Test
    void rejectsForkedArithmeticBecauseTheProfileHasOneActiveControlPath() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                bigint("set", "copy", "literal:1", null, "r"),
                bigint("left", "copy", "literal:2", null, "r"),
                bigint("right", "copy", "literal:3", null, "r"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "set"),
                GraphEdge.to("set", "left"),
                GraphEdge.to("set", "right"),
                GraphEdge.to("left", "end"),
                GraphEdge.to("right", "end")));

        var report = RegisterMachineProfile.validate(graph);

        assertEquals(List.of("CONTROL_PATH_ARITY"), codes(report.errors()));
    }

    private static List<String> codes(List<RegisterMachineProfile.Diagnostic> diagnostics) {
        return diagnostics.stream().map(RegisterMachineProfile.Diagnostic::code).toList();
    }

    private static GraphNode bigint(String id, String operation, String left, String right, String target) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("operation", operation); properties.put("left", left);
        if (right != null) properties.put("right", right);
        properties.put("target", target);
        return new GraphNode(id, NodeKind.BEHAVIOR, "bigint-op", properties);
    }
}
