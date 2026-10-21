package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadLimits;
import java.util.Objects;

/** Typed, bounded correlation selection and retention budgets supplied by runtime composition. */
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

    /** Validates an opaque application/contribution/round key without coercion or normalization. */
    public String requireId(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxIdCharacters
                || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid " + field + ": expected bounded nonblank text without controls");
        return text;
    }
}
