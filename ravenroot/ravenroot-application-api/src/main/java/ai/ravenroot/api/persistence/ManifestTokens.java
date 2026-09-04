package ai.ravenroot.api.persistence;

import java.util.regex.Pattern;

/**
 * The one place a manifest field's accepted shape is decided.
 *
 * <p>Every textual field of an execution manifest is a constrained token, a lowercase hexadecimal
 * digest or a closed enum name. That is not tidiness: it is the mechanism by which a manifest cannot
 * carry a credential. A manifest has no free-form value channel at all, so there is no field into
 * which a secret value, a bearer token or an authorization snapshot could be written — a caller that
 * tried would be rejected by the record's own canonical constructor rather than by a redaction pass
 * that has to recognise the secret first.
 */
final class ManifestTokens {

    /** Identifiers, versions and mode names: printable, bounded, no whitespace and no control bytes. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@+/-]{0,199}");

    /** Enum-shaped names, so a capability vocabulary cannot smuggle arbitrary text. */
    private static final Pattern ENUM_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** Lowercase hexadecimal SHA-256, the same shape {@link GraphContentId} accepts. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private ManifestTokens() {
    }

    static String requireToken(String value, String name) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 1 to 200 characters from "
                    + "[A-Za-z0-9._:@+/-] and must not begin with a punctuation character");
        }
        return value;
    }

    static String requireOptionalToken(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null; use an empty string to record "
                    + "that this deployment composes none");
        }
        return value.isEmpty() ? value : requireToken(value, name);
    }

    static String requireEnumName(String value, String name) {
        if (value == null || !ENUM_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " must be 1 to 64 characters from [A-Z0-9_] and must begin with a letter");
        }
        return value;
    }

    static String requireSha256Hex(String value, String name) {
        if (value == null || !SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " must be exactly 64 lowercase hexadecimal characters");
        }
        return value;
    }
}
