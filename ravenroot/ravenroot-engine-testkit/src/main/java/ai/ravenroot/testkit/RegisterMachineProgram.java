package ai.ravenroot.testkit;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Constructive compiler and independent oracle for Register Machine Profile v1 test programs. */
public final class RegisterMachineProgram {
    private RegisterMachineProgram() {
    }

    public sealed interface Instruction permits Set, Increment, DecrementOrJumpZero, Halt { }

    public record Set(String register, String decimal) implements Instruction { }

    public record Increment(String register) implements Instruction { }

    public record DecrementOrJumpZero(String register, int zeroDestination,
                                      int nonzeroDestination) implements Instruction { }

    public record Halt() implements Instruction { }

    public record Result(Map<String, String> registers, int haltInstruction, long steps) {
        public Result { registers = Map.copyOf(registers); }
    }

    public record Compiled(GraphDefinition graph, List<String> instructionEntries,
                           List<String> initializerNodes) {
        public Compiled {
            instructionEntries = List.copyOf(instructionEntries);
            initializerNodes = List.copyOf(initializerNodes);
        }

        /** Ordinary portable GraphML: no compiler metadata or private extension is emitted. */
        public String graphMl() {
            var output = new ByteArrayOutputStream();
            try (var manager = GraphManager.from(graph)) {
                manager.writeGraphMl(output);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    public static Compiled compile(List<? extends Instruction> instructions,
                                   Map<String, String> initialRegisters) {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(initialRegisters, "initialRegisters");
        if (instructions.isEmpty()) throw new IllegalArgumentException("A program must contain instructions");
        long halts = instructions.stream().filter(Halt.class::isInstance).count();
        if (halts != 1) throw new IllegalArgumentException("A program must contain exactly one HALT");
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            validateInstruction(instruction, instructions.size());
            if (!(instruction instanceof Halt) && index + 1 >= instructions.size()) {
                throw new IllegalArgumentException("A sequential instruction cannot be the last instruction");
            }
        }

        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        var entries = new ArrayList<String>();
        var initializers = new ArrayList<String>();
        nodes.add(GraphNode.start("start"));
        for (int index = 0; index < instructions.size(); index++) entries.add("instruction-" + index);

        String previous = "start";
        int initializer = 0;
        for (var entry : new java.util.TreeMap<>(initialRegisters).entrySet()) {
            requireRegister(entry.getKey());
            requireNatural(entry.getValue());
            String id = "initialize-" + initializer++ + "-" + safeId(entry.getKey());
            initializers.add(id);
            nodes.add(bigint(id, "copy", "literal:" + entry.getValue(), null, entry.getKey()));
            edges.add(GraphEdge.to(previous, id));
            previous = id;
        }
        edges.add(GraphEdge.to(previous, entries.get(0)));

        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            String entry = entries.get(index);
            if (instruction instanceof Set set) {
                nodes.add(withIndependentArrivals(bigint(entry, "copy", "literal:" + set.decimal(), null, set.register())));
                edges.add(GraphEdge.to(entry, entries.get(index + 1)));
            } else if (instruction instanceof Increment increment) {
                nodes.add(withIndependentArrivals(bigint(entry, "add", "field:" + increment.register(), "literal:1",
                        increment.register())));
                edges.add(GraphEdge.to(entry, entries.get(index + 1)));
            } else if (instruction instanceof DecrementOrJumpZero decjz) {
                String bool = "__rm_zero_" + index;
                String decision = entry + "-decision";
                String decrement = entry + "-decrement";
                nodes.add(withIndependentArrivals(bigint(entry, "equal", "field:" + decjz.register(), "literal:0", bool)));
                nodes.add(new GraphNode(decision, NodeKind.BEHAVIOR, "cel-decision", Map.of(
                        "expression", "payload." + bool, "trueOutcome", "zero", "falseOutcome", "nonzero")));
                nodes.add(bigint(decrement, "subtract", "field:" + decjz.register(), "literal:1",
                        decjz.register()));
                edges.add(GraphEdge.to(entry, decision));
                edges.add(new GraphEdge(decision, entries.get(decjz.zeroDestination()), "zero"));
                edges.add(new GraphEdge(decision, decrement, "nonzero"));
                edges.add(GraphEdge.to(decrement, entries.get(decjz.nonzeroDestination())));
            } else if (instruction instanceof Halt) {
                nodes.add(new GraphNode(entry, NodeKind.END, null, Map.of("joinPolicy", "each")));
            }
        }
        return new Compiled(new GraphDefinition(nodes, edges, Map.of("join.semantics", "declared")),
                entries, initializers);
    }

    /** Independent instruction-level oracle. The bound makes nontermination an explicit result. */
    public static Result interpret(List<? extends Instruction> instructions,
                                   Map<String, String> initialRegisters, long maxSteps) {
        Objects.requireNonNull(instructions, "instructions");
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps must be positive");
        var registers = new LinkedHashMap<String, BigInteger>();
        new java.util.TreeMap<>(initialRegisters).forEach((name, value) -> {
            requireRegister(name); requireNatural(value); registers.put(name, new BigInteger(value));
        });
        int instruction = 0;
        for (long steps = 1; steps <= maxSteps; steps++) {
            if (instruction < 0 || instruction >= instructions.size()) {
                throw new IllegalArgumentException("Instruction destination is outside the program: " + instruction);
            }
            Instruction current = instructions.get(instruction);
            if (current instanceof Set set) {
                requireNatural(set.decimal()); registers.put(set.register(), new BigInteger(set.decimal())); instruction++;
            } else if (current instanceof Increment increment) {
                registers.compute(increment.register(), (ignored, value) -> requireInitialized(increment.register(), value).add(BigInteger.ONE));
                instruction++;
            } else if (current instanceof DecrementOrJumpZero decjz) {
                BigInteger value = requireInitialized(decjz.register(), registers.get(decjz.register()));
                if (value.signum() == 0) instruction = decjz.zeroDestination();
                else { registers.put(decjz.register(), value.subtract(BigInteger.ONE)); instruction = decjz.nonzeroDestination(); }
            } else if (current instanceof Halt) {
                var result = new LinkedHashMap<String, String>();
                registers.forEach((name, value) -> result.put(name, value.toString()));
                return new Result(result, instruction, steps);
            }
        }
        throw new StepLimitExceededException(maxSteps);
    }

    private static void validateInstruction(Instruction instruction, int size) {
        Objects.requireNonNull(instruction, "instruction");
        if (instruction instanceof Set set) { requireRegister(set.register()); requireNatural(set.decimal()); }
        else if (instruction instanceof Increment increment) requireRegister(increment.register());
        else if (instruction instanceof DecrementOrJumpZero decjz) {
            requireRegister(decjz.register());
            if (decjz.zeroDestination() < 0 || decjz.zeroDestination() >= size
                    || decjz.nonzeroDestination() < 0 || decjz.nonzeroDestination() >= size) {
                throw new IllegalArgumentException("DECJZ destination is outside the program");
            }
        }
    }

    private static GraphNode bigint(String id, String operation, String left, String right, String target) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", operation); properties.put("left", left);
        if (right != null) properties.put("right", right);
        properties.put("target", target);
        return new GraphNode(id, NodeKind.BEHAVIOR, "bigint-op", properties);
    }

    private static GraphNode withIndependentArrivals(GraphNode node) {
        var properties = new LinkedHashMap<>(node.properties());
        properties.put("joinPolicy", "each");
        return new GraphNode(node.id(), node.kind(), node.behavior(), properties);
    }

    private static void requireRegister(String register) {
        if (register == null || !register.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || register.startsWith("__rm_")) {
            throw new IllegalArgumentException("Invalid or reserved register name: " + register);
        }
    }

    private static void requireNatural(String decimal) {
        if (decimal == null || !decimal.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Register values must be canonical non-negative decimals");
        }
    }

    private static BigInteger requireInitialized(String register, BigInteger value) {
        if (value == null) throw new IllegalArgumentException("Register is not initialized: " + register);
        return value;
    }

    private static String safeId(String register) { return register.replace('_', '-'); }

    public static final class StepLimitExceededException extends IllegalStateException {
        private final long limit;

        public StepLimitExceededException(long limit) {
            super("Register machine did not halt within " + limit + " instruction steps");
            this.limit = limit;
        }

        public long limit() { return limit; }
    }
}
