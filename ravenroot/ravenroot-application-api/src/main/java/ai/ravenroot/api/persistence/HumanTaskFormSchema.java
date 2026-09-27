package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Closed, non-recursive built-in form schema.
 *
 * @param version form-schema contract version
 * @param fields ordered closed field definitions
 */
public record HumanTaskFormSchema(int version, List<Field> fields) {
    public static final int VERSION_1 = 1;
    public static final int MAX_FIELDS = 64;
    public static final int MAX_TEXT_UTF8_BYTES = 16 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final PayloadLimits SCHEMA_LIMITS = new PayloadLimits(
            HumanTaskPresentation.MAX_FORM_SCHEMA_UTF8_BYTES, 5, 512, 2_048, 16_384, 64);

    /** Closed set of typed values supported by built-in forms. */
    public enum Type {
        /** UTF-8-bounded plain text. */
        TEXT,
        /** UTF-8-bounded plain text displayed in a multiline control. */
        MULTILINE_TEXT,
        /** Boolean value. */
        BOOLEAN,
        /** Signed integer value. */
        INTEGER,
        /** Integer or decimal value. */
        DECIMAL,
        /** Plain text selected from the pinned allowed values. */
        ENUM,
        /** ISO-8601 calendar date. */
        DATE,
        /** ISO-8601 instant. */
        DATE_TIME
    }

    /**
     * One field in the closed schema.
     *
     * @param name stable field key
     * @param label responder-visible plain-text label
     * @param help optional responder-visible plain-text help
     * @param type closed typed-value contract
     * @param required whether a non-null response member is required
     * @param maxUtf8Bytes maximum encoded bytes for text-bearing values
     * @param allowedValues pinned values accepted by an enum field
     * @param minimum optional inclusive numeric lower bound
     * @param maximum optional inclusive numeric upper bound
     */
    public record Field(String name, String label, String help, Type type, boolean required,
                        int maxUtf8Bytes, List<String> allowedValues,
                        Optional<Double> minimum, Optional<Double> maximum) {
        /**
         * Compatibility constructor for schema-v1 fields without numeric bounds.
         * @param name stable field key
         * @param label responder-visible plain-text label
         * @param help optional responder-visible plain-text help
         * @param type closed typed-value contract
         * @param required whether a non-null response member is required
         * @param maxUtf8Bytes maximum encoded bytes for text-bearing values
         * @param allowedValues pinned values accepted by an enum field
         */
        public Field(String name, String label, String help, Type type, boolean required,
                     int maxUtf8Bytes, List<String> allowedValues) {
            this(name, label, help, type, required, maxUtf8Bytes, allowedValues,
                    Optional.empty(), Optional.empty());
        }

        /** Validates and snapshots the bounded field definition. */
        public Field {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid human-task form field name");
            }
            label = text(label, "label", 256, true);
            help = text(help, "help", 1_024, false);
            type = Objects.requireNonNull(type, "type");
            if (maxUtf8Bytes < 1 || maxUtf8Bytes > MAX_TEXT_UTF8_BYTES) {
                throw new IllegalArgumentException("form field text limit is outside bounds");
            }
            allowedValues = List.copyOf(allowedValues == null ? List.of() : allowedValues);
            if (type == Type.ENUM) {
                if (allowedValues.isEmpty() || allowedValues.size() > 128
                        || new HashSet<>(allowedValues).size() != allowedValues.size()) {
                    throw new IllegalArgumentException("enum field requires unique allowed values");
                }
                allowedValues = allowedValues.stream()
                        .map(value -> text(value, "allowed value", 256, true)).toList();
            } else if (!allowedValues.isEmpty()) {
                throw new IllegalArgumentException("only enum fields may carry allowed values");
            }
            minimum = minimum == null ? Optional.empty() : minimum;
            maximum = maximum == null ? Optional.empty() : maximum;
            if ((minimum.isPresent() || maximum.isPresent())
                    && type != Type.INTEGER && type != Type.DECIMAL) {
                throw new IllegalArgumentException("only numeric fields may carry bounds");
            }
            minimum.ifPresent(value -> requireFiniteBound(value, "minimum"));
            maximum.ifPresent(value -> requireFiniteBound(value, "maximum"));
            if (minimum.isPresent() && maximum.isPresent()
                    && minimum.orElseThrow() > maximum.orElseThrow()) {
                throw new IllegalArgumentException("form numeric minimum exceeds maximum");
            }
            if (type == Type.INTEGER && (minimum.filter(value -> value != Math.rint(value)).isPresent()
                    || maximum.filter(value -> value != Math.rint(value)).isPresent())) {
                throw new IllegalArgumentException("integer form bounds must be integral");
            }
        }
    }

    /** Validates and snapshots the ordered closed schema. */
    public HumanTaskFormSchema {
        if (version != VERSION_1) throw new IllegalArgumentException("unsupported form schema version");
        fields = List.copyOf(fields == null ? List.of() : fields);
        if (fields.isEmpty() || fields.size() > MAX_FIELDS || fields.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("form schema requires a bounded field list");
        }
        if (new HashSet<>(fields.stream().map(Field::name).toList()).size() != fields.size()) {
            throw new IllegalArgumentException("form field names must be unique");
        }
    }

    /**
     * Validates the exact typed response map, rejecting unknown and missing members.
     *
     * @param value response value to validate
     */
    public void requireResponse(PayloadValue value) {
        if (!(value instanceof PayloadValue.MapValue map)) {
            throw new IllegalArgumentException("form response must be a map");
        }
        var expected = fields.stream().map(Field::name).collect(java.util.stream.Collectors.toSet());
        if (!expected.containsAll(map.entries().keySet())) {
            throw new IllegalArgumentException("form response contains an unknown field");
        }
        for (Field field : fields) {
            PayloadValue supplied = map.entries().get(field.name());
            if (supplied == null || supplied instanceof PayloadValue.NullValue) {
                if (field.required()) throw new IllegalArgumentException("required form field is absent");
                continue;
            }
            requireType(field, supplied);
        }
    }

    /**
     * Encodes the schema into its canonical bounded JSON representation.
     *
     * @return canonical closed schema document
     */
    public String encode() {
        var encodedFields = new ArrayList<PayloadValue>();
        for (Field field : fields) {
            var entry = new LinkedHashMap<String, PayloadValue>();
            entry.put("name", PayloadValue.of(field.name()));
            entry.put("label", PayloadValue.of(field.label()));
            entry.put("help", PayloadValue.of(field.help()));
            entry.put("type", PayloadValue.of(field.type().name()));
            entry.put("required", PayloadValue.of(field.required()));
            entry.put("maxUtf8Bytes", PayloadValue.of(field.maxUtf8Bytes()));
            entry.put("allowedValues", PayloadValue.list(field.allowedValues().stream()
                    .map(PayloadValue::of).toList()));
            entry.put("minimum", field.minimum().<PayloadValue>map(PayloadValue::of)
                    .orElse(PayloadValue.NULL));
            entry.put("maximum", field.maximum().<PayloadValue>map(PayloadValue::of)
                    .orElse(PayloadValue.NULL));
            encodedFields.add(PayloadValue.map(entry));
        }
        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("version", PayloadValue.of(version));
        root.put("fields", PayloadValue.list(encodedFields));
        return PayloadJson.write(PayloadValue.map(root));
    }

    /**
     * Decodes and validates one canonical closed schema document.
     *
     * @param encoded bounded schema document
     * @return validated immutable schema
     */
    public static HumanTaskFormSchema decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw new IllegalArgumentException("form schema is required");
        PayloadValue value = PayloadJson.read(encoded.getBytes(StandardCharsets.UTF_8), SCHEMA_LIMITS);
        if (!(value instanceof PayloadValue.MapValue root)
                || !root.entries().keySet().equals(java.util.Set.of("version", "fields"))
                || !(root.entries().get("version") instanceof PayloadValue.IntegerValue version)
                || !(root.entries().get("fields") instanceof PayloadValue.ListValue list)) {
            throw new IllegalArgumentException("invalid form schema document");
        }
        var fields = new ArrayList<Field>();
        for (PayloadValue item : list.values()) {
            if (!(item instanceof PayloadValue.MapValue map)) {
                throw new IllegalArgumentException("invalid form field document");
            }
            var legacyKeys = java.util.Set.of(
                    "name", "label", "help", "type", "required", "maxUtf8Bytes", "allowedValues");
            var boundedKeys = java.util.Set.of("name", "label", "help", "type", "required",
                    "maxUtf8Bytes", "allowedValues", "minimum", "maximum");
            if (!map.entries().keySet().equals(legacyKeys)
                    && !map.entries().keySet().equals(boundedKeys)) {
                throw new IllegalArgumentException("invalid form field document");
            }
            var allowed = stringList(map.entries().get("allowedValues"));
            fields.add(new Field(string(map, "name"), string(map, "label"), string(map, "help"),
                    Type.valueOf(string(map, "type")), bool(map, "required"),
                    Math.toIntExact(integer(map, "maxUtf8Bytes")), allowed,
                    number(map, "minimum"), number(map, "maximum")));
        }
        return new HumanTaskFormSchema(Math.toIntExact(version.value()), fields);
    }

    private static void requireType(Field field, PayloadValue value) {
        switch (field.type()) {
            case BOOLEAN -> { if (!(value instanceof PayloadValue.BooleanValue)) invalid(field); }
            case INTEGER -> {
                if (!(value instanceof PayloadValue.IntegerValue integer)) { invalid(field); return; }
                requireBounds(field, integer.value());
            }
            case DECIMAL -> {
                double number;
                if (value instanceof PayloadValue.DecimalValue decimal) number = decimal.value();
                else if (value instanceof PayloadValue.IntegerValue integer) number = integer.value();
                else { invalid(field); return; }
                requireBounds(field, number);
            }
            case TEXT, MULTILINE_TEXT, ENUM, DATE, DATE_TIME -> {
                if (!(value instanceof PayloadValue.TextValue text)) invalid(field);
                String supplied = ((PayloadValue.TextValue) value).value();
                if (supplied.getBytes(StandardCharsets.UTF_8).length > field.maxUtf8Bytes()) invalid(field);
                if (field.type() == Type.ENUM && !field.allowedValues().contains(supplied)) invalid(field);
                try {
                    if (field.type() == Type.DATE) LocalDate.parse(supplied);
                    if (field.type() == Type.DATE_TIME) Instant.parse(supplied);
                } catch (DateTimeParseException malformed) { invalid(field); }
            }
        }
    }

    private static void invalid(Field field) {
        throw new IllegalArgumentException("invalid value for form field " + field.name());
    }

    private static String string(PayloadValue.MapValue map, String key) {
        if (!(map.entries().get(key) instanceof PayloadValue.TextValue text)) {
            throw new IllegalArgumentException("invalid form schema field " + key);
        }
        return text.value();
    }

    private static boolean bool(PayloadValue.MapValue map, String key) {
        if (!(map.entries().get(key) instanceof PayloadValue.BooleanValue value)) {
            throw new IllegalArgumentException("invalid form schema field " + key);
        }
        return value.value();
    }

    private static long integer(PayloadValue.MapValue map, String key) {
        if (!(map.entries().get(key) instanceof PayloadValue.IntegerValue value)) {
            throw new IllegalArgumentException("invalid form schema field " + key);
        }
        return value.value();
    }

    private static Optional<Double> number(PayloadValue.MapValue map, String key) {
        PayloadValue value = map.entries().get(key);
        if (value == null || value instanceof PayloadValue.NullValue) return Optional.empty();
        if (value instanceof PayloadValue.IntegerValue integer) return Optional.of((double) integer.value());
        if (value instanceof PayloadValue.DecimalValue decimal) return Optional.of(decimal.value());
        throw new IllegalArgumentException("invalid form schema field " + key);
    }

    private static void requireBounds(Field field, double value) {
        if (field.minimum().filter(bound -> value < bound).isPresent()
                || field.maximum().filter(bound -> value > bound).isPresent()) invalid(field);
    }

    private static void requireFiniteBound(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static List<String> stringList(PayloadValue value) {
        if (!(value instanceof PayloadValue.ListValue list)) {
            throw new IllegalArgumentException("invalid form enum values");
        }
        return list.values().stream().map(item -> {
            if (!(item instanceof PayloadValue.TextValue text)) {
                throw new IllegalArgumentException("invalid form enum value");
            }
            return text.value();
        }).toList();
    }

    private static String text(String value, String name, int maxBytes, boolean required) {
        value = value == null ? "" : value;
        if (required && value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name + " exceeds byte limit");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " contains control data");
            }
        }
        return value;
    }
}
