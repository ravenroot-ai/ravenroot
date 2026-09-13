package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.HumanTaskReviewPresentation;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated graph-authored projection from an incoming payload to inert review text. */
public record HumanTaskReviewDefinition(int version, String source, int maxUtf8Bytes) {
    private static final HumanTaskReviewDefinition NONE = new HumanTaskReviewDefinition(0, "", 1);

    /** Validates the closed version and payload-rooted source path. */
    public HumanTaskReviewDefinition {
        source = Objects.requireNonNull(source, "source");
        if (version == 0) {
            if (!source.isEmpty() || maxUtf8Bytes != 1) {
                throw new IllegalArgumentException("absent review definition cannot carry configuration");
            }
        } else if (version == HumanTaskReviewPresentation.VERSION_1) {
            if (!(source.equals("payload") || source.startsWith("payload."))) {
                throw new IllegalArgumentException("review text source must be payload or a payload path");
            }
            if (source.endsWith(".") || source.contains("..")) {
                throw new IllegalArgumentException("review text source contains an empty path segment");
            }
            if (maxUtf8Bytes < 1
                    || maxUtf8Bytes > HumanTaskReviewPresentation.HARD_MAX_TEXT_UTF8_BYTES) {
                throw new IllegalArgumentException("review text byte limit is outside the technical bounds");
            }
        } else {
            throw new IllegalArgumentException("unsupported human-task review presentation version");
        }
    }

    /** Returns the compatibility definition for tasks without review material. */
    public static HumanTaskReviewDefinition none() {
        return NONE;
    }

    /** Projects an exact text value without interpolation, coercion, or truncation. */
    public HumanTaskReviewPresentation presentation(Object payload) {
        if (version == 0) return HumanTaskReviewPresentation.none();
        Object selected = payload;
        if (!source.equals("payload")) {
            for (String segment : source.substring("payload.".length()).split("\\.", -1)) {
                selected = step(selected, segment);
            }
        }
        if (!(selected instanceof CharSequence text)) {
            throw new IllegalArgumentException("human-task review text source did not select text");
        }
        return HumanTaskReviewPresentation.plainText(text.toString(), maxUtf8Bytes);
    }

    private static Object step(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            if (!map.containsKey(segment) || map.get(segment) == null) {
                throw new IllegalArgumentException("human-task review text source is unavailable");
            }
            return map.get(segment);
        }
        if (current instanceof List<?> list) return indexed(list.size(), list::get, segment);
        if (current != null && current.getClass().isArray()) {
            return indexed(Array.getLength(current), index -> Array.get(current, index), segment);
        }
        throw new IllegalArgumentException("human-task review text source is unavailable");
    }

    private static Object indexed(int size, java.util.function.IntFunction<Object> reader,
                                  String segment) {
        final int index;
        try {
            index = Integer.parseInt(segment);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("human-task review text source is unavailable");
        }
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("human-task review text source is unavailable");
        }
        Object value = reader.apply(index);
        if (value == null) {
            throw new IllegalArgumentException("human-task review text source is unavailable");
        }
        return value;
    }
}
