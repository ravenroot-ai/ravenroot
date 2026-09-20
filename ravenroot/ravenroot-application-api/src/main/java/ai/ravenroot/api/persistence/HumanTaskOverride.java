package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;

/** Explicit, bounded operator intent to bypass one Human Task responder decision policy. */
public record HumanTaskOverride(int version, String reason) {
    public static final int VERSION_1 = 1;
    public static final int MAX_REASON_UTF8_BYTES = 1_024;

    /** Validates a non-empty, printable, audit-safe reason. */
    public HumanTaskOverride {
        if (version != VERSION_1) throw new IllegalArgumentException("unsupported human-task override version");
        reason = reason == null ? "" : reason.strip();
        if (reason.isEmpty()) throw new IllegalArgumentException("human-task override reason is required");
        for (int index = 0; index < reason.length(); index++) {
            char unit = reason.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == reason.length() || !Character.isLowSurrogate(reason.charAt(index))) {
                    throw new IllegalArgumentException("human-task override reason contains malformed Unicode");
                }
            } else if (Character.isLowSurrogate(unit)
                    || (Character.isISOControl(unit) && unit != '\n' && unit != '\t')) {
                throw new IllegalArgumentException("human-task override reason contains invalid control data");
            }
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > MAX_REASON_UTF8_BYTES) {
            throw new IllegalArgumentException("human-task override reason exceeds byte limit");
        }
    }

    public HumanTaskOverride(String reason) {
        this(VERSION_1, reason);
    }
}
