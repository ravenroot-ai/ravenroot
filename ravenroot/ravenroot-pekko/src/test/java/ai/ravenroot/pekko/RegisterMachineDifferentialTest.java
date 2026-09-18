package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.RegisterMachineProfile;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.testkit.RegisterMachineProgram;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterMachineDifferentialTest {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "register-machine", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    @Property(tries = 40)
    void compiledAdditionMatchesIndependentInterpreterIncludingHaltAndStepCount(
            @ForAll @IntRange(max = 30) int left, @ForAll @IntRange(max = 30) int right) throws Exception {
        var instructions = additionProgram();
        var initial = Map.of("a", Integer.toString(left), "b", Integer.toString(right), "z", "0");
        var expected = RegisterMachineProgram.interpret(instructions, initial, 1_000);
        var compiled = RegisterMachineProgram.compile(instructions, initial);
        var monitor = new ExecutionMonitor();

        // Execute what the compiler serialized, not its in-memory construction.
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(
                    compiled.graphMl().getBytes(StandardCharsets.UTF_8)));
             var engine = new PekkoExecutionEngine("register-machine-" + UUID.randomUUID());
             var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard(), monitor)) {
            var future = runner.execute(IDENTITY, Map.of()).toCompletableFuture();
            ai.ravenroot.core.runtime.GraphExecutionResult actual;
            try {
                actual = future.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new AssertionError(monitor.eventsAfter(0).stream()
                        .map(event -> event.type() + ":" + event.nodeId() + ":" + event.detail()).toList().toString(), timeout);
            }
            var actualPayload = (Map<?, ?>) actual.payload();
            var actualRegisters = expected.registers().keySet().stream().collect(java.util.stream.Collectors.toMap(
                    name -> name, actualPayload::get));
            assertEquals(expected.registers(), actualRegisters);
            assertTrue(actual.visitedNodes().contains(compiled.instructionEntries().get(expected.haltInstruction())));
            long actualSteps = monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_STARTED)
                    .filter(event -> compiled.instructionEntries().contains(event.nodeId())).count();
            assertEquals(expected.steps(), actualSteps);
        }
    }

    @Test
    void compilerEmitsPortableConformingGraphMlAndOracleBoundsNontermination() {
        var loop = List.<RegisterMachineProgram.Instruction>of(
                new RegisterMachineProgram.DecrementOrJumpZero("z", 0, 0),
                new RegisterMachineProgram.Halt());
        var compiled = RegisterMachineProgram.compile(loop, Map.of("z", "0"));
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(
                compiled.graphMl().getBytes(StandardCharsets.UTF_8)))) {
            assertTrue(RegisterMachineProfile.validate(manager.definition()).conforms());
        }
        var exceeded = assertThrows(RegisterMachineProgram.StepLimitExceededException.class,
                () -> RegisterMachineProgram.interpret(loop, Map.of("z", "0"), 25));
        assertEquals(25, exceeded.limit());
    }

    @Test
    void beyondLongRegisterSurvivesGraphMlExecutionAndCanonicalPayloadJsonExactly() throws Exception {
        String initial = "922337203685477580812345678901234567890";
        String expected = "922337203685477580812345678901234567891";
        var compiled = RegisterMachineProgram.compile(List.of(
                new RegisterMachineProgram.Increment("wide"), new RegisterMachineProgram.Halt()),
                Map.of("wide", initial));
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(
                    compiled.graphMl().getBytes(StandardCharsets.UTF_8)));
             var engine = new PekkoExecutionEngine("register-machine-wide-" + UUID.randomUUID());
             var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard(), new ExecutionMonitor())) {
            var result = runner.execute(IDENTITY, Map.of()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(expected, ((Map<?, ?>) result.payload()).get("wide"));
            String json = PayloadJson.write(PayloadValue.fromJava(result.payload(), PayloadLimits.DEFAULTS));
            assertEquals(result.payload(), PayloadJson.read(json.getBytes(StandardCharsets.UTF_8),
                    PayloadLimits.DEFAULTS).toJava());
            assertTrue(json.contains(expected));
        }
    }

    private static List<RegisterMachineProgram.Instruction> additionProgram() {
        return List.of(
                new RegisterMachineProgram.DecrementOrJumpZero("b", 3, 1),
                new RegisterMachineProgram.Increment("a"),
                new RegisterMachineProgram.DecrementOrJumpZero("z", 0, 0),
                new RegisterMachineProgram.Halt());
    }
}
