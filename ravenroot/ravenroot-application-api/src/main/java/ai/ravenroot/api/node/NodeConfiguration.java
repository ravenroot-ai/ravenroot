package ai.ravenroot.api.node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The read-only view of one graph node handed to a {@link NodeBehavior} when its handler is built.
 *
 * <h2>Why a view rather than the graph's own node type</h2>
 * <p>The runtime's {@code GraphNode} lives in {@code ravenroot-core}, alongside the graph model, the
 * parser and the engine. Handing it to node authors would make every third-party node depend on
 * {@code ravenroot-core}, and therefore on TinkerGraph and a CEL interpreter, to implement a node
 * that may only call an HTTP endpoint. This view carries the three things a behavior legitimately
 * needs — which node it is, what it was called, and its configured properties — and nothing that
 * would let a node reach into the graph it belongs to.</p>
 *
 * <p>That narrowness is a boundary, not an omission. A node that could navigate the graph could
 * decide its behaviour from its neighbours, and a node's configuration would then stop being
 * reviewable in isolation.</p>
 *
 * <h2>Values only, never schema (SEC-09)</h2>
 * <p>{@link #properties()} are graph-supplied <em>values</em>. Which properties exist, what type
 * they are, and which values are permitted come from the behavior's own
 * {@link ai.ravenroot.api.catalog.NodeTypeDescriptor}, which comes from the registered package and
 * never from graph content. A graph cannot introduce a property, change its type or claim a
 * capability by writing one here.</p>
 *
 * <p>Reserved {@code ravenroot.} properties are refused at ingest and cannot appear in this map.</p>
 * @param nodeId non-blank identifier of the configured graph node
 * @param behavior registered behavior name selected by the graph; it must not be blank
 * @param properties immutable snapshot of graph-supplied values; absent values are represented by an
 *                   empty map
 */
public record NodeConfiguration(String nodeId, String behavior, Map<String, Object> properties) {

    /**
     * Rejects incomplete identity data and takes an insertion-preserving immutable property snapshot.
     * The snapshot prevents a graph mutation after construction from changing a built node's behavior.
     */
    public NodeConfiguration {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("A node configuration must have a non-blank node id");
        }
        if (behavior == null || behavior.isBlank()) {
            throw new IllegalArgumentException("A node configuration must have a non-blank behavior name");
        }
        properties = properties == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
    }

    /**
     * The configured value of {@code name}, or empty when it is absent or blank.
     *
     * <p>Blank counts as absent so that a behavior's "is this set?" and the catalog's
     * required-property validation cannot disagree: the schema validator treats a blank required
     * property as missing, and a behavior that treated {@code ""} as present would then act on a
     * value the validator believed it had already rejected.</p>
     * @param name property key to inspect; must not be {@code null}
     * @return a non-blank string view of the configured value, or empty when no usable value exists
     */
    public Optional<String> property(String name) {
        Object value = properties.get(Objects.requireNonNull(name, "name"));
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

/**
 * The configured value of {@code name}, or {@code defaultValue} when absent or blank.
 * @param name property key to inspect; must not be {@code null}
 * @param defaultValue value to use when the property is absent or blank; it may be {@code null}
 * @return the configured non-blank string, otherwise {@code defaultValue}
 */
    public String property(String name, String defaultValue) {
        return property(name).orElse(defaultValue);
    }

    /**
     * The configured value of {@code name}, failing when it is absent.
     *
     * @throws IllegalStateException when the property is absent or blank, which for a property the
     *         descriptor declares required means the graph was admitted without schema validation
     * @param name property key required by this behavior; must not be {@code null}
     * @return the configured non-blank string
     */
    public String requiredProperty(String name) {
        return property(name).orElseThrow(() -> new IllegalStateException(
                "Node '" + nodeId + "' (" + behavior + ") requires property '" + name + "'"));
    }
}
