package ai.ravenroot.api.catalog;

import java.util.Map;

/** Platform-owned author selection for per-node, per-traversal admission. */
public final class NodeRuntimeMaxConcurrencyProperty {
    /** Platform-owned GraphML property name. */
public static final String NAME = "runtime.maxConcurrency";

    private NodeRuntimeMaxConcurrencyProperty() {
    }

    /**
 * Returns the validated value, or the trusted descriptor default when absent.
* @param descriptor trusted catalog descriptor governing the node type
* @param instanceValues authored property values for the node instance
* @return validated authored value or the descriptor default
 */
    public static int effectiveValue(NodeTypeDescriptor descriptor, Map<String, Object> instanceValues) {
        NodeRuntimeConcurrency constraint = descriptor == null
                ? NodeRuntimeConcurrency.DEFAULT : descriptor.runtimeConcurrency();
        if (instanceValues == null || !instanceValues.containsKey(NAME)) {
            return constraint.defaultValue();
        }
        return parse(instanceValues.get(NAME));
    }

    /**
 * Strict integer parsing: decimal digits only; booleans/fractions/approximate strings fail.
* @param raw candidate authored value to parse as a strict integer
* @return exact integer represented by the authored value
 */
    public static int parse(Object raw) {
        if (raw instanceof Integer integer) {
            return integer;
        }
        if (raw instanceof Long value && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return value.intValue();
        }
        String text = raw == null ? "" : raw.toString().strip();
        if (!text.matches("[0-9]+")) {
            throw new IllegalArgumentException("invalid runtime max concurrency");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid runtime max concurrency", invalid);
        }
    }

    /**
 * Reports whether authored values declare the platform property.
 * @param values authored property values to inspect
 * @return whether the platform property is explicitly present
 */
public static boolean declaredBy(Map<String, Object> values) {
        return values != null && values.containsKey(NAME);
    }

    /**
 * Refuses a node package that attempts to redefine the platform-owned property shape.
* @param descriptor trusted catalog descriptor governing the node type
 */
    public static void validateShape(NodeTypeDescriptor descriptor) {
        boolean collision = descriptor.properties().stream()
                .anyMatch(property -> NAME.equals(property.name()));
        if (collision) {
            throw new IllegalArgumentException("Behavior '" + descriptor.behavior() + "' property '"
                    + NAME + "' is platform-owned and cannot be declared as a behavior property");
        }
    }
}
