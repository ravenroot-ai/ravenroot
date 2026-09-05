package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.execution.NodeCommand;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
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

    /**
     * The full-node-sets shape: the eight above plus {@code untakenEdges}. It stopped being the
     * canonical constructor when {@code paused} was added, which is exactly the moment it starts
     * needing to be pinned — this is what {@code ExecutionResultRegistry} and
     * {@code RequestReplyCoordinator} construct, so a silent loss of it would be a source break in
     * core rather than a wire change anyone would notice here.
     */
    private static final List<String> OUTCOME_ARGUMENTS_WITH_UNTAKEN_EDGES = List.of(
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")",
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000002\")",
            "ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING", "null", "java.util.Set.<String>of()",
            "java.util.Set.<String>of()", "java.util.Set.<String>of()", "java.util.Set.<String>of()",
            "java.util.Set.<String>of()");

    /**
     * The shape {@code LiveExecution} shipped with before a hold became observable.
     *
     * <p>This record had no pinned shape at all until now, and it acquired its first added component
     * in the same change that pins it. Leaving it unpinned would mean the next component after
     * {@code paused} silently breaks every caller of the four-argument form, which is the failure
     * this whole file exists to make loud.</p>
     */
    private static final List<String> LIVE_EXECUTION_ARGUMENTS = List.of(
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")",
            "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000002\")", "\"v1\"",
            "java.time.Instant.EPOCH");

    private static Stream<Entry> pinnedShapes() {
        return Stream.of(
                shape("NodeInvocation before command", NodeInvocation.class,
                        List.of(UUID.class, String.class, Set.class, NodeInvocationStatus.class, List.class),
                        INVOCATION_ARGUMENTS),
                shape("ExecutionOutcome before bypassed nodes", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class), OUTCOME_ARGUMENTS),
                shape("ExecutionOutcome before untaken edges", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class, Set.class, Set.class), OUTCOME_ARGUMENTS_WITH_HANDLED_FAILURES),
                shape("ExecutionOutcome before paused", ExecutionOutcome.class,
                        List.of(UUID.class, UUID.class, ProcessInstanceStatus.class, Object.class, Set.class,
                                Set.class, Set.class, Set.class, Set.class),
                        OUTCOME_ARGUMENTS_WITH_UNTAKEN_EDGES),
                shape("LiveExecution before paused", LiveExecution.class,
                        List.of(UUID.class, UUID.class, String.class, Instant.class),
                        LIVE_EXECUTION_ARGUMENTS));
    }

    @TestFactory
    Stream<DynamicTest> historicalConstructorsStillLink() {
        return pinnedShapes()
                .map(entry -> DynamicTest.dynamicTest(entry.description(), () ->
                        assertTrue(BinaryCompatibility.linksAgainstCurrent(entry.type(), entry.shape()),
                                entry.description() + " no longer links against the shipping class file")));
    }

    @TestFactory
    Stream<DynamicTest> compiledClassFilesDeclareHistoricalDescriptors() {
        return pinnedShapes()
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
        assertFalse(new ExecutionOutcome(id, UUID.randomUUID(), ProcessInstanceStatus.RUNNING, null, Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of()).paused(),
                "a caller that never knew about holds must not be reported as holding one");
        assertFalse(new LiveExecution(id, UUID.randomUUID(), "v1", Instant.EPOCH).paused(),
                "and neither must a listing row assembled by one");
    }

    /**
     * A terminal outcome is never paused, whatever a caller asks for.
     *
     * <p>The invariant is enforced by {@code ExecutionOutcome}'s own compact constructor rather than
     * by the producers that build one, and this is where that placement is pinned. "Paused and
     * completed" is a state no execution can occupy, so it must be unreachable at the type — not
     * merely unproduced by the code paths that happen to exist today. {@code withPaused} is checked
     * on the same footing, because it is the one supported way to add a hold to an outcome and would
     * otherwise be a second door into the same impossible state.</p>
     */
    @Test
    void aTerminalOutcomeCannotCarryAHoldThroughEitherDoor() {
        UUID id = UUID.randomUUID();
        for (ProcessInstanceStatus terminal : List.of(ProcessInstanceStatus.COMPLETED, ProcessInstanceStatus.FAILED)) {
            assertFalse(new ExecutionOutcome(id, UUID.randomUUID(), terminal, null, Set.of(), Set.of(),
                            Set.of(), Set.of(), Set.of(), true).paused(),
                    terminal + " must never be reported as holding");
            assertFalse(new ExecutionOutcome(id, UUID.randomUUID(), terminal, null, Set.of(), Set.of(),
                            Set.of(), Set.of(), Set.of()).withPaused(true).paused(),
                    terminal + " must not acquire a hold through withPaused either");
        }
        // Control: the same two doors do carry a hold while the execution is still running, so the
        // refusals above are about the status and not about the doors being broken.
        assertTrue(new ExecutionOutcome(id, UUID.randomUUID(), ProcessInstanceStatus.RUNNING, null, Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), true).paused());
        assertTrue(new ExecutionOutcome(id, UUID.randomUUID(), ProcessInstanceStatus.RUNNING, null, Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of()).withPaused(true).paused());
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
