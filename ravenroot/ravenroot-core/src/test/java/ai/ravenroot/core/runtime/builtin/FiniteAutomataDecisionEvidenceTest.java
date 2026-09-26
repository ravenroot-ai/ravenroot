package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A program's structured boolean requires a following decision to select an acceptance outcome. */
class FiniteAutomataDecisionEvidenceTest {
    @Test
    void decisionRoutesBothAcceptanceValuesWithoutChangingTheResult() throws Exception {
        var factory = new CelDecisionNodeBehaviorFactory();
        var node = new GraphNode("acceptance", NodeKind.BEHAVIOR, "cel-decision",
                Map.of("expression", "payload.accepted", "trueOutcome", "accepted", "falseOutcome", "rejected"));
        var action = factory.create(node);
        var identity = new SecurityContext("automata-evidence", "test-tenant", "tester", PrincipalType.USER,
                "urn:ravenroot:test");
        for (boolean accepted : new boolean[]{true, false}) {
            var payload = Map.of("accepted", accepted);
            var result = action.handle(new NodeMessage(identity, UUID.randomUUID(), UUID.randomUUID(),
                    "acceptance", payload, Map.of())).toCompletableFuture().get();
            assertEquals(accepted ? "accepted" : "rejected", result.outcome());
            assertEquals(payload, result.payload());
        }
    }
}
