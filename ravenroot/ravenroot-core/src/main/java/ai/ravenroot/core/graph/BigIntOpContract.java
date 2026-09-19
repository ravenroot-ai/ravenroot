package ai.ravenroot.core.graph;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.SecurityContext;

import java.util.regex.Pattern;

/** Shared static contract for authored {@code bigint-op} fields and decimal operands. */
public final class BigIntOpContract {
    public static final int MAX_DECIMAL_DIGITS = 4_096;

    private static final Pattern SIGNED_DECIMAL = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern CANONICAL_NATURAL = Pattern.compile("0|[1-9][0-9]*");

    private BigIntOpContract() {
    }

    /** Whether the value names a structurally valid top-level payload field. */
    public static boolean isFieldName(String field) {
        return field != null && !field.isBlank()
                && field.length() <= PayloadLimits.DEFAULTS.maxKeyLength()
                && field.chars().noneMatch(Character::isISOControl);
    }

    /** Whether a result may be written to this field without entering the reserved security namespace. */
    public static boolean isWritableTarget(String field) {
        return isFieldName(field) && !SecurityContext.isReservedKey(field);
    }

    /** Whether a signed decimal is executable within the finite bigint operand contract. */
    public static boolean isExecutableDecimal(String decimal) {
        if (decimal == null) return false;
        int sign = decimal.startsWith("+") || decimal.startsWith("-") ? 1 : 0;
        int digits = decimal.length() - sign;
        return digits >= 1 && digits <= MAX_DECIMAL_DIGITS && SIGNED_DECIMAL.matcher(decimal).matches();
    }

    /** Whether a non-negative decimal is canonical and within the finite bigint operand contract. */
    public static boolean isCanonicalNatural(String decimal) {
        return decimal != null && decimal.length() <= MAX_DECIMAL_DIGITS
                && CANONICAL_NATURAL.matcher(decimal).matches();
    }
}
