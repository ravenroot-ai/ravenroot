package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.core.graph.GraphNode;

import java.util.LinkedHashMap;
import java.util.Map;

final class NodeProperties {
    private NodeProperties() {
    }

    static String string(GraphNode node, String name, String defaultValue) {
        Object value = node.properties().get(name);
        return value == null ? defaultValue : value.toString();
    }

    static String required(GraphNode node, String name) {
        String value = string(node, name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(
                "Node " + node.id() + " requires property '" + name + "'");
        return value;
    }

    static boolean bool(GraphNode node, String name, boolean defaultValue) {
        String value = string(node, name, Boolean.toString(defaultValue));
        return Boolean.parseBoolean(value);
    }

    static long number(GraphNode node, String name, long defaultValue) {
        String value = string(node, name, Long.toString(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Node " + node.id() + " property '" + name + "' must be an integer");
        }
    }

    static Map<String, Object> attributes(NodeMessage message, Object... additions) {
        var result = new LinkedHashMap<String, Object>(message.attributes());
        for (int index = 0; index + 1 < additions.length; index += 2) {
            if (additions[index + 1] != null) result.put(String.valueOf(additions[index]), additions[index + 1]);
        }
        return Map.copyOf(result);
    }

    /**
     * Matches {@code {{payload}}} and {@code {{payload.a.b}}} / {@code {{payload.items.0}}}.
     *
     * <p>Deliberately anchored to the {@code payload} prefix so the two pre-existing token families,
     * {@code {{attributes.X}}} and {@code {{properties.X}}}, keep their exact previous behaviour. They
     * address node-local configuration, not the value flowing along an edge, and widening them is a
     * separate from edge-payload interpolation.</p>
     */
    private static final java.util.regex.Pattern PAYLOAD_TOKEN =
            java.util.regex.Pattern.compile("\\{\\{payload(\\.[^{}]*)?}}");

    /**
     * Renders a node's template against the value that arrived on its incoming edge.
     *
     * <h2>Why field access had to exist</h2>
     * <p>Before this, the only payload token was the literal {@code {{payload}}}, substituted with
     * {@code String.valueOf(payload)} -- the whole value, stringified. Two nodes could pass a value;
     * they could not pass a value one of them could address a field of. The canonical composition
     * pair, {@code cel-transform} into {@code template}, therefore could not work as any graph author
     * would expect: the transform produced a map and the template could only render it as
     * {@code {name=x}}, Java's {@code Map.toString}, not even JSON.</p>
     *
     * <h2>What a graph author gets</h2>
     * <ul>
     *   <li>{@code {{payload}}} -- the whole value. See the fallback rule below.</li>
     *   <li>{@code {{payload.name}}} -- the {@code name} entry of a map payload.</li>
     *   <li>{@code {{payload.items.0}}} -- index 0 of a list, at any depth, mixed freely with keys.</li>
     * </ul>
     *
     * <h2>Absence is explicit, never a blank</h2>
     * <p>An unresolvable path fails the node with a message naming the node, the token and the reason.
     * It does not render an empty string and does not leave the token in the output. Both of those are
     * silent, and a template that quietly emits {@code Hello } where the author wrote
     * {@code Hello {{payload.name}}} is the same class of defect as a run reporting success having
     * done nothing. A null payload fails the same way rather than rendering the four-character string
     * {@code "null"} into a result, which reads as data and is not.</p>
     *
     * <p><strong>{@code toString()} remains the fallback, and only the fallback.</strong> A bare
     * {@code {{payload}}} over a value with no addressable structure -- a string, a number, a domain
     * object -- renders through {@code String.valueOf}. That is defensible as a last resort and is
     * indefensible as the only path, which is what it was. An author who sees {@code {name=x}} in an
     * output is looking at this fallback applied to a structured value, and should address the field
     * instead.</p>
     */
    static String render(String template, NodeMessage message, GraphNode node) {
        if (template == null) {
            return "";
        }
        var matcher = PAYLOAD_TOKEN.matcher(template);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            Object resolved = path == null || path.isEmpty()
                    ? requirePresent(message.payload(), matcher.group(), node)
                    : resolve(message.payload(), path.substring(1), matcher.group(), node);
            matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(
                    String.valueOf(resolved)));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        for (var entry : message.attributes().entrySet()) {
            result = result.replace("{{attributes." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        for (var entry : node.properties().entrySet()) {
            result = result.replace("{{properties." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Object requirePresent(Object payload, String token, GraphNode node) {
        if (payload == null) {
            throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                    + "': no value arrived on its incoming edge");
        }
        return payload;
    }

    /**
     * Walks a dotted path over maps and lists, failing at the first step that cannot be taken.
     *
     * <p>Fails at the step rather than at the end so the message names the segment that was wrong. An
     * author debugging {@code {{payload.order.total}}} needs to know whether {@code order} was absent
     * or {@code total} was, and a single "unresolvable path" would make those indistinguishable.</p>
     */
    private static Object resolve(Object payload, String path, String token, GraphNode node) {
        Object current = requirePresent(payload, token, node);
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': the path has an empty segment");
            }
            current = step(current, segment, token, node);
        }
        return current;
    }

    private static Object step(Object current, String segment, String token, GraphNode node) {
        if (current instanceof Map<?, ?> map) {
            if (!map.containsKey(segment)) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': the value has no entry '" + segment + "'");
            }
            Object next = map.get(segment);
            if (next == null) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': entry '" + segment + "' is null");
            }
            return next;
        }
        // Arrays are indexed alongside lists deliberately: a CEL list literal arrives here as an
        // Object[], not a java.util.List, so treating only List would make {{payload.items.0}} work or
        // not depending on which upstream behavior produced the sequence -- exactly the "no agreed
        // shape on an edge" handled here, reproduced at the validation boundary.
        boolean isList = current instanceof java.util.List<?>;
        if (isList || current.getClass().isArray()) {
            int size = isList
                    ? ((java.util.List<?>) current).size()
                    : java.lang.reflect.Array.getLength(current);
            int index;
            try {
                index = Integer.parseInt(segment);
            } catch (NumberFormatException notAnIndex) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': '" + segment + "' is not a list index");
            }
            if (index < 0 || index >= size) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': index " + index + " is outside a list of " + size);
            }
            Object next = isList
                    ? ((java.util.List<?>) current).get(index)
                    : java.lang.reflect.Array.get(current, index);
            if (next == null) {
                throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                        + "': index " + index + " is null");
            }
            return next;
        }
        throw new IllegalArgumentException("Node " + node.id() + " cannot render '" + token
                + "': '" + segment + "' cannot be addressed on a "
                + current.getClass().getSimpleName() + ", which has no fields");
    }
}
