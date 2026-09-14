package ai.ravenroot.api.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A dynamic, indexed collection of atomic node-property groups.
 *
 * <p>A group named {@code skills} with fields {@code name}, {@code description}, and
 * {@code instructions} owns keys such as {@code skills.1.name}. Indices are canonical positive
 * decimal integers, start at one, and are contiguous. Every field in one present item is required;
 * the collection itself is optional and has no descriptor-level item maximum.</p>
 *
 * <p>This is the trusted additional-properties mechanism. Unknown graph properties remain inert and
 * round-trip unchanged, while keys matched here are type-checked and delivered to the behavior just
 * like statically named properties.</p>
 *
 * @param name stable collection name and property prefix
 * @param displayName editor-facing collection label
 * @param description editor-facing collection description
 * @param fields required fields of one atomic item, in deterministic display order
 */
public record NodePropertyGroupDescriptor(
        String name,
        String displayName,
        String description,
        List<NodePropertyDescriptor> fields) {

    /**
     * Validates and normalizes one trusted dynamic property collection descriptor.
     */
    public NodePropertyGroupDescriptor {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Property group name must be a stable identifier");
        }
        displayName = displayName == null || displayName.isBlank() ? name : displayName;
        description = description == null ? "" : description;
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Property group '" + name + "' must declare fields");
        }
        Map<String, NodePropertyDescriptor> unique = new LinkedHashMap<>();
        for (NodePropertyDescriptor field : fields) {
            Objects.requireNonNull(field, "field");
            if (!field.required()) {
                throw new IllegalArgumentException("Property group '" + name + "' field '"
                        + field.name() + "' must be required within an item");
            }
            if (!field.name().matches("[A-Za-z][A-Za-z0-9_-]*") || unique.put(field.name(), field) != null) {
                throw new IllegalArgumentException("Property group '" + name
                        + "' has an invalid or duplicate field name");
            }
            if (field.adapterBinding() || field.visibleWhen() != null || field.requiredWhen() != null) {
                throw new IllegalArgumentException("Property group fields cannot be adapter bindings or conditional");
            }
        }
    }

    /**
     * Resolves a canonical key such as {@code skills.12.description}.
     *
     * @param propertyName concrete property name to resolve
     * @return the matched positive item index and field, or empty when the key is not in this group
     */
    public Optional<Match> match(String propertyName) {
        if (propertyName == null || !propertyName.startsWith(name + ".")) {
            return Optional.empty();
        }
        String suffix = propertyName.substring(name.length() + 1);
        int separator = suffix.indexOf('.');
        if (separator < 1 || separator == suffix.length() - 1) {
            return Optional.empty();
        }
        String indexText = suffix.substring(0, separator);
        if (!indexText.matches("[1-9][0-9]*")) {
            return Optional.empty();
        }
        long parsed;
        try {
            parsed = Long.parseLong(indexText);
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
        if (parsed > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        String fieldName = suffix.substring(separator + 1);
        return fields.stream().filter(field -> field.name().equals(fieldName)).findFirst()
                .map(field -> new Match((int) parsed, field));
    }

    /**
     * Materializes the scalar descriptor used to validate one concrete item field.
     *
     * @param index positive collection item index
     * @param field field descriptor belonging to this group
     * @return scalar descriptor for the indexed concrete property
     */
    public NodePropertyDescriptor property(int index, NodePropertyDescriptor field) {
        if (index < 1 || !fields.contains(field)) {
            throw new IllegalArgumentException("Property group field does not belong to this item");
        }
        return new NodePropertyDescriptor(name + "." + index + "." + field.name(),
                displayName + " " + index + " " + field.displayName(), field.type(), true,
                field.description(), "", field.allowedValues(), false, null, null,
                field.minimumValue(), field.maximumValue(), field.maximumUtf8Bytes(),
                field.maximumItems(), field.maximumItemUtf8Bytes());
    }

    /**
     * One trusted match in this collection.
     *
     * @param index positive collection item index parsed from the property name
     * @param field matched field descriptor from the owning group
     */
    public record Match(int index, NodePropertyDescriptor field) { }
}
