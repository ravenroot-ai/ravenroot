package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Exact, bounded elementary integer arithmetic over top-level payload fields. */
final class BigIntOpNodeBehaviorFactory implements NodeBehaviorFactory {
    static final int MAX_DECIMAL_DIGITS = 4_096;
    private static final int MAX_OPERAND_REFERENCE_UTF8_BYTES = "literal:".length() + 1 + MAX_DECIMAL_DIGITS;
    private static final int MAX_OPERATION_UTF8_BYTES = 32;
    private static final BigInteger MAX_MAGNITUDE_EXCLUSIVE = BigInteger.TEN.pow(MAX_DECIMAL_DIGITS);
    private static final Pattern SIGNED_DECIMAL = Pattern.compile("[+-]?[0-9]+");

    private static final List<String> OPERATION_NAMES = List.of(
            "copy", "add", "subtract", "multiply", "floor-divide", "modulo", "equal", "less-than");
    private static final PropertyCondition BINARY_OPERATION = PropertyCondition.oneOf("operation",
            "add", "subtract", "multiply", "floor-divide", "modulo", "equal", "less-than");

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("bigint-op", "Exact integer operation", "Transformations",
                "Applies one bounded, exact integer operation to top-level payload fields or decimal literals.",
                "flow", false, List.of(
                new NodePropertyDescriptor("operation", "Operation", NodePropertyType.STRING, true,
                        "One elementary integer operation.", "", OPERATION_NAMES, false,
                        null, null, "", "", MAX_OPERATION_UTF8_BYTES, 0, 0),
                NodePropertyDescriptor.boundedText("left", "Left operand", NodePropertyType.STRING, true,
                        "Operand reference: field:<name> or literal:<signed-decimal>.", "",
                        MAX_OPERAND_REFERENCE_UTF8_BYTES, 0, 0),
                new NodePropertyDescriptor("right", "Right operand", NodePropertyType.STRING, false,
                        "Second operand reference, required for every operation except copy.", "", List.of(),
                        false, BINARY_OPERATION, BINARY_OPERATION, "", "",
                        MAX_OPERAND_REFERENCE_UTF8_BYTES, 0, 0),
                NodePropertyDescriptor.boundedText("target", "Target field", NodePropertyType.STRING, true,
                        "Top-level payload field that receives the result.", "",
                        PayloadLimits.DEFAULTS.maxKeyLength(), 0, 0)),
                Set.of("deterministic", "pure", "in-process"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The copied payload contains the exact result in target; all attributes pass through."));
    }

    @Override
    public void validate(GraphNode node) {
        Operation operation = operation(node);
        operand(node, "left", requiredValue(node, "left"), false);
        boolean rightDeclared = node.properties().containsKey("right");
        if (operation == Operation.COPY && rightDeclared) {
            throw invalid(node, "right", "is forbidden when operation is copy");
        }
        if (operation != Operation.COPY) {
            operand(node, "right", requiredValue(node, "right"), false);
        }
        target(node);
    }

    @Override
    public NodeHandler create(GraphNode node) {
        validate(node);
        Operation operation = operation(node);
        Operand left = operand(node, "left", requiredValue(node, "left"), true);
        Operand right = operation == Operation.COPY ? null
                : operand(node, "right", requiredValue(node, "right"), true);
        String target = target(node);
        return message -> {
            try {
                if (!(message.payload() instanceof Map<?, ?> input)) {
                    throw invalid(node, "payload", "must be an object/map");
                }
                var output = copyPayload(node, input);
                BigInteger leftValue = left.resolve(input, node, "left");
                BigInteger rightValue = right == null ? null : right.resolve(input, node, "right");
                Object result = evaluate(node, operation, leftValue, rightValue);
                output.put(target, result);
                return CompletableFuture.completedFuture(new NodeResult("continue",
                        Collections.unmodifiableMap(output), message.attributes()));
            } catch (IllegalArgumentException rejected) {
                return CompletableFuture.failedFuture(rejected);
            }
        };
    }

    private static LinkedHashMap<String, Object> copyPayload(GraphNode node, Map<?, ?> input) {
        var output = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(node, "payload", "must contain only string field names");
            }
            output.put(key, entry.getValue());
        }
        return output;
    }

    private static Object evaluate(GraphNode node, Operation operation, BigInteger left, BigInteger right) {
        return switch (operation) {
            case COPY -> arithmeticResult(node, left);
            case ADD -> arithmeticResult(node, left.add(right));
            case SUBTRACT -> arithmeticResult(node, left.subtract(right));
            case MULTIPLY -> arithmeticResult(node, left.multiply(right));
            case FLOOR_DIVIDE -> arithmeticResult(node, floorPair(node, left, right)[0]);
            case MODULO -> arithmeticResult(node, floorPair(node, left, right)[1]);
            case EQUAL -> left.equals(right);
            case LESS_THAN -> left.compareTo(right) < 0;
        };
    }

    private static BigInteger[] floorPair(GraphNode node, BigInteger left, BigInteger right) {
        if (right.signum() == 0) {
            throw invalid(node, "right", "resolved to zero for division");
        }
        BigInteger[] pair = left.divideAndRemainder(right);
        if (pair[1].signum() != 0 && left.signum() != right.signum()) {
            pair[0] = pair[0].subtract(BigInteger.ONE);
            pair[1] = pair[1].add(right);
        }
        return pair;
    }

    private static String arithmeticResult(GraphNode node, BigInteger value) {
        requireMagnitude(node, "result", value);
        return value.toString();
    }

    private static Operation operation(GraphNode node) {
        String value = requiredValue(node, "operation");
        try {
            return Operation.from(value);
        } catch (IllegalArgumentException unknown) {
            throw invalid(node, "operation", "is unsupported; choose one of the catalogued operations");
        }
    }

    private static Operand operand(GraphNode node, String property, String reference, boolean parseLiteral) {
        if (reference.startsWith("field:")) {
            String field = reference.substring("field:".length());
            requireFieldName(node, property, field);
            return new FieldOperand(field);
        }
        if (reference.startsWith("literal:")) {
            String decimal = reference.substring("literal:".length());
            requireDecimalText(node, property, decimal);
            return new LiteralOperand(parseLiteral ? new BigInteger(decimal) : BigInteger.ZERO);
        }
        throw invalid(node, property, "must use field:<name> or literal:<signed-decimal>");
    }

    private static String target(GraphNode node) {
        String target = requiredValue(node, "target");
        requireFieldName(node, "target", target);
        if (SecurityContext.isReservedKey(target)) {
            throw invalid(node, "target", "cannot use Ravenroot's reserved security namespace");
        }
        return target;
    }

    private static String requiredValue(GraphNode node, String property) {
        Object raw = node.properties().get(property);
        if (raw == null || raw.toString().isBlank()) {
            throw invalid(node, property, "is required");
        }
        return raw.toString();
    }

    private static void requireFieldName(GraphNode node, String property, String field) {
        if (field.isBlank() || field.length() > PayloadLimits.DEFAULTS.maxKeyLength()
                || field.chars().anyMatch(Character::isISOControl)) {
            throw invalid(node, property, "must name a non-blank top-level payload field of at most "
                    + PayloadLimits.DEFAULTS.maxKeyLength() + " characters without control characters");
        }
    }

    private static void requireDecimalText(GraphNode node, String property, String decimal) {
        int sign = decimal.startsWith("+") || decimal.startsWith("-") ? 1 : 0;
        int digits = decimal.length() - sign;
        if (digits < 1 || digits > MAX_DECIMAL_DIGITS) {
            throw invalid(node, property, "decimal operand must contain 1 to " + MAX_DECIMAL_DIGITS + " digits");
        }
        if (!SIGNED_DECIMAL.matcher(decimal).matches()) {
            throw invalid(node, property, "literal is not a signed base-10 integer");
        }
    }

    private static BigInteger runtimeInteger(GraphNode node, String property, String field, Object value) {
        BigInteger integer;
        if (value instanceof BigInteger exact) {
            integer = exact;
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigDecimal decimal && decimal.scale() <= 0) {
            long digits = (long) decimal.precision() - decimal.scale();
            if (decimal.signum() != 0 && digits > MAX_DECIMAL_DIGITS) {
                throw fieldFailure(node, property, field, "exceeds the " + MAX_DECIMAL_DIGITS + "-digit ceiling");
            }
            integer = decimal.toBigIntegerExact();
        } else if (value instanceof String text) {
            requireRuntimeDecimal(node, property, field, text);
            integer = new BigInteger(text);
        } else {
            throw fieldFailure(node, property, field,
                    "must contain a decimal string or exact integral runtime number");
        }
        requireMagnitude(node, property + " field '" + field + "'", integer);
        return integer;
    }

    private static void requireRuntimeDecimal(GraphNode node, String property, String field, String decimal) {
        int sign = decimal.startsWith("+") || decimal.startsWith("-") ? 1 : 0;
        int digits = decimal.length() - sign;
        if (digits < 1 || digits > MAX_DECIMAL_DIGITS) {
            throw fieldFailure(node, property, field,
                    "must contain 1 to " + MAX_DECIMAL_DIGITS + " decimal digits");
        }
        if (!SIGNED_DECIMAL.matcher(decimal).matches()) {
            throw fieldFailure(node, property, field, "does not contain a signed base-10 integer");
        }
    }

    private static void requireMagnitude(GraphNode node, String subject, BigInteger value) {
        if (value.abs().compareTo(MAX_MAGNITUDE_EXCLUSIVE) >= 0) {
            throw invalid(node, subject, "exceeds the " + MAX_DECIMAL_DIGITS + "-digit ceiling");
        }
    }

    private static IllegalArgumentException fieldFailure(
            GraphNode node, String property, String field, String detail) {
        return invalid(node, property, "field '" + field + "' " + detail);
    }

    private static IllegalArgumentException invalid(GraphNode node, String property, String detail) {
        return new IllegalArgumentException("Node " + node.id() + " bigint-op property '" + property + "' " + detail);
    }

    private sealed interface Operand permits FieldOperand, LiteralOperand {
        BigInteger resolve(Map<?, ?> payload, GraphNode node, String property);
    }

    private record FieldOperand(String field) implements Operand {
        @Override
        public BigInteger resolve(Map<?, ?> payload, GraphNode node, String property) {
            if (!payload.containsKey(field)) {
                throw fieldFailure(node, property, field, "is missing");
            }
            return runtimeInteger(node, property, field, payload.get(field));
        }
    }

    private record LiteralOperand(BigInteger value) implements Operand {
        @Override
        public BigInteger resolve(Map<?, ?> payload, GraphNode node, String property) {
            return value;
        }
    }

    private enum Operation {
        COPY("copy"), ADD("add"), SUBTRACT("subtract"), MULTIPLY("multiply"),
        FLOOR_DIVIDE("floor-divide"), MODULO("modulo"), EQUAL("equal"), LESS_THAN("less-than");

        private final String externalName;

        Operation(String externalName) {
            this.externalName = externalName;
        }

        static Operation from(String externalName) {
            for (Operation operation : values()) {
                if (operation.externalName.equals(externalName)) return operation;
            }
            throw new IllegalArgumentException("unsupported operation");
        }
    }
}
