package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.SagaDefinition;
import ai.ravenroot.api.persistence.SagaStepDefinition;
import ai.ravenroot.core.graph.GraphCanonicalForm;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

/** Parses and validates the graph-authored portion of the versioned saga contract. */
final class SagaGraphContract {
    static final String SCOPE = "saga.scope";
    static final String STEP = "saga.step";
    static final String PARTICIPANT = "saga.participant";
    static final String COMPENSATION = "saga.compensation";
    static final String DEPENDS_ON = "saga.dependsOn";
    static final String ROLE = "saga.role";
    static final String IRREVERSIBLE = "saga.irreversible";
    static final String BUSINESS_COMPLETION = "saga.businessCompletionRequired";
    static final String DEADLINE_MS = "saga.deadlineMs";
    static final String ADAPTER = "saga.adapter";
    static final String RECEIPT_STATEMENT = "saga.receiptStatement";
    static final String INBOX_BINDING = "saga.inboxBinding";
    private static final long MAX_DEADLINE_MS = Duration.ofDays(30).toMillis();

    private SagaGraphContract() { }

    static Map<String, SagaDefinition> validate(GraphDefinition graph, BehaviorRegistry behaviors) {
        var scopes = new LinkedHashMap<String, LinkedHashMap<String, SagaStepDefinition>>();
        var compensationNodes = new LinkedHashMap<String, Set<String>>();
        var nodesById = new LinkedHashMap<String, GraphNode>();
        for (GraphNode node : graph.nodes()) {
            nodesById.put(node.id(), node);
            String scope = text(node, SCOPE, false);
            boolean hasSagaProperty = node.properties().keySet().stream().anyMatch(key -> key.startsWith("saga."));
            if (scope == null) {
                if (hasSagaProperty) throw invalid(node.id(), "saga.scope is required when saga properties are present");
                continue;
            }
            if (node.kind() != NodeKind.BEHAVIOR) throw invalid(node.id(), "only behavior nodes may participate");
            String role = text(node, ROLE, false);
            if ("compensation".equals(role)) {
                compensationNodes.computeIfAbsent(scope, ignored -> new LinkedHashSet<>()).add(node.id());
                continue;
            }
            if (role != null && !"forward".equals(role)) throw invalid(node.id(), "saga.role must be forward or compensation");
            String step = text(node, STEP, true);
            String participant = text(node, PARTICIPANT, true);
            requireSupportedParticipant(node, participant, behaviors);
            String compensation = text(node, COMPENSATION, false);
            boolean irreversible = flag(node, IRREVERSIBLE);
            boolean businessCompletion = flag(node, BUSINESS_COMPLETION);
            List<String> dependencies = csv(node.properties().get(DEPENDS_ON));
            SagaStepDefinition definition;
            try {
                definition = new SagaStepDefinition(step, node.id(), participant, compensation,
                        dependencies, irreversible, businessCompletion);
            } catch (IllegalArgumentException malformed) {
                throw invalid(node.id(), malformed.getMessage());
            }
            if (scopes.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).putIfAbsent(step, definition) != null) {
                throw invalid(node.id(), "duplicate logical step '" + step + "' in scope '" + scope + "'");
            }
        }
        var result = new LinkedHashMap<String, SagaDefinition>();
        String graphDigest = GraphCanonicalForm.sha256(graph);
        for (var scope : scopes.entrySet()) {
            deadline(graph, scope.getKey());
            Set<String> compensations = compensationNodes.getOrDefault(scope.getKey(), Set.of());
            for (SagaStepDefinition step : scope.getValue().values()) {
                if (!"pure".equals(step.participantContract())
                        && step.compensationNodeId() == null && !step.irreversible()) {
                    throw invalid(step.nodeId(), "an effectful participant requires saga.compensation "
                            + "or explicit saga.irreversible=true");
                }
                if (step.compensationNodeId() != null && step.irreversible()) {
                    throw invalid(step.nodeId(), "a compensatable step cannot also be saga.irreversible=true");
                }
                if (step.compensationNodeId() != null && !compensations.contains(step.compensationNodeId())) {
                    throw invalid(step.nodeId(), "compensation node '" + step.compensationNodeId()
                            + "' is absent or is not saga.role=compensation in the same scope");
                }
                if (step.compensationNodeId() != null) {
                    requireSupportedParticipant(nodesById.get(step.compensationNodeId()),
                            step.participantContract(), behaviors);
                }
            }
            rejectDependencyCycles(scope.getKey(), scope.getValue());
            String participantDigest = digest(scope.getValue().values().stream()
                    .map(step -> step.stepId() + "\u0000" + step.participantContract() + "\u0000"
                            + String.valueOf(step.compensationNodeId()) + "\u0000" + step.irreversible()
                            + "\u0000" + step.businessCompletionRequired())
                    .sorted().reduce("", (left, right) -> left + "\u0001" + right));
            result.put(scope.getKey(), new SagaDefinition(SagaDefinition.CONTRACT_VERSION,
                    scope.getKey(), graphDigest, participantDigest, scope.getValue()));
        }
        if (!compensationNodes.keySet().stream().allMatch(scopes::containsKey)) {
            throw new IllegalArgumentException("a saga compensation node belongs to a scope with no forward step");
        }
        rejectRepeatVisits(graph);
        return Map.copyOf(result);
    }

    static boolean forward(GraphNode node) {
        return node.properties().containsKey(SCOPE) && !"compensation".equals(node.properties().get(ROLE));
    }

    static boolean compensation(GraphNode node) {
        return node.properties().containsKey(SCOPE) && "compensation".equals(node.properties().get(ROLE));
    }

    static String scope(GraphNode node) { return text(node, SCOPE, true); }

    static String step(GraphNode node) { return text(node, STEP, true); }

    static Duration deadline(GraphDefinition graph, String scope) {
        Long millis = null;
        for (GraphNode node : graph.nodes()) {
            if (!scope.equals(text(node, SCOPE, false))) continue;
            Object raw = node.properties().get(DEADLINE_MS);
            if (raw == null || String.valueOf(raw).isBlank()) continue;
            long parsed;
            try {
                parsed = raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException malformed) {
                throw invalid(node.id(), DEADLINE_MS + " must be an integer");
            }
            if (parsed < 1 || parsed > MAX_DEADLINE_MS) {
                throw invalid(node.id(), DEADLINE_MS + " must be in [1, " + MAX_DEADLINE_MS + "]");
            }
            if (millis != null && millis != parsed) {
                throw invalid(node.id(), DEADLINE_MS + " must be identical throughout scope '" + scope + "'");
            }
            millis = parsed;
        }
        return millis == null ? null : Duration.ofMillis(millis);
    }

    private static void rejectDependencyCycles(String scope, Map<String, SagaStepDefinition> steps) {
        var visiting = new LinkedHashSet<String>();
        var visited = new LinkedHashSet<String>();
        for (String step : steps.keySet()) visit(scope, step, steps, visiting, visited);
    }

    private static void rejectRepeatVisits(GraphDefinition graph) {
        var outgoing = new LinkedHashMap<String, List<String>>();
        graph.nodes().forEach(node -> outgoing.put(node.id(), new ArrayList<>()));
        graph.edges().forEach(edge -> outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>())
                .add(edge.target()));
        for (GraphNode node : graph.nodes()) {
            if (!node.properties().containsKey(SCOPE)) continue;
            var pending = new ArrayDeque<String>(outgoing.getOrDefault(node.id(), List.of()));
            var visited = new LinkedHashSet<String>();
            while (!pending.isEmpty()) {
                String next = pending.removeFirst();
                if (next.equals(node.id())) {
                    throw invalid(node.id(), "repeat visits and loops through a saga operation are unsupported");
                }
                if (visited.add(next)) pending.addAll(outgoing.getOrDefault(next, List.of()));
            }
        }
    }

    private static void requireSupportedParticipant(GraphNode node, String participant,
                                                    BehaviorRegistry behaviors) {
        var descriptor = behaviors.descriptor(node.behavior()).orElse(null);
        String adapter = text(node, ADAPTER, false);
        boolean accepted = switch (participant) {
            case "pure" -> descriptor != null && descriptor.capabilities().contains("saga-pure")
                    && !descriptor.capabilities().contains("side-effect");
            case "jdbc-receipt-v1" -> trusted(descriptor, adapter, "ravenroot.jdbc-receipt.v1")
                    && "jdbc.insert".equals(node.behavior());
            case "amqp-inbox-v1" -> trusted(descriptor, adapter, "ravenroot.amqp-inbox.v1")
                    && "amqp.publish".equals(node.behavior());
            case "http-idempotency-v1" -> trusted(descriptor, adapter, "ravenroot.http-idempotency.v1")
                    && "http-request".equals(node.behavior());
            default -> false;
        };
        if (!accepted) throw invalid(node.id(), "participant contract '" + participant
                + "' is unsupported by behavior '" + node.behavior() + "'");
        if ("http-idempotency-v1".equals(participant)
                && text(node, "saga.outcomeLookupUrl", false) == null) {
            throw invalid(node.id(), "http-idempotency-v1 requires saga.outcomeLookupUrl");
        }
        if ("jdbc-receipt-v1".equals(participant) && text(node, RECEIPT_STATEMENT, false) == null) {
            throw invalid(node.id(), "jdbc-receipt-v1 requires an operator-owned saga.receiptStatement");
        }
        if ("amqp-inbox-v1".equals(participant)) {
            if (text(node, INBOX_BINDING, false) == null) {
                throw invalid(node.id(), "amqp-inbox-v1 requires an operator-governed saga.inboxBinding");
            }
            if (!flag(node, BUSINESS_COMPLETION)) {
                throw invalid(node.id(), "amqp-inbox-v1 requires saga.businessCompletionRequired=true");
            }
            if (!flag(node, "persistent")) {
                throw invalid(node.id(), "amqp-inbox-v1 requires persistent=true");
            }
        }
    }

    private static boolean trusted(ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor,
                                   String configuredAdapter, String requiredAdapter) {
        return descriptor != null && requiredAdapter.equals(configuredAdapter)
                && descriptor.capabilities().contains("saga-adapter:" + requiredAdapter);
    }

    private static void visit(String scope, String step, Map<String, SagaStepDefinition> steps,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(step)) return;
        if (!visiting.add(step)) throw new IllegalArgumentException("saga scope '" + scope + "' has a dependency cycle");
        for (String dependency : steps.get(step).dependencies()) {
            if (!steps.containsKey(dependency)) throw invalid(steps.get(step).nodeId(),
                    "unknown saga dependency '" + dependency + "'");
            visit(scope, dependency, steps, visiting, visited);
        }
        visiting.remove(step);
        visited.add(step);
    }

    private static List<String> csv(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        var answer = new ArrayList<String>();
        for (String token : String.valueOf(value).split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || answer.contains(trimmed)) throw new IllegalArgumentException("invalid saga.dependsOn");
            answer.add(trimmed);
        }
        return List.copyOf(answer);
    }

    private static String text(GraphNode node, String key, boolean required) {
        Object value = node.properties().get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            if (required) throw invalid(node.id(), key + " is required");
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static boolean flag(GraphNode node, String key) {
        Object value = node.properties().get(key);
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw invalid(node.id(), key + " must be true or false");
    }

    private static IllegalArgumentException invalid(String nodeId, String reason) {
        return new IllegalArgumentException("Invalid saga configuration at node '" + nodeId + "': " + reason);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
