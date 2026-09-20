package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable presentation choice pinned with one durable Human Task. */
public record HumanTaskPresentation(HumanTaskPresentationKind kind, int version,
                                    String profileId, int profileVersion, String formSchema,
                                    String schemaDigest) {
    public static final int VERSION_1 = 1;
    public static final int MAX_PROFILE_ID_UTF8_BYTES = 256;
    public static final int MAX_FORM_SCHEMA_UTF8_BYTES = 64 * 1024;

    public HumanTaskPresentation {
        kind = Objects.requireNonNull(kind, "kind");
        profileId = bounded(profileId, "profileId", MAX_PROFILE_ID_UTF8_BYTES);
        formSchema = bounded(formSchema, "formSchema", MAX_FORM_SCHEMA_UTF8_BYTES);
        schemaDigest = schemaDigest == null ? "" : schemaDigest;
        if (kind == HumanTaskPresentationKind.CLASSIC) {
            if (version != 0 || !profileId.isEmpty() || profileVersion != 0
                    || !formSchema.isEmpty() || !schemaDigest.isEmpty()) {
                throw new IllegalArgumentException("classic presentation cannot carry pinned fields");
            }
        } else {
            if (version != VERSION_1) throw new IllegalArgumentException("unsupported presentation version");
            if (kind == HumanTaskPresentationKind.FORM) {
                if (!profileId.isEmpty() || profileVersion != 0 || formSchema.isEmpty()) {
                    throw new IllegalArgumentException("form presentation requires only a closed schema");
                }
                HumanTaskFormSchema.decode(formSchema);
            } else if (kind == HumanTaskPresentationKind.CUSTOM
                    || kind == HumanTaskPresentationKind.EXTERNAL) {
                if (profileId.isEmpty() || profileVersion < 1 || !formSchema.isEmpty()) {
                    throw new IllegalArgumentException("registered presentation requires opaque profile id and version");
                }
            } else if (!profileId.isEmpty() || profileVersion != 0 || !formSchema.isEmpty()) {
                throw new IllegalArgumentException("confirmation presentation cannot carry profile fields");
            }
            String expected = digest(formSchema);
            if (!schemaDigest.equals(expected)) {
                throw new IllegalArgumentException("presentation schema digest does not match pinned schema");
            }
        }
    }

    public static HumanTaskPresentation classic() {
        return new HumanTaskPresentation(HumanTaskPresentationKind.CLASSIC, 0, "", 0, "", "");
    }

    public static HumanTaskPresentation confirmation() {
        return new HumanTaskPresentation(HumanTaskPresentationKind.CONFIRMATION, VERSION_1,
                "", 0, "", digest(""));
    }

    public static HumanTaskPresentation form(HumanTaskFormSchema schema) {
        String encoded = Objects.requireNonNull(schema, "schema").encode();
        return new HumanTaskPresentation(HumanTaskPresentationKind.FORM, VERSION_1,
                "", 0, encoded, digest(encoded));
    }

    public static HumanTaskPresentation registered(HumanTaskPresentationKind kind,
                                                   String profileId, int profileVersion) {
        if (kind != HumanTaskPresentationKind.CUSTOM && kind != HumanTaskPresentationKind.EXTERNAL) {
            throw new IllegalArgumentException("registered presentation must be custom or external");
        }
        return new HumanTaskPresentation(kind, VERSION_1, profileId, profileVersion, "", digest(""));
    }

    public static HumanTaskPresentation compatibility(HumanTaskConfirmationPresentation confirmation) {
        return confirmation != null && confirmation.embedded() ? confirmation() : classic();
    }

    public HumanTaskFormSchema decodedFormSchema() {
        if (kind != HumanTaskPresentationKind.FORM) {
            throw new IllegalStateException("presentation is not a form");
        }
        return HumanTaskFormSchema.decode(formSchema);
    }

    private static String bounded(String value, String name, int maximum) {
        value = value == null ? "" : value;
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds byte limit");
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isISOControl(unit) && unit != '\n' && unit != '\t' && unit != '\r') {
                throw new IllegalArgumentException(name + " contains control data");
            }
        }
        return value;
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
