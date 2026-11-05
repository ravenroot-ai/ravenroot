package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadLimits;
import java.util.Objects;

/** Typed, bounded correlation selection and retention budgets supplied by runtime composition.
 * @param maxIdCharacters finite UTF-16 character ceiling for opaque business, contribution and round identifiers
 * @param maxSelectorCharacters finite character ceiling for an authored JSON Pointer
 * @param maxSelectorSegments maximum traversed segments in an authored JSON Pointer
 * @param maxManifestItems maximum distinct members extracted from an expectation manifest
 * @param input operator-owned bounds for the actual exposed ingress payload
 */
public record CorrelationLimits(int maxIdCharacters, int maxSelectorCharacters, int maxSelectorSegments,
                                int maxManifestItems, PayloadLimits input) {
    /** Shipped finite defaults; embedders may tighten or raise them within the safety ceilings. */
    public static final CorrelationLimits DEFAULTS = new CorrelationLimits(256, 1024, 32, 1024,
            PayloadLimits.DEFAULTS);

    /** Rejects configurations that would make authoring or extraction unbounded. */
    public CorrelationLimits {
        if (maxIdCharacters < 1 || maxIdCharacters > 4096 || maxSelectorCharacters < 1
                || maxSelectorCharacters > 16384 || maxSelectorSegments < 1 || maxSelectorSegments > 256
                || maxManifestItems < 1 || maxManifestItems > 10000)
            throw new IllegalArgumentException("correlation limits exceed supported bounds");
        Objects.requireNonNull(input, "input");
    }

    /** Validates an opaque application/contribution/round key without coercion or normalization.
 * @param value candidate opaque identity; values are never echoed in a refusal
 * @param field safe diagnostic field name; the rejected identifier value is never included
 * @return validated original text without coercion, normalization or diagnostic value disclosure
 */
    public String requireId(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxIdCharacters
                || text.codePoints().anyMatch(Character::isISOControl) || !wellFormed(text))
            throw new IllegalArgumentException("invalid " + field + ": expected bounded nonblank text without controls");
        return text;
    }

    private static boolean wellFormed(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }
}
