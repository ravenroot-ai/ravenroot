package ai.ravenroot.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Static conformance checks for the portable Register Machine Profile v1. */
public final class RegisterMachineProfile {
    public static final String VERSION = "1";
    public static final int MAX_DIAGNOSTICS = 256;
    public static final int MAX_INSPECTED_ELEMENTS = 20_000;

    private static final Set<String> OPERATIONS = Set.of(
            "copy", "add", "subtract", "multiply", "floor-divide", "modulo", "equal", "less-than");
    private static final Set<String> COMPARISONS = Set.of("equal", "less-than");
    private static final Pattern FIELD_EXPRESSION = Pattern.compile("payload\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DECIMAL = Pattern.compile("0|-?[1-9][0-9]*");

    private RegisterMachineProfile() {
    }

    /**
     * Inspects only graph structure and literal properties. It never evaluates CEL, invokes a node,
     * follows a runtime path, or mutates the definition.
     */
    public static Report validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        var findings = new ArrayList<Diagnostic>();
        long elements = (long) graph.nodes().size() + graph.edges().size();
        if (elements > MAX_INSPECTED_ELEMENTS) {
            findings.add(error("PROFILE_LIMIT", "graph", "Profile inspection is limited to "
                    + MAX_INSPECTED_ELEMENTS + " nodes and edges; found " + elements));
            return report(findings, true);
        }

        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().stream().sorted(Comparator.comparing(GraphNode::id))
                .forEach(node -> nodes.put(node.id(), node));
        Map<String, List<GraphEdge>> outgoing = index(graph.edges(), GraphEdge::source);
        Map<String, List<GraphEdge>> incoming = index(graph.edges(), GraphEdge::target);

        for (GraphEdge edge : graph.edges()) {
            if (!nodes.containsKey(edge.source())) {
                findings.add(error("UNRESOLVED_EDGE_SOURCE", edge.id(), "Edge source does not exist: " + edge.source()));
            }
            if (!nodes.containsKey(edge.target())) {
                findings.add(error("UNRESOLVED_EDGE_TARGET", edge.id(), "Edge target does not exist: " + edge.target()));
            }
        }

        var comparisonTargets = new LinkedHashMap<String, String>();
        var consumedBooleans = new HashSet<String>();
        for (GraphNode node : nodes.values()) {
            switch (node.kind()) {
                case START -> requireOutgoing(node, outgoing, 1, findings);
                case END -> requireOutgoing(node, outgoing, 0, findings);
                case PASSTHROUGH, ERROR -> findings.add(error("NODE_OUTSIDE_PROFILE", node.id(),
                        "Register Machine Profile v1 permits START, END, bigint-op, cel-decision, and explicit log nodes"));
                case BEHAVIOR -> {
                    inspectBehavior(node, outgoing.getOrDefault(node.id(), List.of()),
                            comparisonTargets, consumedBooleans, findings);
                    if ("bigint-op".equals(node.behavior()) || "log".equals(node.behavior())) {
                        requireOutgoing(node, outgoing, 1, findings);
                    }
                }
            }
        }

        for (var entry : comparisonTargets.entrySet()) {
            if (!consumedBooleans.contains(entry.getKey())) {
                findings.add(warning("UNCONSUMED_BOOLEAN", entry.getValue(),
                        "Comparison result field '" + entry.getKey() + "' is not consumed by a profile decision"));
            }
        }
        for (String consumed : consumedBooleans) {
            if (!comparisonTargets.containsKey(consumed)) {
                String decision = nodes.values().stream()
                        .filter(node -> "cel-decision".equals(node.behavior()))
                        .filter(node -> consumed.equals(booleanField(node))).map(GraphNode::id)
                        .sorted().findFirst().orElse("graph");
                findings.add(error("UNRESOLVED_DECISION_INPUT", decision,
                        "Decision boolean '" + consumed + "' is not produced by a profile comparison"));
            }
        }

        Set<String> reachable = reachable(graph.start().id(), outgoing);
        for (GraphNode node : nodes.values()) {
            if (!reachable.contains(node.id())) {
                findings.add(warning("UNREACHABLE_NODE", node.id(), "Node is unreachable from START"));
            }
        }

        inspectReadsBeforeInitialization(nodes, incoming, findings);
        inspectCycles(nodes, outgoing, findings);
        inspectDecjz(nodes, incoming, outgoing, findings);
        return report(findings, findings.size() > MAX_DIAGNOSTICS);
    }

    private static void inspectBehavior(GraphNode node, List<GraphEdge> outgoing,
                                        Map<String, String> comparisonTargets, Set<String> consumedBooleans,
                                        List<Diagnostic> findings) {
        if ("log".equals(node.behavior())) return;
        if ("cel-decision".equals(node.behavior())) {
            String expression = text(node, "expression");
            var matcher = FIELD_EXPRESSION.matcher(expression);
            if (!matcher.matches()) {
                findings.add(error("DECISION_EXPRESSION", node.id(),
                        "Profile decisions must read exactly one top-level boolean as payload.<field>"));
            } else {
                consumedBooleans.add(matcher.group(1));
            }
            String onTrue = optional(node, "trueOutcome", "true");
            String onFalse = optional(node, "falseOutcome", "false");
            long trueEdges = outgoing.stream().filter(edge -> onTrue.equals(edge.outcome())).count();
            long falseEdges = outgoing.stream().filter(edge -> onFalse.equals(edge.outcome())).count();
            if (onTrue.equals(onFalse) || trueEdges != 1 || falseEdges != 1 || outgoing.size() != 2) {
                findings.add(error("AMBIGUOUS_DECISION_OUTCOMES", node.id(),
                        "Decision must have distinct true/false outcomes and exactly one edge for each"));
            }
            return;
        }
        if (!"bigint-op".equals(node.behavior())) {
            findings.add(error("NODE_OUTSIDE_PROFILE", node.id(),
                    "Behavior '" + node.behavior() + "' is outside the side-effect-free profile core"));
            return;
        }
        String operation = text(node, "operation");
        String left = text(node, "left");
        String target = text(node, "target");
        if (!OPERATIONS.contains(operation) || !operand(left) || target.isBlank()
                || (!"copy".equals(operation) && !operand(text(node, "right")))) {
            findings.add(error("MALFORMED_BIGINT_OPERATION", node.id(),
                    "bigint-op must declare a supported operation, field/literal operands, and a target"));
            return;
        }
        if ("copy".equals(operation) && node.properties().containsKey("right")) {
            findings.add(error("MALFORMED_BIGINT_OPERATION", node.id(), "copy must not declare a right operand"));
        }
        warnNonCanonicalLiteral(node, "left", left, findings);
        warnNonCanonicalLiteral(node, "right", text(node, "right"), findings);
        if (COMPARISONS.contains(operation)) comparisonTargets.put(target, node.id());
        if (!"copy".equals(operation) && !COMPARISONS.contains(operation) && left.startsWith("field:")
                && !target.equals(left.substring("field:".length()))) {
            findings.add(warning("REGISTER_OVERWRITE", node.id(), "Operation reads register '"
                    + left.substring("field:".length()) + "' but writes another register '" + target + "'"));
        }
    }

    private static void requireOutgoing(GraphNode node, Map<String, List<GraphEdge>> outgoing,
                                        int expected, List<Diagnostic> findings) {
        int actual = outgoing.getOrDefault(node.id(), List.of()).size();
        if (actual != expected) findings.add(error("CONTROL_PATH_ARITY", node.id(),
                "Profile node requires " + expected + " outgoing edge(s); found " + actual));
    }

    private static void inspectReadsBeforeInitialization(Map<String, GraphNode> nodes,
                                                          Map<String, List<GraphEdge>> incoming,
                                                          List<Diagnostic> findings) {
        Map<String, Set<String>> definite = new HashMap<>();
        nodes.keySet().forEach(id -> definite.put(id, new LinkedHashSet<>()));
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean changed = false;
            for (GraphNode node : nodes.values()) {
                List<GraphEdge> predecessors = incoming.getOrDefault(node.id(), List.of());
                Set<String> next = new LinkedHashSet<>();
                if (!predecessors.isEmpty()) {
                    next.addAll(definite.getOrDefault(predecessors.get(0).source(), Set.of()));
                    for (int index = 1; index < predecessors.size(); index++) {
                        next.retainAll(definite.getOrDefault(predecessors.get(index).source(), Set.of()));
                    }
                }
                if (isBigInt(node)) next.add(text(node, "target"));
                if (!next.equals(definite.get(node.id()))) {
                    definite.put(node.id(), next);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        for (GraphNode node : nodes.values()) {
            if (!isBigInt(node)) continue;
            Set<String> before = predecessorIntersection(node.id(), incoming, definite);
            for (String property : List.of("left", "right")) {
                String reference = text(node, property);
                if (reference.startsWith("field:")) {
                    String field = reference.substring("field:".length());
                    if (!before.contains(field)) findings.add(warning("READ_BEFORE_INITIALIZATION", node.id(),
                            "Register '" + field + "' may be read before initialization"));
                }
            }
        }
    }

    private static Set<String> predecessorIntersection(String id, Map<String, List<GraphEdge>> incoming,
                                                       Map<String, Set<String>> definite) {
        List<GraphEdge> predecessors = incoming.getOrDefault(id, List.of());
        if (predecessors.isEmpty()) return Set.of();
        var result = new LinkedHashSet<>(definite.getOrDefault(predecessors.get(0).source(), Set.of()));
        for (int index = 1; index < predecessors.size(); index++) {
            result.retainAll(definite.getOrDefault(predecessors.get(index).source(), Set.of()));
        }
        return result;
    }

    private static void inspectDecjz(Map<String, GraphNode> nodes, Map<String, List<GraphEdge>> incoming,
                                     Map<String, List<GraphEdge>> outgoing, List<Diagnostic> findings) {
        for (GraphNode decrement : nodes.values()) {
            if (!isBigInt(decrement) || !"subtract".equals(text(decrement, "operation"))
                    || !"literal:1".equals(text(decrement, "right"))) continue;
            String left = text(decrement, "left");
            String register = left.startsWith("field:") ? left.substring(6) : "";
            boolean guarded = incoming.getOrDefault(decrement.id(), List.of()).stream().anyMatch(edge -> {
                GraphNode decision = nodes.get(edge.source());
                if (decision == null || !"cel-decision".equals(decision.behavior())) return false;
                String falseOutcome = optional(decision, "falseOutcome", "false");
                if (!falseOutcome.equals(edge.outcome())) return false;
                String bool = booleanField(decision);
                return incoming.getOrDefault(decision.id(), List.of()).stream().anyMatch(in -> {
                    GraphNode test = nodes.get(in.source());
                    return isBigInt(test) && "equal".equals(text(test, "operation"))
                            && ("field:" + register).equals(text(test, "left"))
                            && "literal:0".equals(text(test, "right"))
                            && bool.equals(text(test, "target"))
                            && outgoing.getOrDefault(test.id(), List.of()).size() == 1;
                });
            });
            if (!guarded) findings.add(warning("UNGUARDED_DECREMENT", decrement.id(),
                    "Subtract-one is not proven to be on the nonzero branch of a zero test"));
        }
    }

    private static void inspectCycles(Map<String, GraphNode> nodes, Map<String, List<GraphEdge>> outgoing,
                                      List<Diagnostic> findings) {
        // One warning per cyclic component. Logging is the profile's explicit observable boundary;
        // runtime cancellation still remains between every two nodes, including unlogged cycles.
        var index = new HashMap<String, Integer>();
        var low = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var onStack = new HashSet<String>();
        int[] next = {0};
        for (String id : nodes.keySet()) if (!index.containsKey(id)) {
            strongConnect(id, nodes, outgoing, index, low, stack, onStack, next, findings);
        }
    }

    private static void strongConnect(String id, Map<String, GraphNode> nodes,
                                      Map<String, List<GraphEdge>> outgoing, Map<String, Integer> index,
                                      Map<String, Integer> low, ArrayDeque<String> stack, Set<String> onStack,
                                      int[] next, List<Diagnostic> findings) {
        index.put(id, next[0]); low.put(id, next[0]++); stack.push(id); onStack.add(id);
        for (GraphEdge edge : outgoing.getOrDefault(id, List.of())) {
            String target = edge.target();
            if (!nodes.containsKey(target)) continue;
            if (!index.containsKey(target)) {
                strongConnect(target, nodes, outgoing, index, low, stack, onStack, next, findings);
                low.put(id, Math.min(low.get(id), low.get(target)));
            } else if (onStack.contains(target)) low.put(id, Math.min(low.get(id), index.get(target)));
        }
        if (!low.get(id).equals(index.get(id))) return;
        var component = new ArrayList<String>();
        String member;
        do { member = stack.pop(); onStack.remove(member); component.add(member); } while (!member.equals(id));
        boolean selfLoop = outgoing.getOrDefault(id, List.of()).stream().anyMatch(edge -> edge.target().equals(id));
        if ((component.size() > 1 || selfLoop) && component.stream().noneMatch(memberId ->
                "log".equals(nodes.get(memberId).behavior()))) {
            component.sort(String::compareTo);
            findings.add(warning("UNOBSERVABLE_CYCLE", component.get(0),
                    "Cycle has no explicit log observation point; cancellation remains available between nodes"));
        }
    }

    private static String booleanField(GraphNode decision) {
        var matcher = FIELD_EXPRESSION.matcher(text(decision, "expression"));
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static boolean isBigInt(GraphNode node) {
        return node.kind() == NodeKind.BEHAVIOR && "bigint-op".equals(node.behavior());
    }

    private static boolean operand(String value) {
        return value.startsWith("field:") && value.length() > 6
                || value.startsWith("literal:") && value.length() > 8;
    }

    private static void warnNonCanonicalLiteral(GraphNode node, String property, String reference,
                                                List<Diagnostic> findings) {
        if (!reference.startsWith("literal:")) return;
        String decimal = reference.substring("literal:".length());
        if (!DECIMAL.matcher(decimal).matches()) findings.add(warning("NONCANONICAL_DECIMAL", node.id(),
                "Property '" + property + "' is not a canonical decimal string"));
    }

    private static String text(GraphNode node, String property) {
        Object value = node.properties().get(property);
        return value == null ? "" : value.toString();
    }

    private static String optional(GraphNode node, String property, String fallback) {
        String value = text(node, property);
        return value.isBlank() ? fallback : value;
    }

    private static Set<String> reachable(String start, Map<String, List<GraphEdge>> outgoing) {
        var reached = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String id = pending.remove();
            if (!reached.add(id)) continue;
            outgoing.getOrDefault(id, List.of()).stream().map(GraphEdge::target).sorted().forEach(pending::add);
        }
        return reached;
    }

    private static Map<String, List<GraphEdge>> index(List<GraphEdge> edges,
                                                       java.util.function.Function<GraphEdge, String> key) {
        var result = new HashMap<String, List<GraphEdge>>();
        edges.stream().sorted(Comparator.comparing(GraphEdge::id)).forEach(edge ->
                result.computeIfAbsent(key.apply(edge), ignored -> new ArrayList<>()).add(edge));
        return result;
    }

    private static Report report(List<Diagnostic> findings, boolean preTruncated) {
        findings.sort(Comparator.comparing(Diagnostic::severity).thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::location).thenComparing(Diagnostic::message));
        boolean truncated = preTruncated || findings.size() > MAX_DIAGNOSTICS;
        List<Diagnostic> kept = List.copyOf(findings.subList(0, Math.min(findings.size(), MAX_DIAGNOSTICS)));
        return new Report(kept.stream().filter(item -> item.severity() == Severity.ERROR).toList(),
                kept.stream().filter(item -> item.severity() == Severity.WARNING).toList(), truncated);
    }

    private static Diagnostic error(String code, String location, String message) {
        return new Diagnostic(Severity.ERROR, code, safe(location), message);
    }

    private static Diagnostic warning(String code, String location, String message) {
        return new Diagnostic(Severity.WARNING, code, safe(location), message);
    }

    private static String safe(String location) {
        return location == null || location.isBlank() ? "graph" : location;
    }

    public enum Severity { ERROR, WARNING }

    public record Diagnostic(Severity severity, String code, String location, String message) {
        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(message, "message");
        }
    }

    public record Report(List<Diagnostic> errors, List<Diagnostic> warnings, boolean truncated) {
        public Report {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean conforms() { return errors.isEmpty(); }
    }
}
