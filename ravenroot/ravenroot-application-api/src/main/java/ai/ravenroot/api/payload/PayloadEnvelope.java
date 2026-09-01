package ai.ravenroot.api.payload;

import java.util.Map;
import java.util.Objects;

/**
 * A structured payload together with the schema and version that describe it (API-01).
 *
 * <h2>Two versions, because they answer different questions</h2>
 * <ul>
 *   <li>{@link #contract()} is <b>Ravenroot's</b> version of the payload representation itself — what
 *       {@code kind}, {@code schema} and {@code value} mean and how they encode. It is
 *       {@value #CONTRACT} in this build. A caller declaring anything else is refused: a value this
 *       build cannot claim to understand must not reach a node under a label that says it was
 *       understood.</li>
 *   <li>{@link #schema()} and {@link #schemaVersion()} are the <b>caller's</b> declaration of what the
 *       payload means to its own domain. Ravenroot does not interpret them and deliberately owns no
 *       schema registry — validating a caller's domain schema would make the engine the authority on
 *       a contract it does not own. They are carried, echoed and audited, so producer and consumer can
 *       agree on a version without Ravenroot arbitrating it.</li>
 * </ul>
 *
 * <h2>The kind is checked, not derived</h2>
 * <p>{@link #kind()} is what the caller says the payload is. It is verified against the value at
 * construction, so a client whose payload silently changes shape — a map that becomes a list because
 * a serializer collapsed a single-entry collection — is refused at ingress instead of discovered by a
 * node that assumed otherwise.</p>
 *
 * <h2>Compatibility rule</h2>
 * <p>An unknown {@code contract} is rejected. An unknown <em>member</em> inside a known contract is
 * ignored. That pairing is what lets the envelope grow additively without either side guessing:
 * additions are safe for old readers, and a semantic change requires a version a old reader will
 * refuse rather than misread.</p>
 * @param contract Ravenroot payload-representation version; must equal {@link #CONTRACT}
 * @param schema caller-owned domain schema name, normalized when omitted
 * @param schemaVersion caller-owned version of that schema, normalized when omitted
 * @param kind declared structural shape, which must match {@code value}
 * @param value normalized structured payload data carried to the execution runtime
 */
public record PayloadEnvelope(String contract, String schema, String schemaVersion, PayloadKind kind,
                              PayloadValue value) {

    /** The payload representation version this build implements. */
    public static final String CONTRACT = "ravenroot.payload/1";

    /** The schema a caller that declares none is treated as having declared. */
    public static final String UNDECLARED_SCHEMA = "ravenroot.unspecified";

    /**
     * The schema of the pre-API-01 textual payload.
     *
     * <p>Naming it is the whole compatibility story: a legacy caller is not schemaless, it is a
     * caller of a schema that was never written down. Now it is, so a node can tell "the client sent
     * text" from "the client sent a structured payload that happens to be text".</p>
     */
    public static final String LEGACY_TEXT_SCHEMA = "ravenroot.text";

    public static final String DEFAULT_SCHEMA_VERSION = "1";

    private static final int MAX_LABEL_LENGTH = 128;

/**
 * Validates representation compatibility, label grammar and the declared structural shape.
 */
    public PayloadEnvelope {
        Objects.requireNonNull(contract, "contract");
        if (!CONTRACT.equals(contract)) {
            throw PayloadRejection.unsupportedContractVersion(contract);
        }
        schema = label(schema, UNDECLARED_SCHEMA);
        schemaVersion = label(schemaVersion, DEFAULT_SCHEMA_VERSION);
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(kind, "kind");
        if (kind != value.kind()) {
            throw PayloadRejection.kindMismatch(kind, value.kind());
        }
    }

/**
 * An envelope for a value whose schema the caller did not name.
 * @param value structured value whose kind supplies the envelope's declared kind
 * @return an envelope using the unspecified schema and default schema version
 */
    public static PayloadEnvelope of(PayloadValue value) {
        return new PayloadEnvelope(CONTRACT, UNDECLARED_SCHEMA, DEFAULT_SCHEMA_VERSION, value.kind(), value);
    }

/**
 * Creates an envelope with caller-owned schema metadata.
 * @param schema domain schema name to carry without interpreting
 * @param schemaVersion version label associated with that domain schema
 * @param value structured value whose kind supplies the envelope's declared kind
 * @return an API-01 envelope validated against this build's representation contract
 */
    public static PayloadEnvelope of(String schema, String schemaVersion, PayloadValue value) {
        return new PayloadEnvelope(CONTRACT, schema, schemaVersion, value.kind(), value);
    }

/**
 * The envelope a pre-API-01 textual payload is exactly equivalent to.
 * @param text legacy text; {@code null} is represented as the empty string
 * @return the canonical envelope used for pre-API-01 textual input
 */
    public static PayloadEnvelope legacyText(String text) {
        return new PayloadEnvelope(CONTRACT, LEGACY_TEXT_SCHEMA, DEFAULT_SCHEMA_VERSION,
                PayloadKind.SCALAR, PayloadValue.of(text == null ? "" : text));
    }

/**
 * The interior value the engine carries. Exactly what the pre-API-01 path handed to a node.
 * @return the Java object represented by the contained structured value
 */
    public Object toJava() {
        return value.toJava();
    }

/**
 * Serializes this envelope using Ravenroot's bounded JSON representation.
 * @return canonical JSON text for this validated envelope
 */
    public String toJson() {
        return PayloadJson.writeEnvelope(this);
    }

    /**
     * Builds an envelope from an already-decoded envelope object.
     *
     * <p>The entry point for a transport that decoded a larger document and is holding the envelope as
     * one member of it. {@code limits} is re-enforced on the value, because the outer decode ran under
     * the transport's budgets and those are necessarily looser than the payload's.</p>
     *
     * @throws PayloadException when the value is not an envelope object, the contract is unknown, or a
     *                          declared member has the wrong type
 * @param envelope decoded structured value expected to be an object with envelope members
 * @param limits budgets re-enforced before the inner value enters the runtime
 * @return a validated envelope reconstructed from the decoded object
     */
    public static PayloadEnvelope fromValue(PayloadValue envelope, PayloadLimits limits) {
        if (!(envelope instanceof PayloadValue.MapValue members)) {
            throw PayloadRejection.unsupportedType();
        }
        return fromMembers(members.entries(), limits);
    }

    /**
     * Builds an envelope from decoded members, ignoring members this build does not know.
     *
     * @throws PayloadException when the contract is unknown, or a declared field has the wrong type
     */
    static PayloadEnvelope fromMembers(Map<String, PayloadValue> members, PayloadLimits limits) {
        String contract = text(members.get("contract"), CONTRACT);
        if (!CONTRACT.equals(contract)) {
            throw PayloadRejection.unsupportedContractVersion(contract);
        }
        PayloadValue value = members.get("value");
        if (value == null) {
            throw PayloadRejection.malformed(0, null);
        }
        limits.enforce(value);
        PayloadKind declared = kind(members.get("kind"), value.kind());
        return new PayloadEnvelope(contract, text(members.get("schema"), UNDECLARED_SCHEMA),
                text(members.get("schemaVersion"), DEFAULT_SCHEMA_VERSION), declared, value);
    }

    private static PayloadKind kind(PayloadValue declared, PayloadKind fallback) {
        if (declared == null) {
            return fallback;
        }
        if (!(declared instanceof PayloadValue.TextValue text)) {
            throw PayloadRejection.unsupportedType();
        }
        try {
            return PayloadKind.valueOf(text.value());
        } catch (IllegalArgumentException unknown) {
            throw PayloadRejection.kindMismatch(fallback, fallback);
        }
    }

    private static String text(PayloadValue value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof PayloadValue.TextValue text)) {
            throw PayloadRejection.unsupportedType();
        }
        return text.value();
    }

    /**
     * Bounds and constrains a caller-supplied label.
     *
     * <p>{@code schema} and {@code schemaVersion} are echoed in responses and written to audit
     * records, which makes them untrusted text that reaches two rendering contexts. They are therefore
     * restricted to a token grammar rather than merely escaped: escaping addresses injection into the
     * context you thought of, and a grammar addresses the one you did not.</p>
     */
    private static String label(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.length() > MAX_LABEL_LENGTH) {
            throw PayloadRejection.keyTooLong(MAX_LABEL_LENGTH);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-'
                    || character == ':' || character == '+' || character == '/';
            if (!allowed) {
                throw PayloadRejection.unsupportedType();
            }
        }
        return value;
    }
}
