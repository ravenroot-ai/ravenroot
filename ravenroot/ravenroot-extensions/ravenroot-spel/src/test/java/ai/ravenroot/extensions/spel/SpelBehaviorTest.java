package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.node.NodeConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelBehaviorTest {
    @Test
    @SuppressWarnings("unchecked")
    void transformReturnsOnlyTheBoundedCanonicalResultAndPreservesAttributes() {
        var action = new SpelTransformNodeBehavior().create(new NodeConfiguration(
                "transform", "spel.transform", Map.of("expression", "customer")));
        var result = action.handle(SpelTestSupport.message(Map.of(
                "customer", Map.of("name", "Ada", "roles", List.of("author", "admin")))))
                .toCompletableFuture().join();

        assertEquals(Map.of("name", "Ada", "roles", List.of("author", "admin")), result.payload());
        assertEquals(Map.of("trace", "preserved"), result.attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) result.payload()).put("new", "value"));
    }

    @Test
    void decisionRequiresAnExactBooleanAndUsesConfiguredOutcomes() {
        var action = new SpelDecisionNodeBehavior().create(new NodeConfiguration(
                "decision", "spel.decision", Map.of(
                "expression", "age >= 18", "trueOutcome", "adult", "falseOutcome", "minor")));
        assertEquals("adult", action.handle(SpelTestSupport.message(Map.of("age", 20L)))
                .toCompletableFuture().join().outcome());
        assertEquals("minor", action.handle(SpelTestSupport.message(Map.of("age", 17L)))
                .toCompletableFuture().join().outcome());

        var wrongType = new SpelDecisionNodeBehavior().create(new NodeConfiguration(
                "decision", "spel.decision", Map.of("expression", "age")));
        SpelNodeException failure = SpelTestSupport.failure(assertThrows(RuntimeException.class,
                () -> wrongType.handle(SpelTestSupport.message(Map.of("age", 20L)))
                        .toCompletableFuture().join()));
        assertEquals(SpelNodeException.Code.DECISION_NOT_BOOLEAN, failure.code());
    }

    @Test
    void refusesNonCanonicalObjectsOversizedCollectionsAndForbiddenKeysAtAnyDepth() {
        var action = new SpelTransformNodeBehavior().create(new NodeConfiguration(
                "transform", "spel.transform", Map.of("expression", "'safe'")));

        assertFailure(action, new Object(), SpelNodeException.Code.INPUT_REJECTED);
        var oversized = new ArrayList<Integer>();
        for (int index = 0; index <= 256; index++) oversized.add(index);
        assertFailure(action, oversized, SpelNodeException.Code.INPUT_REJECTED);
        assertFailure(action, Map.of("nested", Map.of("ClAsS", "forbidden")),
                SpelNodeException.Code.FORBIDDEN_PROPERTY);

        SpelNodeException forbiddenResult = assertThrows(SpelNodeException.class,
                () -> new SpelTransformNodeBehavior().create(new NodeConfiguration(
                        "transform", "spel.transform", Map.of("expression", "{'Constructor':'x'}"))));
        assertEquals(SpelNodeException.Code.FORBIDDEN_PROPERTY, forbiddenResult.code());

        for (String forbidden : List.of("class", "GETCLASS", "metaClass", "ClassLoader",
                "declaringClass", "protectionDomain", "__proto__", "prototype", "constructor")) {
            assertFailure(action, Map.of("nested", Map.of(forbidden, "rejected")),
                    SpelNodeException.Code.FORBIDDEN_PROPERTY);
        }
    }

    @Test
    void canonicalResultTextLimitAcceptsTheBoundaryAndRejectsTheNextCharacter() {
        assertEquals("x".repeat(SpelBounds.TREE.maxTextLength()),
                CanonicalTree.result("x".repeat(SpelBounds.TREE.maxTextLength())));
        SpelNodeException failure = assertThrows(SpelNodeException.class,
                () -> CanonicalTree.result("x".repeat(SpelBounds.TREE.maxTextLength() + 1)));
        assertEquals(SpelNodeException.Code.RESULT_REJECTED, failure.code());
    }

    @Test
    void concurrentInvocationsNeverLeakRootsAcrossEvaluationContexts() {
        var action = new SpelTransformNodeBehavior().create(new NodeConfiguration(
                "transform", "spel.transform", Map.of("expression", "value")));
        var stages = new ArrayList<java.util.concurrent.CompletableFuture<ai.ravenroot.api.execution.NodeResult>>();
        for (long value = 0; value < 64; value++) {
            stages.add(action.handle(SpelTestSupport.message(Map.of("value", value))).toCompletableFuture());
        }
        int completed = 0;
        for (int index = 0; index < stages.size(); index++) {
            try {
                assertEquals((long) index, stages.get(index).join().payload());
                completed++;
            } catch (RuntimeException refused) {
                assertEquals(SpelNodeException.Code.CAPACITY_UNAVAILABLE,
                        SpelTestSupport.failure(refused).code());
            }
        }
        assertTrue(completed >= SpelBounds.PER_NODE_CONCURRENCY);
        assertFalse(stages.isEmpty());
    }

    private static void assertFailure(ai.ravenroot.api.node.NodeAction action, Object payload,
                                      SpelNodeException.Code code) {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> action.handle(SpelTestSupport.message(payload)).toCompletableFuture().join());
        SpelNodeException failure = SpelTestSupport.failure(thrown);
        assertEquals(code, failure.code());
        assertEquals("SPEL_" + code.name(), failure.getMessage());
    }
}
