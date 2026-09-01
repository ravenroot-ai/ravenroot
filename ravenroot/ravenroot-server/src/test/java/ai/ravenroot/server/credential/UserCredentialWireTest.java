package ai.ravenroot.server.credential;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may be sent to the credential route and what may come back.
 *
 * <p>The reader here <b>accepts</b> a secret, while nothing sends one back. The two halves are tested
 * together on purpose: the file that admits a value is the
 * file whose write side must be proved unable to emit it.</p>
 */
class UserCredentialWireTest {

    private static final String PLANTED = "sk-ant-planted-canary-577";

    /**
     * <b>A caller-proposed reference is refused, not ignored.</b>
     *
     * <p>The server-minted-reference contract states: "the port must mint the
     * reference and must never accept a caller-chosen name. Otherwise it is a cross-tenant clobber
     * primitive." Refused rather than ignored for the reason {@code ModelProviderWire} already gives
     * about tolerant readers — an ignored field is a caller who believes something that is not
     * happening.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code "reference"} to {@link UserCredentialWire#KNOWN_FIELDS}
     * and this reds on the first assertion; remove the {@code CALLER_CHOSEN_NAME_FIELDS} branch alone
     * and it still reds, on the message, because the generic refusal no longer says who chooses.</p>
     */
    @Test
    void aCallerMayNotProposeTheReference() throws Exception {
        for (String field : UserCredentialWire.CALLER_CHOSEN_NAME_FIELDS) {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> UserCredentialWire.readCreate(body("{\"label\":\"L\",\"scheme\":\"api-key\","
                            + "\"value\":\"" + PLANTED + "\",\"" + field + "\":\"chosen-by-me\"}")),
                    () -> "field '" + field + "' proposed a name and was accepted");
            assertTrue(refused.getMessage().contains("mints"),
                    () -> "the refusal must say who chooses the reference: " + refused.getMessage());
            assertFalse(refused.getMessage().contains(PLANTED),
                    "a refusal must not quote the credential it declined to store");
        }
    }

    /** The closed field set is the control; an unknown member is refused as firmly as a secret-like one. */
    @Test
    void anUnknownFieldIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> UserCredentialWire.readCreate(
                        body("{\"label\":\"L\",\"scheme\":\"api-key\",\"value\":\"v\",\"zzz\":\"1\"}")));
        assertTrue(refused.getMessage().contains("unknown"), refused.getMessage());
    }

    /**
     * <b>Every refusal this reader can raise is free of the value.</b>
     *
     * <p>A refusal message is not returned to the caller — the route answers {@code INVALID_REQUEST}
     * — but it is what a server-side record and a stack trace carry, which is exactly the surface
     * no-secret-output contract covers. This is exhaustive over the branches rather than sampled because a
     * message added later is the one that would leak.</p>
     */
    @Test
    void noRefusalMessageEverQuotesTheValue() {
        var malformed = java.util.List.of(
                "{}",
                "{\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"" + "x".repeat(200) + "\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"L\",\"scheme\":\"nonsense\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"L\",\"scheme\":\"basic\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"L\",\"scheme\":\"api-key\",\"username\":\"u\",\"value\":\"" + PLANTED + "\"}",
                "{\"label\":\"L\",\"scheme\":\"api-key\"}",
                "{\"label\":\"L\",\"scheme\":\"api-key\",\"value\":123}",
                "[]");
        for (String candidate : malformed) {
            try {
                UserCredentialWire.readCreate(body(candidate));
                throw new AssertionError("expected a refusal for " + candidate);
            } catch (Exception refused) {
                assertFalse(String.valueOf(refused.getMessage()).contains(PLANTED),
                        () -> "a refusal quoted the credential: " + refused.getMessage());
            }
        }
    }

    /** A scheme that carries no username refuses one rather than dropping it silently. */
    @Test
    void aUsernameIsRequiredExactlyWhereTheSchemeCarriesOne() throws Exception {
        var basic = UserCredentialWire.readCreate(body(
                "{\"label\":\"db\",\"scheme\":\"basic\",\"username\":\"operator\",\"value\":\"p\"}"));
        assertEquals(CredentialScheme.BASIC, basic.scheme());
        assertEquals("operator", basic.username());

        assertThrows(IllegalArgumentException.class, () -> UserCredentialWire.readCreate(
                body("{\"label\":\"db\",\"scheme\":\"basic\",\"value\":\"p\"}")));
        assertThrows(IllegalArgumentException.class, () -> UserCredentialWire.readCreate(
                body("{\"label\":\"k\",\"scheme\":\"api-key\",\"username\":\"u\",\"value\":\"p\"}")));
    }

    /**
     * <b>The rendered form has no place to put a value.</b>
     *
     * <p><b>Mutation proof.</b> Give {@link StoredCredential} a {@code value} component and append it
     * in {@link UserCredentialWire#writeCredential}; this reds. The assertion is against the rendered
     * text and against the record's components together, so neither a new field nor a new
     * interpolation of an existing one passes.</p>
     */
    @Test
    void nothingWrittenBackCanCarryTheValue() {
        var stored = new StoredCredential("rrc_0123456789abcdef0123456789abcdef",
                "Claude connection", CredentialScheme.API_KEY, "", Instant.parse("2026-08-28T10:00:00Z"));

        String rendered = UserCredentialWire.writeCredential(stored);

        assertTrue(rendered.contains("\"reference\":\"rrc_0123456789abcdef0123456789abcdef\""), rendered);
        assertTrue(rendered.contains("\"label\":\"Claude connection\""), rendered);
        assertFalse(rendered.contains("value"),
                () -> "the rendered credential must not even carry the word: " + rendered);
        assertFalse(rendered.contains("secret"), rendered);
    }

    /** A label an author wrote is caller text, so it is escaped like every other caller string. */
    @Test
    void aLabelIsEscapedOnTheWayOut() {
        var stored = new StoredCredential("rrc_0123456789abcdef0123456789abcdef",
                "quote \" and \\ backslash", CredentialScheme.API_KEY, "", Instant.EPOCH);

        String rendered = UserCredentialWire.writeCredential(stored);

        assertTrue(rendered.contains("\\\""), rendered);
        assertTrue(rendered.contains("\\\\"), rendered);
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
