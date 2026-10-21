package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiled RFC 6901 JSON Pointer over the exposed structured ingress payload. There is no implicit
 * metadata fallback, object introspection, expression evaluation, wildcard or transport-ID alias.
 * The empty pointer selects the root. Errors disclose only the selector and fixed reason.
 */
public final class CorrelationSelector {
    /** Bounded reasons for validation and sample-preview failures. */
    public enum Reason { INVALID_POINTER, LIMIT_EXCEEDED, MISSING, NOT_SCALAR_TEXT, INVALID_ID, NOT_COUNT, NOT_MANIFEST }

    /** Safe authoring error: it deliberately contains no selected input or connector metadata. */
    public static final class Invalid extends IllegalArgumentException {
        private final String selector;
        private final Reason reason;
        private Invalid(String selector, Reason reason) {
            super("correlation selector " + selector + ": " + reason.name().toLowerCase(java.util.Locale.ROOT));
            this.selector = selector;
            this.reason = reason;
        }
        /** Configured selector; never the extracted value. */
        public String selector() { return selector; }
        /** Fixed actionable error classifier. */
        public Reason reason() { return reason; }
    }

    private final String pointer;
    private final List<String> segments;
    private final CorrelationLimits limits;

    /** Compiles and bounds the pointer before any event is processed. */
    public CorrelationSelector(String pointer, CorrelationLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        if (pointer == null) throw new Invalid("", Reason.INVALID_POINTER);
        if (pointer.length() > limits.maxSelectorCharacters()) throw new Invalid("[oversized]", Reason.LIMIT_EXCEEDED);
        this.pointer = pointer;
        if (!pointer.isEmpty() && !pointer.startsWith("/")) throw invalid(Reason.INVALID_POINTER);
        var parts = new ArrayList<String>();
        if (!pointer.isEmpty()) for (String encoded : pointer.substring(1).split("/", -1)) {
            if (parts.size() >= limits.maxSelectorSegments()) throw invalid(Reason.LIMIT_EXCEEDED);
            var decoded = new StringBuilder();
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (Character.isISOControl(c)) throw invalid(Reason.INVALID_POINTER);
                if (c != '~') decoded.append(c);
                else {
                    if (++i >= encoded.length()) throw invalid(Reason.INVALID_POINTER);
                    char escape = encoded.charAt(i);
                    if (escape != '0' && escape != '1') throw invalid(Reason.INVALID_POINTER);
                    decoded.append(escape == '0' ? '~' : '/');
                }
            }
            parts.add(decoded.toString());
        }
        this.segments = List.copyOf(parts);
    }

    /** Returns the exact selected structured value; no fallback or conversion is performed. */
    public PayloadValue select(PayloadValue input) {
        PayloadValue current = Objects.requireNonNull(input, "input");
        for (String segment : segments) {
            if (current instanceof PayloadValue.MapValue map) current = map.entries().get(segment);
            else if (current instanceof PayloadValue.ListValue list) {
                if (!segment.matches("0|[1-9][0-9]{0,9}")) throw invalid(Reason.MISSING);
                long index = Long.parseLong(segment);
                current = index < list.values().size() ? list.values().get((int) index) : null;
            } else throw invalid(Reason.MISSING);
            if (current == null) throw invalid(Reason.MISSING);
        }
        return current;
    }

    /** Selects an opaque string ID. Null, arrays, objects, booleans and numbers are never coerced. */
    public String selectId(PayloadValue input) {
        if (!(select(input) instanceof PayloadValue.TextValue text)) throw invalid(Reason.NOT_SCALAR_TEXT);
        try { return limits.requireId(text.value(), "selected ID"); }
        catch (IllegalArgumentException invalid) { throw invalid(Reason.INVALID_ID); }
    }

    /** Selects a bounded expected count, including an explicitly declared empty production. */
    public int selectCount(PayloadValue input) {
        if (!(select(input) instanceof PayloadValue.IntegerValue count)
                || count.value() < 0 || count.value() > limits.maxManifestItems()) throw invalid(Reason.NOT_COUNT);
        return Math.toIntExact(count.value());
    }

    /** Selects a distinct bounded expected manifest; duplicate identities are an error. */
    public List<String> selectManifest(PayloadValue input) {
        if (!(select(input) instanceof PayloadValue.ListValue values)
                || values.values().size() > limits.maxManifestItems()) throw invalid(Reason.NOT_MANIFEST);
        var result = new java.util.LinkedHashSet<String>();
        for (PayloadValue item : values.values()) {
            if (!(item instanceof PayloadValue.TextValue text)) throw invalid(Reason.NOT_MANIFEST);
            final String id;
            try { id = limits.requireId(text.value(), "manifest contribution"); }
            catch (IllegalArgumentException invalid) { throw invalid(Reason.NOT_MANIFEST); }
            if (!result.add(id)) throw invalid(Reason.NOT_MANIFEST);
        }
        return List.copyOf(result);
    }

    private Invalid invalid(Reason reason) { return new Invalid(pointer, reason); }
}
