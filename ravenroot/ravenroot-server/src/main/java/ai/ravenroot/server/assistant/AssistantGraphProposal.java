package ai.ravenroot.server.assistant;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.audit.JsonStrings;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An inert, provider-neutral graph-change proposal. The model supplies operations only; the service
 * binds them to the editor incarnation/revision/catalog snapshot captured on the incoming turn.
 */
public record AssistantGraphProposal(String id, String documentIncarnation, long documentRevision,
                                     String catalogDigest, String summary, String operationsJson,
                                     String model) {

    public static final String TOOL_NAME = "ravenroot_propose_graph_change";
    public static final int MAX_OPERATIONS = 64;
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_IDENTIFIER = 256;
    private static final PayloadLimits LIMITS =
            new PayloadLimits(128 * 1024, 12, 1024, 16_000, 32_000, 128);
    /* Closed at every level. Properties are arrays so duplicate semantic names can be refused. */
    private static final String INPUT_SCHEMA = inputSchema();

    /** One executable field contract drives both provider schema and server shape validation. */
    private enum OperationKind {
        CREATE_NODE("create-node", List.of("op", "ref", "id", "behavior", "name", "position", "properties"),
                List.of("op", "ref", "id", "behavior"), 4),
        UPDATE_NODE("update-node", List.of("op", "target", "name", "position", "properties",
                "removeProperties"), List.of("op", "target"), 3),
        DELETE_NODE("delete-node", List.of("op", "target"), List.of("op", "target"), 2),
        CREATE_EDGE("create-edge", List.of("op", "ref", "id", "source", "destination", "outcome"),
                List.of("op", "ref", "id", "source", "destination"), 5),
        UPDATE_EDGE("update-edge", List.of("op", "target", "source", "destination", "outcome"),
                List.of("op", "target"), 3),
        DELETE_EDGE("delete-edge", List.of("op", "target"), List.of("op", "target"), 2);

        private final String wireName;
        private final List<String> allowed;
        private final List<String> required;
        private final int minimumProperties;

        OperationKind(String wireName, List<String> allowed, List<String> required,
                      int minimumProperties) {
            this.wireName = wireName;
            this.allowed = List.copyOf(allowed);
            this.required = List.copyOf(required);
            this.minimumProperties = minimumProperties;
        }

        static OperationKind from(String wireName) {
            return java.util.Arrays.stream(values())
                    .filter(candidate -> candidate.wireName.equals(wireName))
                    .findFirst()
                    .orElseThrow(() -> new ProposalValidationException(null,
                            "UNKNOWN_OPERATION", List.of()));
        }

        void validateShape(Map<String, PayloadValue> fields) {
            List<String> missing = required.stream().filter(field -> !fields.containsKey(field)).toList();
            if (!missing.isEmpty()) {
                throw new ProposalValidationException(wireName, "MISSING_REQUIRED_FIELDS", missing);
            }
            List<String> unknown = fields.keySet().stream().filter(field -> !allowed.contains(field))
                    .sorted().toList();
            if (!unknown.isEmpty()) {
                throw new ProposalValidationException(wireName, "UNKNOWN_FIELDS", List.of());
            }
        }

        OperationContract contract() {
            return new OperationContract(Set.copyOf(allowed), Set.copyOf(required));
        }

        String schemaJson() {
            String properties = allowed.stream()
                    .map(field -> "\"" + field + "\":" + fieldSchema(field, wireName))
                    .collect(Collectors.joining(","));
            return "{\"type\":\"object\",\"additionalProperties\":false,\"minProperties\":"
                    + minimumProperties + ",\"required\":["
                    + required.stream().map(field -> "\"" + field + "\"")
                            .collect(Collectors.joining(","))
                    + "],\"properties\":{" + properties + "}}";
        }
    }

    record OperationContract(Set<String> allowed, Set<String> required) { }

    static Map<String, OperationContract> operationContracts() {
        var contracts = new LinkedHashMap<String, OperationContract>();
        for (OperationKind kind : OperationKind.values()) contracts.put(kind.wireName, kind.contract());
        return Map.copyOf(contracts);
    }

    private static String inputSchema() {
        String variants = java.util.Arrays.stream(OperationKind.values())
                .map(OperationKind::schemaJson).collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[\"summary\",\"operations\"],\"properties\":{"
                + "\"summary\":{\"type\":\"string\",\"maxLength\":500},"
                + "\"operations\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":64,"
                + "\"items\":{\"oneOf\":[" + variants + "]}}},"
                + "\"$defs\":{\"selector\":{\"type\":\"object\",\"oneOf\":["
                + "{\"additionalProperties\":false,\"required\":[\"existing\"],"
                + "\"properties\":{\"existing\":{\"type\":\"string\"}}},"
                + "{\"additionalProperties\":false,\"required\":[\"created\"],"
                + "\"properties\":{\"created\":{\"type\":\"string\"}}}]}}}";
    }

    private static String fieldSchema(String field, String operation) {
        return switch (field) {
            case "op" -> "{\"const\":\"" + operation + "\"}";
            case "ref", "id", "behavior", "name", "outcome" -> "{\"type\":\"string\"}";
            case "position" -> "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"x\",\"y\"],\"properties\":{"
                    + "\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}}}";
            case "properties" -> "{\"type\":\"array\",\"items\":{\"type\":\"object\","
                    + "\"additionalProperties\":false,\"required\":[\"name\",\"value\"],"
                    + "\"properties\":{\"name\":{\"type\":\"string\"},"
                    + "\"value\":{\"type\":\"string\"}}}}";
            case "removeProperties" ->
                    "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";
            case "target", "source", "destination" -> "{\"$ref\":\"#/$defs/selector\"}";
            default -> throw new IllegalStateException("unknown proposal schema field");
        };
    }

    private static final class ProposalValidationException extends IllegalArgumentException {
        private final String operation;
        private final String problem;
        private final List<String> fields;

        private ProposalValidationException(String operation, String problem, List<String> fields) {
            super("invalid assistant graph proposal");
            this.operation = operation;
            this.problem = problem;
            this.fields = List.copyOf(fields);
        }

        String feedbackJson() {
            String operationMember = operation == null ? "null" : "\"" + operation + "\"";
            String fieldMembers = fields.stream().map(field -> "\"" + field + "\"")
                    .collect(Collectors.joining(","));
            return "{\"code\":\"INVALID_GRAPH_PROPOSAL\",\"operation\":" + operationMember
                    + ",\"problem\":\"" + problem + "\",\"fields\":[" + fieldMembers + "],"
                    + "\"instruction\":\"Correct the proposal and call the proposal tool alone. "
                    + "The proposal remains unapplied until the author confirms it.\"}";
        }
    }

    static String safeValidationFeedback(IllegalArgumentException invalid) {
        if (invalid instanceof ProposalValidationException classified) return classified.feedbackJson();
        return new ProposalValidationException(null, "SEMANTIC_VALIDATION_FAILED", List.of())
                .feedbackJson();
    }

    public AssistantGraphProposal {
        if (id == null || id.isBlank() || documentIncarnation == null
                || documentIncarnation.isBlank() || documentRevision < 0
                || catalogDigest == null || catalogDigest.isBlank()
                || summary == null || summary.isBlank()
                || operationsJson == null || operationsJson.isBlank()) {
            throw new IllegalArgumentException("an assistant graph proposal is incomplete");
        }
    }

    public static AssistantProvider.ToolSpec toolSpec() {
        return new AssistantProvider.ToolSpec(TOOL_NAME,
                "Propose a bounded edit to the currently open Ravenroot graph. This creates only a "
                        + "preview awaiting explicit human confirmation. Use tagged existing/created "
                        + "selectors, proposal-local refs, explicit final graph ids, and never include "
                        + "credentials, secret references or secret-class values.", INPUT_SCHEMA);
    }

    public static boolean canOffer(AssistantTurn turn) {
        if (turn == null || !turn.hasDocumentBinding()
                || !turn.attached().containsAll(Set.of("graph", "catalog"))) return false;
        try {
            Snapshot.read(turn.contextJson());
            return true;
        } catch (IllegalArgumentException invalidSnapshot) {
            return false;
        }
    }

    public static AssistantGraphProposal read(String inputJson, AssistantTurn turn, String model) {
        if (!turn.hasDocumentBinding()
                || !turn.attached().containsAll(Set.of("graph", "catalog"))) {
            throw new IllegalArgumentException("a graph proposal needs an open document binding");
        }
        Snapshot snapshot = Snapshot.read(turn.contextJson());
        PayloadValue parsed = PayloadJson.read(String.valueOf(inputJson)
                .getBytes(StandardCharsets.UTF_8), LIMITS);
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw new IllegalArgumentException("a graph proposal must be a JSON object");
        }
        requireExact(root.entries(), Set.of("summary", "operations"), "proposal");
        String summary = text(root.entries().get("summary"), "proposal summary");
        if (summary.isBlank() || summary.length() > MAX_SUMMARY) {
            throw new IllegalArgumentException("the proposal summary is invalid");
        }
        if (!(root.entries().get("operations") instanceof PayloadValue.ListValue operations)
                || operations.values().isEmpty() || operations.values().size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("a proposal needs between 1 and " + MAX_OPERATIONS
                    + " operations");
        }
        var refs = new HashMap<String, Element>();
        var finalIds = new HashSet<String>(snapshot.allIds());
        var mutated = new HashSet<String>();
        var removed = new HashSet<String>();
        var liveEdges = new HashMap<String, EdgeEndpoints>(snapshot.edgeEndpoints());
        for (PayloadValue operation : operations.values()) {
            validateOperation(operation, refs, finalIds, mutated, removed, liveEdges, snapshot);
        }
        return new AssistantGraphProposal("assistant-" + UUID.randomUUID(),
                turn.documentIncarnation(), turn.documentRevision(), turn.catalogDigest(), summary,
                PayloadJson.write(operations), model);
    }

    /** Existing response members remain required; proposal is additive and optional. */
    public String toJson() {
        return "{\"text\":\"" + JsonStrings.escape(summary) + "\",\"model\":"
                + (model == null ? "null" : "\"" + JsonStrings.escape(model) + "\"")
                + ",\"truncated\":false,\"proposal\":{\"version\":1,\"id\":\""
                + JsonStrings.escape(id) + "\",\"document\":{\"incarnation\":\""
                + JsonStrings.escape(documentIncarnation) + "\",\"revision\":" + documentRevision
                + ",\"catalogDigest\":\"" + JsonStrings.escape(catalogDigest)
                + "\"},\"summary\":\"" + JsonStrings.escape(summary)
                + "\",\"operations\":" + operationsJson
                + "}}";
    }

    private static void validateOperation(PayloadValue value, Map<String, Element> refs,
                                          Set<String> finalIds, Set<String> mutated,
                                          Set<String> removed,
                                          Map<String, EdgeEndpoints> liveEdges,
                                          Snapshot snapshot) {
        if (!(value instanceof PayloadValue.MapValue operation)) {
            throw new IllegalArgumentException("every proposal operation must be an object");
        }
        Map<String, PayloadValue> fields = operation.entries();
        String type = text(fields.get("op"), "operation type");
        OperationKind kind = OperationKind.from(type);
        kind.validateShape(fields);
        switch (kind) {
            case CREATE_NODE -> {
                String ref = identifier(fields.get("ref"), "node ref");
                if (refs.containsKey(ref)) throw new IllegalArgumentException("proposal ref is duplicated");
                unique(identifier(fields.get("id"), "node id"), finalIds, "final graph id");
                String behavior = identifier(fields.get("behavior"), "catalog behavior");
                snapshot.requireBehavior(behavior);
                optionalText(fields.get("name"), "node name");
                optionalPosition(fields.get("position"));
                optionalProperties(fields.get("properties"), snapshot.properties(behavior));
                refs.put(ref, new Element("node", behavior, "created:" + ref));
            }
            case UPDATE_NODE -> {
                Element target = selector(fields.get("target"), "node", refs, removed, snapshot);
                claim(mutated, target);
                optionalText(fields.get("name"), "node name");
                optionalPosition(fields.get("position"));
                optionalProperties(fields.get("properties"), snapshot.properties(target.behavior()));
                optionalPropertyNames(fields.get("removeProperties"), snapshot.properties(target.behavior()));
                rejectSetAndRemoveOverlap(fields.get("properties"), fields.get("removeProperties"));
                if (fields.size() == 2) throw new IllegalArgumentException("an update-node needs a change");
            }
            case DELETE_NODE -> {
                Element target = selector(fields.get("target"), "node", refs, removed, snapshot);
                if (target.key().startsWith("created:")) {
                    throw new IllegalArgumentException("a proposal cannot delete an element it just created");
                }
                claim(mutated, target);
                List<String> incident = liveEdges.entrySet().stream()
                        .filter(entry -> entry.getValue().incidentTo(target.key()))
                        .map(Map.Entry::getKey)
                        .toList();
                if (incident.stream().anyMatch(mutated::contains)) {
                    throw new IllegalArgumentException(
                            "deleting a node would also delete an edge changed by this proposal");
                }
                incident.forEach(edgeKey -> {
                    liveEdges.remove(edgeKey);
                    removed.add(edgeKey);
                });
                removed.add(target.key());
            }
            case CREATE_EDGE -> {
                String ref = identifier(fields.get("ref"), "edge ref");
                if (refs.containsKey(ref)) throw new IllegalArgumentException("proposal ref is duplicated");
                unique(identifier(fields.get("id"), "edge id"), finalIds, "final graph id");
                Element source = selector(fields.get("source"), "node", refs, removed, snapshot);
                Element destination = selector(fields.get("destination"), "node", refs, removed, snapshot);
                optionalText(fields.get("outcome"), "edge outcome");
                Element created = new Element("edge", null, "created:" + ref);
                refs.put(ref, created);
                liveEdges.put(created.key(), new EdgeEndpoints(source.key(), destination.key()));
                mutated.add(created.key());
            }
            case UPDATE_EDGE -> {
                Element target = selector(fields.get("target"), "edge", refs, removed, snapshot);
                claim(mutated, target);
                EdgeEndpoints current = liveEdges.get(target.key());
                if (current == null) throw new IllegalArgumentException("an edge was already removed");
                String source = fields.containsKey("source")
                        ? selector(fields.get("source"), "node", refs, removed, snapshot).key()
                        : current.source();
                String destination = fields.containsKey("destination")
                        ? selector(fields.get("destination"), "node", refs, removed, snapshot).key()
                        : current.destination();
                optionalText(fields.get("outcome"), "edge outcome");
                if (fields.size() == 2) throw new IllegalArgumentException("an update-edge needs a change");
                liveEdges.put(target.key(), new EdgeEndpoints(source, destination));
            }
            case DELETE_EDGE -> {
                Element target = selector(fields.get("target"), "edge", refs, removed, snapshot);
                if (target.key().startsWith("created:")) {
                    throw new IllegalArgumentException("a proposal cannot delete an element it just created");
                }
                claim(mutated, target);
                liveEdges.remove(target.key());
                removed.add(target.key());
            }
        }
    }

    private static Element selector(PayloadValue value, String kind, Map<String, Element> refs,
                                    Set<String> removed, Snapshot snapshot) {
        if (!(value instanceof PayloadValue.MapValue selector)) {
            throw new IllegalArgumentException("an element selector must be an object");
        }
        Set<String> keys = selector.entries().keySet();
        if (!(keys.equals(Set.of("existing")) || keys.equals(Set.of("created")))) {
            throw new IllegalArgumentException("an element selector needs exactly one existing or created tag");
        }
        String token = identifier(selector.entries().values().iterator().next(), "element selector");
        Element resolved = keys.contains("created") ? refs.get(token) : snapshot.element(kind, token);
        if (resolved == null || !resolved.kind().equals(kind)) {
            throw new IllegalArgumentException("an element selector names an unknown " + kind);
        }
        if (removed.contains(resolved.key())) {
            throw new IllegalArgumentException("an element selector names a removed " + kind);
        }
        return resolved;
    }

    private static void claim(Set<String> mutated, Element target) {
        if (!mutated.add(target.key())) {
            throw new IllegalArgumentException("an element is changed more than once");
        }
    }

    private static void optionalPosition(PayloadValue value) {
        if (value == null) return;
        if (!(value instanceof PayloadValue.MapValue position)) {
            throw new IllegalArgumentException("a node position must be an object");
        }
        requireExact(position.entries(), Set.of("x", "y"), "position");
        for (PayloadValue coordinate : position.entries().values()) {
            if (!(coordinate instanceof PayloadValue.IntegerValue
                    || coordinate instanceof PayloadValue.DecimalValue)) {
                throw new IllegalArgumentException("node coordinates must be numbers");
            }
        }
    }

    private static void optionalProperties(PayloadValue value, Set<String> allowedNames) {
        if (value == null) return;
        if (!(value instanceof PayloadValue.ListValue properties)) {
            throw new IllegalArgumentException("node properties must be an array");
        }
        var names = new HashSet<String>();
        for (PayloadValue entry : properties.values()) {
            if (!(entry instanceof PayloadValue.MapValue property)) {
                throw new IllegalArgumentException("a property edit must be an object");
            }
            requireExact(property.entries(), Set.of("name", "value"), "property edit");
            String name = identifier(property.entries().get("name"), "property name");
            if (sensitiveName(name)) {
                throw new IllegalArgumentException("secret-class properties are not allowed in proposals");
            }
            if (!allowedNames.contains(name)) {
                throw new IllegalArgumentException("a property is not declared by the captured catalog");
            }
            unique(name, names, "property name");
            text(property.entries().get("value"), "property value");
        }
    }

    private static void optionalPropertyNames(PayloadValue value, Set<String> allowedNames) {
        if (value == null) return;
        if (!(value instanceof PayloadValue.ListValue list)) {
            throw new IllegalArgumentException("removed properties must be a list");
        }
        var names = new HashSet<String>();
        for (PayloadValue entry : list.values()) {
            String name = identifier(entry, "property name");
            if (sensitiveName(name)) {
                throw new IllegalArgumentException("secret-class properties are not allowed in proposals");
            }
            if (!allowedNames.contains(name)) {
                throw new IllegalArgumentException("a property is not declared by the captured catalog");
            }
            unique(name, names, "property name");
        }
    }

    private static boolean sensitiveName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(?:secret|password|passwd|token|credential|api[-_.]?key).*");
    }

    private static void rejectSetAndRemoveOverlap(PayloadValue properties, PayloadValue removed) {
        if (!(properties instanceof PayloadValue.ListValue set)
                || !(removed instanceof PayloadValue.ListValue remove)) return;
        Set<String> names = new HashSet<>();
        for (PayloadValue entry : set.values()) {
            if (entry instanceof PayloadValue.MapValue property) {
                names.add(text(property.entries().get("name"), "property name"));
            }
        }
        for (PayloadValue entry : remove.values()) {
            if (names.contains(text(entry, "property name"))) {
                throw new IllegalArgumentException("a property cannot be both set and removed");
            }
        }
    }

    private static void unique(String value, Set<String> values, String label) {
        if (!values.add(value)) throw new IllegalArgumentException(label + " is duplicated");
    }

    private static String identifier(PayloadValue value, String label) {
        String text = text(value, label);
        if (text.isBlank() || text.length() > MAX_IDENTIFIER || !text.equals(text.trim())
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return text;
    }

    private static String text(PayloadValue value, String label) {
        if (!(value instanceof PayloadValue.TextValue text)) {
            throw new IllegalArgumentException(label + " must be text");
        }
        return text.value();
    }

    private static void optionalText(PayloadValue value, String label) {
        if (value != null) text(value, label);
    }

    private static void requireExact(Map<String, PayloadValue> fields, Set<String> exact, String label) {
        if (!fields.keySet().equals(exact)) {
            throw new IllegalArgumentException(label + " has unknown or missing fields");
        }
    }

    private record Element(String kind, String behavior, String key) { }

    private record EdgeEndpoints(String source, String destination) {
        boolean incidentTo(String nodeKey) {
            return source.equals(nodeKey) || destination.equals(nodeKey);
        }
    }

    /** Safe, already-budgeted graph/catalog projection captured on the incoming turn. */
    private record Snapshot(Map<String, Element> nodes, Map<String, Element> edges,
                            Map<String, Set<String>> catalog,
                            Map<String, EdgeEndpoints> edgeEndpoints) {
        static Snapshot read(String contextJson) {
            if (contextJson == null) {
                throw new IllegalArgumentException("a proposal needs captured graph and catalog context");
            }
            PayloadValue parsed = PayloadJson.read(contextJson.getBytes(StandardCharsets.UTF_8),
                    AssistantTurn.TURN_LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue context)
                    || !(context.entries().get("catalog") instanceof PayloadValue.ListValue catalogValues)
                    || !(context.entries().get("graph") instanceof PayloadValue.MapValue graph)
                    || !(graph.entries().get("nodes") instanceof PayloadValue.ListValue nodeValues)
                    || !(graph.entries().get("edges") instanceof PayloadValue.ListValue edgeValues)) {
                throw new IllegalArgumentException("a proposal needs captured graph and catalog context");
            }
            Map<String, Set<String>> catalog = new HashMap<>();
            for (PayloadValue value : catalogValues.values()) {
                if (!(value instanceof PayloadValue.MapValue descriptor)) continue;
                String behavior = optionalIdentifier(descriptor.entries().get("behavior"));
                if (behavior == null || catalog.containsKey(behavior)) {
                    throw new IllegalArgumentException("the captured catalog is invalid");
                }
                Set<String> properties = new HashSet<>();
                if (descriptor.entries().get("properties") instanceof PayloadValue.ListValue propertyValues) {
                    for (PayloadValue propertyValue : propertyValues.values()) {
                        if (!(propertyValue instanceof PayloadValue.MapValue property)) continue;
                        String name = optionalIdentifier(property.entries().get("name"));
                        if (name == null || sensitiveName(name) || !properties.add(name)) {
                            throw new IllegalArgumentException("the captured catalog is invalid");
                        }
                    }
                }
                catalog.put(behavior, Set.copyOf(properties));
            }
            Map<String, Element> nodes = elements(nodeValues.values(), "node");
            Map<String, Element> edges = new HashMap<>();
            Map<String, EdgeEndpoints> edgeEndpoints = new HashMap<>();
            for (PayloadValue value : edgeValues.values()) {
                if (!(value instanceof PayloadValue.MapValue object)) continue;
                String id = optionalIdentifier(object.entries().get("id"));
                String source = optionalIdentifier(object.entries().get("source"));
                String destination = optionalIdentifier(object.entries().get("target"));
                if (id == null || source == null || destination == null || edges.containsKey(id)
                        || !nodes.containsKey(source) || !nodes.containsKey(destination)) {
                    throw new IllegalArgumentException("the captured graph is invalid");
                }
                Element edge = new Element("edge", null, "existing:edge:" + id);
                edges.put(id, edge);
                edgeEndpoints.put(edge.key(),
                        new EdgeEndpoints(nodes.get(source).key(), nodes.get(destination).key()));
            }
            if (catalog.isEmpty()) throw new IllegalArgumentException("the captured catalog is empty");
            return new Snapshot(Map.copyOf(nodes), Map.copyOf(edges), Map.copyOf(catalog),
                    Map.copyOf(edgeEndpoints));
        }

        private static Map<String, Element> elements(List<PayloadValue> values, String kind) {
            Map<String, Element> result = new HashMap<>();
            for (PayloadValue value : values) {
                if (!(value instanceof PayloadValue.MapValue object)) continue;
                String id = optionalIdentifier(object.entries().get("id"));
                if (id == null || result.containsKey(id)) {
                    throw new IllegalArgumentException("the captured graph is invalid");
                }
                String behavior = kind.equals("node")
                        ? optionalIdentifier(object.entries().get("behavior")) : null;
                result.put(id, new Element(kind, behavior, "existing:" + kind + ":" + id));
            }
            return result;
        }

        private static String optionalIdentifier(PayloadValue value) {
            if (!(value instanceof PayloadValue.TextValue text) || text.value().isBlank()) return null;
            return text.value();
        }

        Set<String> allIds() {
            Set<String> ids = new HashSet<>(nodes.keySet());
            ids.addAll(edges.keySet());
            return ids;
        }

        Element element(String kind, String id) {
            return kind.equals("node") ? nodes.get(id) : edges.get(id);
        }

        void requireBehavior(String behavior) {
            if (!catalog.containsKey(behavior)) {
                throw new IllegalArgumentException("catalog behavior is not in the captured catalog");
            }
        }

        Set<String> properties(String behavior) {
            requireBehavior(behavior);
            return catalog.get(behavior);
        }
    }
}
