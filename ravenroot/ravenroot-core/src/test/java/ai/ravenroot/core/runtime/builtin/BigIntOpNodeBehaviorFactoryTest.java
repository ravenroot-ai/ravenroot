package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BigIntOpNodeBehaviorFactoryTest {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "bigint-test", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");
    private static final Map<String, Object> ATTRIBUTES = Map.of("trace", "kept", "attempt", 7L);

    @Test
    void copiesBeyondLongRangeAndPreservesPayloadAttributesAndTargetAliasing() throws Exception {
        String beyondLong = "92233720368547758081234567890";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("counter", beyondLong);
        payload.put("unrelated", Map.of("kept", true));
        payload.put("nullable", null);

        NodeResult result = execute("copy", "field:counter", null, "counter", payload);

        Map<?, ?> output = assertInstanceOf(Map.class, result.payload());
        assertEquals(beyondLong, output.get("counter"));
        assertEquals(Map.of("kept", true), output.get("unrelated"));
        assertTrue(output.containsKey("nullable"));
        assertEquals(null, output.get("nullable"));
        assertEquals("continue", result.outcome());
        assertEquals(ATTRIBUTES, result.attributes());
    }

    @Test
    void acceptsOnlyExactIntegralRuntimeNumbers() throws Exception {
        assertEquals("42", fieldResult(42));
        assertEquals("42", fieldResult(42L));
        assertEquals("42000", fieldResult(new BigDecimal("4.2E+4")));
        assertEquals("123456789012345678901234567890",
                fieldResult(new BigInteger("123456789012345678901234567890")));

        assertFieldFailure(1.0d, "exact integral runtime number");
        assertFieldFailure(1.0f, "exact integral runtime number");
        assertFieldFailure(new BigDecimal("1.0"), "exact integral runtime number");
        assertFieldFailure(true, "exact integral runtime number");
    }

    @Test
    void floorDivisionAndModuloUseOneMathematicalPairForEverySign() throws Exception {
        assertPair("7", "3", "2", "1");
        assertPair("-7", "3", "-3", "2");
        assertPair("7", "-3", "-3", "-2");
        assertPair("-7", "-3", "2", "-1");
        assertPair("0", "-3", "0", "0");
    }

    @Test
    void enforcesOperandAndResultCeilingsAtTheirBoundaries() throws Exception {
        String maximum = "9".repeat(BigIntOpNodeBehaviorFactory.MAX_DECIMAL_DIGITS);
        assertEquals(maximum, literalResult("copy", maximum, null));
        assertEquals("-" + maximum, literalResult("copy", "-" + maximum, null));

        var tooLong = node("copy", "literal:" + "9".repeat(
                BigIntOpNodeBehaviorFactory.MAX_DECIMAL_DIGITS + 1), null, "answer");
        IllegalArgumentException operand = assertThrows(IllegalArgumentException.class,
                () -> new BigIntOpNodeBehaviorFactory().validate(tooLong));
        assertTrue(operand.getMessage().contains("4096"));

        String half = "9".repeat(BigIntOpNodeBehaviorFactory.MAX_DECIMAL_DIGITS / 2 + 1);
        ExecutionException result = assertThrows(ExecutionException.class,
                () -> execute("multiply", "literal:" + half, "literal:" + half,
                        "answer", Map.of()).payload());
        assertTrue(result.getCause().getMessage().contains("result"));
        assertTrue(result.getCause().getMessage().contains("4096"));
    }

    @Test
    void failuresAreActionableAndNeverEchoUnboundedValues() {
        assertBoundedFailure("copy", "field:absent", null, "answer", Map.of(), "field 'absent' is missing");
        assertBoundedFailure("floor-divide", "literal:1", "literal:0", "answer", Map.of(), "resolved to zero");
        assertBoundedFailure("copy", "field:value", null, "answer",
                Map.of("value", "x".repeat(BigIntOpNodeBehaviorFactory.MAX_DECIMAL_DIGITS)),
                "does not contain a signed base-10 integer");

        ExecutionException wrongPayload = assertThrows(ExecutionException.class,
                () -> execute("copy", "literal:1", null, "answer", "not-a-map"));
        assertTrue(wrongPayload.getCause().getMessage().contains("object/map"));
        assertTrue(wrongPayload.getCause().getMessage().length() < 512);
    }

    @Property(tries = 300)
    void operationsMatchBigIntegerAcrossSignsZeroAndLargeValues(
            @ForAll("integers") BigInteger left, @ForAll("integers") BigInteger right) throws Exception {
        assertEquals(left.toString(), literalResult("copy", left.toString(), null));
        assertEquals(left.add(right).toString(), literalResult("add", left.toString(), right.toString()));
        assertEquals(left.subtract(right).toString(),
                literalResult("subtract", left.toString(), right.toString()));
        assertEquals(left.multiply(right).toString(),
                literalResult("multiply", left.toString(), right.toString()));
        assertEquals(Boolean.toString(left.equals(right)),
                literalResult("equal", left.toString(), right.toString()).toString());
        assertEquals(Boolean.toString(left.compareTo(right) < 0),
                literalResult("less-than", left.toString(), right.toString()).toString());
        if (right.signum() != 0) {
            BigInteger[] floor = floorPair(left, right);
            assertEquals(floor[0].toString(),
                    literalResult("floor-divide", left.toString(), right.toString()));
            assertEquals(floor[1].toString(), literalResult("modulo", left.toString(), right.toString()));
            assertEquals(left, right.multiply(floor[0]).add(floor[1]));
            assertTrue(floor[1].signum() == 0 || floor[1].signum() == right.signum());
        }
    }

    @Provide
    Arbitrary<BigInteger> integers() {
        BigInteger limit = BigInteger.TEN.pow(180);
        return Arbitraries.bigIntegers().between(limit.negate(), limit);
    }

    private static BigInteger[] floorPair(BigInteger left, BigInteger right) {
        BigInteger[] pair = left.divideAndRemainder(right);
        if (pair[1].signum() != 0 && left.signum() != right.signum()) {
            return new BigInteger[]{pair[0].subtract(BigInteger.ONE), pair[1].add(right)};
        }
        return pair;
    }

    private static void assertPair(String left, String right, String quotient, String remainder) throws Exception {
        assertEquals(quotient, literalResult("floor-divide", left, right));
        assertEquals(remainder, literalResult("modulo", left, right));
    }

    private static Object fieldResult(Object value) throws Exception {
        return ((Map<?, ?>) execute("copy", "field:value", null, "answer", Map.of("value", value)).payload())
                .get("answer");
    }

    private static void assertFieldFailure(Object value, String expected) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> fieldResult(value));
        assertTrue(failure.getCause().getMessage().contains(expected), failure.getCause().getMessage());
    }

    private static Object literalResult(String operation, String left, String right) throws Exception {
        NodeResult result = execute(operation, "literal:" + left,
                right == null ? null : "literal:" + right, "answer", Map.of());
        return ((Map<?, ?>) result.payload()).get("answer");
    }

    private static void assertBoundedFailure(String operation, String left, String right, String target,
                                             Object payload, String expected) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> execute(operation, left, right, target, payload));
        String message = failure.getCause().getMessage();
        if (expected != null) assertTrue(message.contains(expected), message);
        assertTrue(message.length() < 512, "diagnostic must stay bounded: " + message.length());
    }

    private static NodeResult execute(String operation, String left, String right,
                                      String target, Object payload) throws Exception {
        GraphNode node = node(operation, left, right, target);
        return new BigIntOpNodeBehaviorFactory().create(node).handle(new NodeMessage(
                IDENTITY, UUID.randomUUID(), UUID.randomUUID(), node.id(), payload, ATTRIBUTES))
                .toCompletableFuture().get();
    }

    private static GraphNode node(String operation, String left, String right, String target) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", operation);
        properties.put("left", left);
        if (right != null) properties.put("right", right);
        properties.put("target", target);
        return new GraphNode("math", NodeKind.BEHAVIOR, "bigint-op", properties);
    }
}
