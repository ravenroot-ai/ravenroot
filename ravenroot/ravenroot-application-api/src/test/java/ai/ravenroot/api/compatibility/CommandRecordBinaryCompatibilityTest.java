package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.execution.NodeCommand;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Historical record constructors must remain source- and binary-linkable as components are added. */
class CommandRecordBinaryCompatibilityTest {
    private static final List<String> INVOCATION_ARGUMENTS = List.of(
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")", "\"node\"",
            "java.util.Set.<java.util.UUID>of()",
            "ai.ravenroot.api.application.NodeInvocationStatus.SCHEDULED", "java.util.List.of()");
    private static final List<String> OUTCOME_ARGUMENTS = List.of(
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")",
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000002\")",
            "ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING", "null", "java.util.Set.<String>of()",
            "java.util.Set.<String>of()");

    /** The handled-failure shape: the six above plus {@code bypassedNodes} and
     * {@code handledFailureNodes}. At risk when {@code untakenEdges} is added. */
    private static final List<String> OUTCOME_ARGUMENTS_WITH_HANDLED_FAILURES = List.of(
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")",
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000002\")",
            "ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING", "null", "java.util.Set.<String>of()",
            "java.util.Set.<String>of()", "java.util.Set.<String>of()", "java.util.Set.<String>of()");

    @TestFactory
    Stream<DynamicTest> historicalConstructorsStillLink() {
        return Stream.of(
                shape("NodeInvocation before command", NodeInvocation.class,
                        List.of(UUID.class, String.class, Set.class, NodeInvocationStatus.class, List.class),
                        INVOCATION_ARGUMENTS),
                shape("ExecutionOutcome before bypassed nodes", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class), OUTCOME_ARGUMENTS),
                shape("ExecutionOutcome before untaken edges", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class, Set.class, Set.class), OUTCOME_ARGUMENTS_WITH_HANDLED_FAILURES))
                .map(entry -> DynamicTest.dynamicTest(entry.description(), () ->
                        assertTrue(BinaryCompatibility.linksAgainstCurrent(entry.type(), entry.shape()),
                                entry.description() + " no longer links against the shipping class file")));
    }

    @TestFactory
    Stream<DynamicTest> compiledClassFilesDeclareHistoricalDescriptors() {
        return Stream.of(
                shape("NodeInvocation before command", NodeInvocation.class,
                        List.of(UUID.class, String.class, Set.class, NodeInvocationStatus.class, List.class),
                        INVOCATION_ARGUMENTS),
                shape("ExecutionOutcome before bypassed nodes", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class), OUTCOME_ARGUMENTS),
                shape("ExecutionOutcome before untaken edges", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class, Set.class, Set.class), OUTCOME_ARGUMENTS_WITH_HANDLED_FAILURES))
                .map(entry -> DynamicTest.dynamicTest(entry.description(), () -> assertTrue(
                        BinaryCompatibility.declaredConstructorDescriptors(entry.type()).contains(
                                entry.shape().descriptor()), entry.description() + " descriptor is absent")));
    }

    @Test
    void historicalSourceCallsCompileInThisModuleAndDefaultTheNewComponents() {
        UUID id = UUID.randomUUID();
        assertEquals(NodeCommand.PROCESS,
                new NodeInvocation(id, "node", Set.of(), NodeInvocationStatus.SCHEDULED, List.of()).command());
        assertTrue(new ExecutionOutcome(id, UUID.randomUUID(), ProcessInstanceStatus.RUNNING, null, Set.of(),
                Set.of()).bypassedNodes().isEmpty());
        assertTrue(new ExecutionOutcome(id, UUID.randomUUID(), ProcessInstanceStatus.RUNNING, null, Set.of(),
                Set.of(), Set.of(), Set.of()).untakenEdges().isEmpty());
    }

    @Test
    void negativeControlDoesNotLinkOrAppearInTheClassFile() throws Exception {
        var impossible = new BinaryCompatibility.ConstructorShape("never published", List.of(UUID.class,
                String.class, Set.class, NodeInvocationStatus.class), INVOCATION_ARGUMENTS.subList(0, 4));
        assertFalse(BinaryCompatibility.linksAgainstCurrent(NodeInvocation.class, impossible));
        assertFalse(BinaryCompatibility.declaredConstructorDescriptors(NodeInvocation.class)
                .contains(impossible.descriptor()));
    }

    private static Entry shape(String description, Class<?> type, List<Class<?>> parameters, List<String> arguments) {
        return new Entry(description, type, new BinaryCompatibility.ConstructorShape(description, parameters, arguments));
    }

    private record Entry(String description, Class<?> type, BinaryCompatibility.ConstructorShape shape) { }
}
