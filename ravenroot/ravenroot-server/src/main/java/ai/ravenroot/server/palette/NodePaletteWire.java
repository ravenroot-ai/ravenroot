package ai.ravenroot.server.palette;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyGroupDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.server.audit.JsonStrings;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed, bounded wire contract and fail-closed node sanitizer for personal palettes. */
public final class NodePaletteWire {
    public static final int MAX_NAME_CHARACTERS = 120;
    public static final int MAX_DESCRIPTION_CHARACTERS = 2_000;
    public static final PayloadLimits REQUEST_LIMITS = new PayloadLimits(96 * 1024, 4, 512, 128,
            64 * 1024, 256);
    private static final Set<String> PALETTE_FIELDS = Set.of("name");
    private static final Set<String> TEMPLATE_FIELDS = Set.of("paletteId", "name", "node");
    private static final Set<String> UPDATE_FIELDS = Set.of("paletteId", "name");
    private static final Set<String> NODE_FIELDS = Set.of(
            "name", "kind", "behavior", "nodeType", "classname", "description",
            "width", "height", "properties");
    private static final Set<String> KINDS = Set.of("START", "PASSTHROUGH", "BEHAVIOR", "END", "ERROR");
    private static final Set<String> NODE_TYPES = Set.of("start", "end", "terminal", "error", "consumer",
            "handler", "agent", "flow", "actor", "system", "trace", "human-task", "workspace");
    private static final Set<String> STRUCTURAL_PROPERTIES = Set.of("joinPolicy", "joinQuorum", "joinTimeout");

    private NodePaletteWire() { }

    public record CreateTemplate(String paletteId, String name, SanitizedNode node) { }
    public record UpdateTemplate(String paletteId, String name) { }
    public record SanitizedNode(String name, String kind, String behavior, String nodeType, String classname,
                                String description, int width, int height,
                                Map<String, String> properties, Map<String, String> propertyTypes,
                                List<String> workspaceReferences) { }

    public static String readPaletteName(byte[] body) {
        Map<String, PayloadValue> root = object(body, PALETTE_FIELDS);
        return name(root, "name");
    }

    public static UpdateTemplate readUpdate(byte[] body) {
        Map<String, PayloadValue> root = object(body, UPDATE_FIELDS);
        return new UpdateTemplate(identifier(root, "paletteId"), name(root, "name"));
    }

    public static CreateTemplate readCreateTemplate(byte[] body, List<NodeTypeDescriptor> catalog) {
        Map<String, PayloadValue> root = object(body, TEMPLATE_FIELDS);
        PayloadValue rawNode = root.get("node");
        if (!(rawNode instanceof PayloadValue.MapValue nodeValue)) {
            throw new IllegalArgumentException("node must be an object");
        }
        ensureFields(nodeValue.entries(), NODE_FIELDS);
        String kind = text(nodeValue.entries(), "kind").toUpperCase(java.util.Locale.ROOT);
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("unsupported node kind");
        String behavior = text(nodeValue.entries(), "behavior");
        NodeTypeDescriptor descriptor = null;
        if ("BEHAVIOR".equals(kind)) {
            descriptor = catalog.stream().filter(type -> type.behavior().equals(behavior)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("behavior is unavailable"));
        } else if (!behavior.isEmpty()) {
            throw new IllegalArgumentException("structural nodes cannot name a behavior");
        }
        Map<String, String> submitted = stringMap(nodeValue.entries().get("properties"));
        var safeProperties = new LinkedHashMap<String, String>();
        var safeTypes = new LinkedHashMap<String, String>();
        var workspaceReferences = new java.util.ArrayList<String>();
        if (descriptor == null) {
            for (String key : STRUCTURAL_PROPERTIES) {
                if (!submitted.containsKey(key)) continue;
                safeProperties.put(key, submitted.get(key));
                safeTypes.put(key, "joinQuorum".equals(key) || "joinTimeout".equals(key) ? "long" : "string");
            }
        } else {
            for (Map.Entry<String, String> entry : submitted.entrySet()) {
                NodePropertyDescriptor property = declaredProperty(descriptor, entry.getKey());
                if (property == null || property.adapterBinding()
                        || property.type() == NodePropertyType.SECRET_REFERENCE) continue;
                safeProperties.put(entry.getKey(), entry.getValue());
                safeTypes.put(entry.getKey(), graphMlType(property.type()));
                if (property.type() == NodePropertyType.WORKSPACE_REFERENCE) {
                    workspaceReferences.add(entry.getKey());
                }
            }
        }
        String nodeName = bounded(text(nodeValue.entries(), "name"), "node name", MAX_NAME_CHARACTERS);
        if (nodeName.isBlank()) nodeName = "BEHAVIOR".equals(kind) ? descriptor.displayName() : title(kind);
        String description = bounded(text(nodeValue.entries(), "description"), "description",
                MAX_DESCRIPTION_CHARACTERS);
        int width = dimension(nodeValue.entries().get("width"), 80);
        int height = dimension(nodeValue.entries().get("height"), 52);
        String defaultNodeType = descriptor == null ? switch (kind) {
            case "START" -> "start"; case "END" -> "end"; case "ERROR" -> "error";
            case "PASSTHROUGH" -> "flow"; default -> throw new IllegalStateException();
        } : descriptor.visualType();
        String submittedNodeType = text(nodeValue.entries(), "nodeType").strip().toLowerCase(java.util.Locale.ROOT);
        if (!submittedNodeType.isEmpty() && !NODE_TYPES.contains(submittedNodeType)) {
            throw new IllegalArgumentException("nodeType is invalid");
        }
        String nodeType = Set.of("START", "END", "ERROR").contains(kind) || submittedNodeType.isEmpty()
                ? defaultNodeType : submittedNodeType;
        String classname = bounded(text(nodeValue.entries(), "classname").strip(), "classname",
                MAX_NAME_CHARACTERS);
        var node = new SanitizedNode(nodeName, kind, behavior, nodeType, classname, description, width, height,
                Map.copyOf(safeProperties), Map.copyOf(safeTypes), List.copyOf(workspaceReferences));
        return new CreateTemplate(identifier(root, "paletteId"), name(root, "name"), node);
    }

    public static String payload(SanitizedNode node) {
        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("schemaVersion", PayloadValue.of(1L));
        root.put("name", PayloadValue.of(node.name()));
        root.put("kind", PayloadValue.of(node.kind()));
        root.put("behavior", PayloadValue.of(node.behavior()));
        root.put("nodeType", PayloadValue.of(node.nodeType()));
        root.put("classname", PayloadValue.of(node.classname()));
        root.put("description", PayloadValue.of(node.description()));
        root.put("width", PayloadValue.of((long) node.width()));
        root.put("height", PayloadValue.of((long) node.height()));
        root.put("properties", textMap(node.properties()));
        root.put("propertyTypes", textMap(node.propertyTypes()));
        root.put("workspaceReferences", PayloadValue.list(node.workspaceReferences().stream()
                .map(PayloadValue::of).toList()));
        return PayloadJson.write(PayloadValue.map(root));
    }

    public static SanitizedNode readStoredNode(String payload) {
        Map<String, PayloadValue> root = object(payload.getBytes(StandardCharsets.UTF_8), Set.of(
                "schemaVersion", "name", "kind", "behavior", "nodeType", "description",
                "classname", "width", "height", "properties", "propertyTypes", "workspaceReferences"));
        return new SanitizedNode(text(root, "name"), text(root, "kind"), text(root, "behavior"),
                text(root, "nodeType"), text(root, "classname"), text(root, "description"),
                dimension(root.get("width"), 80),
                dimension(root.get("height"), 52), stringMap(root.get("properties")),
                stringMap(root.get("propertyTypes")), textList(root.get("workspaceReferences")));
    }

    public static String writeAll(List<NodePaletteStore.Palette> palettes,
                                  List<NodePaletteStore.Template> templates) {
        String paletteJson = palettes.stream().map(NodePaletteWire::writePalette)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String templateJson = templates.stream().map(NodePaletteWire::writeTemplate)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"schemaVersion\":1,\"palettes\":" + paletteJson + ",\"templates\":" + templateJson + "}";
    }

    public static String writePalette(NodePaletteStore.Palette palette) {
        return "{\"id\":\"" + escape(palette.id()) + "\",\"name\":\"" + escape(palette.name())
                + "\",\"version\":" + palette.version() + ",\"createdAt\":\""
                + escape(palette.createdAt().toString()) + "\",\"updatedAt\":\""
                + escape(palette.updatedAt().toString()) + "\"}";
    }

    public static String writeTemplate(NodePaletteStore.Template template) {
        return "{\"id\":\"" + escape(template.id()) + "\",\"paletteId\":\""
                + escape(template.paletteId()) + "\",\"name\":\"" + escape(template.name())
                + "\",\"kind\":\"" + escape(template.kind()) + "\",\"version\":"
                + template.version() + ",\"createdAt\":\"" + escape(template.createdAt().toString())
                + "\",\"updatedAt\":\"" + escape(template.updatedAt().toString())
                + "\",\"node\":" + template.payload() + "}";
    }

    private static Map<String, PayloadValue> object(byte[] body, Set<String> fields) {
        if (body.length > REQUEST_LIMITS.maxEncodedBytes()) throw new IllegalArgumentException("request too large");
        PayloadValue parsed = PayloadJson.read(body, REQUEST_LIMITS);
        if (!(parsed instanceof PayloadValue.MapValue root)) throw new IllegalArgumentException("request must be an object");
        ensureFields(root.entries(), fields);
        return root.entries();
    }

    private static void ensureFields(Map<String, PayloadValue> entries, Set<String> fields) {
        if (!fields.containsAll(entries.keySet())) throw new IllegalArgumentException("unknown field");
    }

    private static String name(Map<String, PayloadValue> root, String field) {
        String value = bounded(text(root, field).strip(), field, MAX_NAME_CHARACTERS);
        if (value.isBlank() || controls(value)) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String identifier(Map<String, PayloadValue> root, String field) {
        String value = text(root, field);
        try { return java.util.UUID.fromString(value).toString(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(field + " is invalid"); }
    }

    private static String text(Map<String, PayloadValue> entries, String field) {
        PayloadValue value = entries.get(field);
        if (value == null) return "";
        if (!(value instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException(field + " must be text");
        return text.value();
    }

    private static Map<String, String> stringMap(PayloadValue value) {
        if (value == null) return Map.of();
        if (!(value instanceof PayloadValue.MapValue map)) throw new IllegalArgumentException("properties must be an object");
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entries().entrySet()) {
            if (!(entry.getValue() instanceof PayloadValue.TextValue text)) {
                throw new IllegalArgumentException("property values must be text");
            }
            if (entry.getKey().length() > 128 || text.value().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new IllegalArgumentException("property is too large");
            }
            result.put(entry.getKey(), text.value());
        }
        return result;
    }

    private static List<String> textList(PayloadValue value) {
        if (value == null) return List.of();
        if (!(value instanceof PayloadValue.ListValue list)) throw new IllegalArgumentException("expected list");
        var result = new java.util.ArrayList<String>();
        for (PayloadValue item : list.values()) {
            if (!(item instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException("expected text");
            result.add(text.value());
        }
        return List.copyOf(result);
    }

    private static NodePropertyDescriptor declaredProperty(NodeTypeDescriptor descriptor, String name) {
        for (NodePropertyDescriptor property : descriptor.properties()) {
            if (property.name().equals(name)) return property;
        }
        for (NodePropertyGroupDescriptor group : descriptor.additionalProperties()) {
            String prefix = group.name() + ".";
            if (!name.startsWith(prefix)) continue;
            String remainder = name.substring(prefix.length());
            int dot = remainder.indexOf('.');
            if (dot < 1 || !remainder.substring(0, dot).matches("[1-9][0-9]*")) return null;
            String field = remainder.substring(dot + 1);
            for (NodePropertyDescriptor property : group.fields()) {
                if (property.name().equals(field)) return property;
            }
        }
        return null;
    }

    private static int dimension(PayloadValue raw, int fallback) {
        long value = raw instanceof PayloadValue.IntegerValue integer ? integer.value() : fallback;
        return (int) Math.max(24, Math.min(1_000, value));
    }

    private static PayloadValue textMap(Map<String, String> values) {
        var result = new LinkedHashMap<String, PayloadValue>();
        values.forEach((key, value) -> result.put(key, PayloadValue.of(value)));
        return PayloadValue.map(result);
    }

    private static String graphMlType(NodePropertyType type) {
        return switch (type) {
            case INTEGER -> "long";
            case DECIMAL -> "double";
            case BOOLEAN -> "boolean";
            default -> "string";
        };
    }

    private static String bounded(String value, String field, int limit) {
        if (value.length() > limit || controls(value)) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static boolean controls(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static String title(String kind) {
        String lower = kind.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String escape(String value) { return JsonStrings.escape(value); }
}
