package ai.ravenroot.server.credential;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.server.audit.JsonStrings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wire form of a stored credential, in both directions.
 *
 * <h2>This reader accepts a secret. That is the point, and it is new</h2>
 * <p>Every other reader in this server refuses one. {@code ModelProviderWire} does, and keeps doing
 * so — a provider profile still carries a reference and never a value. This one is the single place
 * a value may enter the product from a caller, which is exactly the shape the contract requires: one
 * window, one route, and selection everywhere else.</p>
 *
 * <p>So the property that used to be "no secret ever enters" is not true any more, and the property
 * that replaces it is narrower and still worth defending: <b>a secret enters here and leaves
 * nowhere</b>. Concretely, in this file: {@link #writeCredential} has no branch that can emit the
 * value, {@link StoredCredential} has no component that can hold one, and the create response is
 * built from a {@link StoredCredential} rather than from the request.</p>
 *
 * <h2>Reading: a closed field set, kept for the reason it was built</h2>
 * <p>{@link #readCreate} refuses any member it does not know, the same closed-set discipline
 * {@code ModelProviderWire} established. The set is different — it includes {@code value} — but the
 * mechanism is unchanged, and it is what refuses a caller-proposed {@code reference}: the port must
 * mint and must never accept a name, and this is where that is
 * enforced rather than merely intended.</p>
 */
public final class UserCredentialWire {

    private UserCredentialWire() {
    }

    /**
     * Budgets for one create request.
     *
     * <p>Larger than {@code ModelProviderWire.PROFILE_LIMITS}'s per-value ceiling because one of these
     * values is a credential and some are long — a JWT-shaped token runs to a couple of kilobytes —
     * and small enough that no realistic credential is near it. Depth 2 and 16 values leave no room
     * for a structure this reader has any use for.</p>
     *
     * <p><b>The key ceiling is 64 and not 16, and that is not slack.</b> At 16 the parser refuses a
     * member named {@code credentialReference} — nineteen characters — <em>before</em>
     * {@link #CALLER_CHOSEN_NAME_FIELDS} can tell the caller that the server mints the reference.
     * The caller would be told "a payload key is too long", which is true and useless, and the one
     * refusal the server-minted-reference contract most wants to be legible would be the one that never arrives. A
     * budget that silences a security message before it is composed is the wrong budget.</p>
     */
    public static final PayloadLimits CREATE_LIMITS =
            new PayloadLimits(16 * 1024, 2, 16, 64, 8 * 1024, 64);

    /** Every member a create request may carry. Anything else is refused. */
    static final Set<String> KNOWN_FIELDS = Set.of("label", "scheme", "username", "value");

    /**
     * Members that get the pointed refusal rather than the generic one.
     *
     * <p>Diagnostics only, exactly as in {@code ModelProviderWire}: the closed field set above is the
     * control and refuses {@code "zzz"} just as firmly. This only decides which sentence the caller is
     * told, and the sentence that matters is that the server chooses the reference.</p>
     */
    static final Set<String> CALLER_CHOSEN_NAME_FIELDS =
            Set.of("reference", "id", "credentialRef", "credentialReference", "name");

    /** The longest label this route accepts. Long enough for a sentence, short enough for a selector. */
    public static final int MAX_LABEL_CHARACTERS = 120;

    /** The longest username. Generous; the ceiling exists so the column has one, not to be reached. */
    public static final int MAX_USERNAME_CHARACTERS = 256;

    /** What a caller asked to store. Holds the secret, briefly, and is never rendered. */
    public record CreateRequest(String label, CredentialScheme scheme, String username, char[] value) {

        /**
         * Redacted, and not because anything prints it today.
         *
         * <p>A record's generated {@code toString} would render the {@code char[]} as an identity hash
         * rather than as text, so nothing leaks even without this. It is overridden anyway for the
         * reason {@code AssistantConfiguration} overrides its own: so that changing the component to a
         * {@code String} later cannot quietly start printing it.</p>
         */
        @Override
        public String toString() {
            return "CreateRequest[label=" + label + ", scheme=" + scheme.wireValue()
                    + ", value=redacted]";
        }
    }

    /**
     * Reads a create request, or explains why the body is not one.
     *
     * @throws PayloadException         when the body exceeds a budget or is not well-formed JSON
     * @throws IllegalArgumentException when the body parses but is not a valid request. The message is
     *                                  never placed in a response body — the caller answers
     *                                  {@code INVALID_REQUEST} — but it is what a server-side record
     *                                  and a test read. <b>No message built here quotes a value.</b>
     */
    public static CreateRequest readCreate(byte[] body) throws PayloadException {
        PayloadValue parsed = PayloadJson.read(body, CREATE_LIMITS);
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw new IllegalArgumentException("a credential request must be a JSON object");
        }
        Map<String, PayloadValue> entries = root.entries();
        for (String field : entries.keySet()) {
            if (KNOWN_FIELDS.contains(field)) {
                continue;
            }
            if (CALLER_CHOSEN_NAME_FIELDS.contains(field)) {
                throw new IllegalArgumentException("the server mints the reference for a stored "
                        + "credential; a caller may not propose one");
            }
            throw new IllegalArgumentException("unknown credential field");
        }
        String label = text(entries, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("a stored credential needs a label");
        }
        if (label.length() > MAX_LABEL_CHARACTERS) {
            throw new IllegalArgumentException("the label is too long");
        }
        if (containsControl(label)) {
            throw new IllegalArgumentException("the label must not contain control characters");
        }
        CredentialScheme scheme = CredentialScheme.parse(text(entries, "scheme"));
        String username = text(entries, "username");
        if (scheme.carriesUsername()) {
            if (username.isBlank()) {
                throw new IllegalArgumentException("this scheme needs a username");
            }
            if (username.length() > MAX_USERNAME_CHARACTERS || containsControl(username)) {
                throw new IllegalArgumentException("the username is not usable");
            }
        } else if (!username.isEmpty()) {
            // Refused rather than dropped. A username sent with an api-key entry means the caller
            // believes it is being stored, and silently discarding it would leave them expecting an
            // authentication that cannot happen.
            throw new IllegalArgumentException("this scheme carries no username");
        }
        String value = text(entries, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a stored credential needs a value");
        }
        return new CreateRequest(label.strip(), scheme, username.strip(), value.toCharArray());
    }

    /**
     * One stored credential, as both the create response and the list render it.
     *
     * <p><b>There is no value branch, and there is no field for one.</b> Everything written here comes
     * off a {@link StoredCredential}, which has no component that could carry a secret — so this
     * method could not emit one even if a caller asked.</p>
     *
     * <p>That structural argument is checked by
     * {@code UserCredentialWireTest#nothingWrittenBackCanCarryTheValue}, which asserts the rendered
     * text and the record's component set together, and by
     * {@code UserCredentialStoreTest#theTypeReturnedToCallersHasNoComponentThatCouldHoldASecret},
     * which pins that set. <b>It does not cover every surface, and saying so is the point:</b>
     * {@code RavenrootServer#createCredential} composes the 201 by hand with the {@code char[]} still
     * in scope, so nothing structural protects that line. What protects it is
     * {@code CredentialRouteTest#assertNoTraceOf}, which searches every surface for fragments of a
     * planted canary and not only for the whole string. A masked hint can expose part of a secret
     * while leaving tests for the complete value green.</p>
     */
    public static String writeCredential(StoredCredential credential) {
        return "{\"reference\":\"" + JsonStrings.escape(credential.reference()) + "\""
                + ",\"label\":\"" + JsonStrings.escape(credential.label()) + "\""
                + ",\"scheme\":\"" + credential.scheme().wireValue() + "\""
                + ",\"username\":\"" + JsonStrings.escape(credential.username()) + "\""
                + ",\"createdAt\":\"" + JsonStrings.escape(credential.createdAt().toString()) + "\"}";
    }

    /** The caller's own credentials, in the order the store returned them. */
    public static String writeCredentials(List<StoredCredential> credentials) {
        var body = new StringBuilder("{\"credentials\":[");
        for (int index = 0; index < credentials.size(); index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append(writeCredential(credentials.get(index)));
        }
        return body.append("]}").toString();
    }

    private static String text(Map<String, PayloadValue> entries, String field) {
        PayloadValue value = entries.get(field);
        if (value == null) {
            return "";
        }
        if (!(value instanceof PayloadValue.TextValue text)) {
            // Refused rather than coerced. A number where a value was expected is a caller that
            // believes something other than what will be stored, and 'value' is the field where that
            // belief matters most. The field name is the caller's own and is safe to name; the value
            // is never repeated.
            throw new IllegalArgumentException("field '" + field + "' must be a string");
        }
        return text.value();
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
