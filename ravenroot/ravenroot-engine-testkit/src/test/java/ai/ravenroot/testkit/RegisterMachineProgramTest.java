package ai.ravenroot.testkit;

import ai.ravenroot.core.graph.BigIntOpContract;
import ai.ravenroot.core.graph.RegisterMachineProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterMachineProgramTest {
    @Test
    void setLiteralBoundaryIsIdenticalForCompilerOracleAndProfile() {
        String accepted = "1" + "0".repeat(BigIntOpContract.MAX_DECIMAL_DIGITS - 1);
        String rejected = "1" + "0".repeat(BigIntOpContract.MAX_DECIMAL_DIGITS);
        var acceptedProgram = List.<RegisterMachineProgram.Instruction>of(
                new RegisterMachineProgram.Set("r", accepted), new RegisterMachineProgram.Halt());

        var compiled = RegisterMachineProgram.compile(acceptedProgram, Map.of());
        assertTrue(RegisterMachineProfile.validate(compiled.graph()).conforms());
        assertEquals(accepted, RegisterMachineProgram.interpret(acceptedProgram, Map.of(), 2)
                .registers().get("r"));

        var rejectedProgram = List.<RegisterMachineProgram.Instruction>of(
                new RegisterMachineProgram.Set("r", rejected), new RegisterMachineProgram.Halt());
        assertThrows(IllegalArgumentException.class,
                () -> RegisterMachineProgram.compile(rejectedProgram, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RegisterMachineProgram.interpret(rejectedProgram, Map.of(), 2));
    }

    @Test
    void initialRegisterBoundaryIsIdenticalForCompilerOracleAndProfile() {
        String accepted = "1" + "0".repeat(BigIntOpContract.MAX_DECIMAL_DIGITS - 1);
        String rejected = "1" + "0".repeat(BigIntOpContract.MAX_DECIMAL_DIGITS);
        var halt = List.<RegisterMachineProgram.Instruction>of(new RegisterMachineProgram.Halt());

        var compiled = RegisterMachineProgram.compile(halt, Map.of("r", accepted));
        assertTrue(RegisterMachineProfile.validate(compiled.graph()).conforms());
        assertEquals(accepted, RegisterMachineProgram.interpret(halt, Map.of("r", accepted), 1)
                .registers().get("r"));

        assertThrows(IllegalArgumentException.class,
                () -> RegisterMachineProgram.compile(halt, Map.of("r", rejected)));
        assertThrows(IllegalArgumentException.class,
                () -> RegisterMachineProgram.interpret(halt, Map.of("r", rejected), 1));
    }

    @Test
    void everySuccessfullyCompiledInstructionShapeConformsToProfileV1() {
        List<List<? extends RegisterMachineProgram.Instruction>> programs = List.of(
                List.of(new RegisterMachineProgram.Set("r", "2"), new RegisterMachineProgram.Halt()),
                List.of(new RegisterMachineProgram.Increment("r"), new RegisterMachineProgram.Halt()),
                List.of(new RegisterMachineProgram.DecrementOrJumpZero("r", 1, 0),
                        new RegisterMachineProgram.Halt()));

        for (var program : programs) {
            var compiled = RegisterMachineProgram.compile(program, Map.of("r", "1"));
            var report = RegisterMachineProfile.validate(compiled.graph());
            assertTrue(report.conforms(), report.errors().toString());
        }
    }
}
