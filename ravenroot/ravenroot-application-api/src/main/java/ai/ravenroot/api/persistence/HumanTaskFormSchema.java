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
import java.util.regex.Pattern;

/** Closed, non-recursive built-in form schema. */
public record HumanTaskFormSchema(int version, List<Field> fields) {
    public static final int VERSION_1 = 1;
    public static final int MAX_FIELDS = 64;
    public static final int MAX_TEXT_UTF8_BYTES = 16 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final PayloadLimits SCHEMA_LIMITS = new PayloadLimits(
            HumanTaskPresentation.MAX_FORM_SCHEMA_UTF8_BYTES, 5, 512, 2_048, 16_384, 64);

    public enum Type { TEXT, BOOLEAN, INTEGER, DECIMAL, ENUM, DATE, DATE_TIME }

    /** One field in the closed schema. */
    public record Field(String name, String label, String help, Type type, boolean required,
                        int maxUtf8Bytes, List<String> allowedValues) {
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
        }
    }

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

    /** Validates the exact typed response map, rejecting unknown and missing members. */
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
            encodedFields.add(PayloadValue.map(entry));
        }
        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("version", PayloadValue.of(version));
        root.put("fields", PayloadValue.list(encodedFields));
        return PayloadJson.write(PayloadValue.map(root));
    }

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
            if (!(item instanceof PayloadValue.MapValue map)
                    || !map.entries().keySet().equals(java.util.Set.of(
                    "name", "label", "help", "type", "required", "maxUtf8Bytes", "allowedValues"))) {
                throw new IllegalArgumentException("invalid form field document");
            }
            var allowed = stringList(map.entries().get("allowedValues"));
            fields.add(new Field(string(map, "name"), string(map, "label"), string(map, "help"),
                    Type.valueOf(string(map, "type")), bool(map, "required"),
                    Math.toIntExact(integer(map, "maxUtf8Bytes")), allowed));
        }
        return new HumanTaskFormSchema(Math.toIntExact(version.value()), fields);
    }

    private static void requireType(Field field, PayloadValue value) {
        switch (field.type()) {
            case BOOLEAN -> { if (!(value instanceof PayloadValue.BooleanValue)) invalid(field); }
            case INTEGER -> { if (!(value instanceof PayloadValue.IntegerValue)) invalid(field); }
            case DECIMAL -> { if (!(value instanceof PayloadValue.DecimalValue
                    || value instanceof PayloadValue.IntegerValue)) invalid(field); }
            case TEXT, ENUM, DATE, DATE_TIME -> {
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
