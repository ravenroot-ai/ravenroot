package ai.ravenroot.server.assistant;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A credential carrying a control character is refused at construction.
 *
 * <p>The first test <b>reproduces the JDK behavior that makes this dangerous</b>, so the reason for the
 * control is asserted rather than assumed.</p>
 */
class AssistantCredentialGrammarTest {

    private static final String SECRET = "sk-ant-SECRET-CANARY-12345";

    /**
     * <b>The premise, executed.</b> {@code HttpRequest.Builder.header} rejects a value containing a
     * control character with an {@code IllegalArgumentException} whose message quotes the <em>whole
     * value</em> — the credential, verbatim, inside an exception that the adapter would keep as the
     * cause of a named failure.
     *
     * <p>If a future JDK stops quoting the value this test fails, and that is correct: the control
     * below would then be defending against something that no longer happens, and whoever sees this
     * red must trigger a re-evaluation of the threat before the guard is removed.</p>
     */
    @Test
    void theJdkQuotesTheWholeHeaderValueInItsRejection() {
        for (String hostile : new String[] {SECRET + "\r\nX-Injected: 1", "sk-ant\nSECRET-tail",
                "sk-ant\rSECRET-tail", "sk-ant\0SECRET-tail"}) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> HttpRequest.newBuilder(URI.create("https://example.invalid"))
                            .header("x-api-key", hostile).build());
            assertTrue(String.valueOf(thrown.getMessage()).contains("sk-ant"),
                    () -> "this control exists because the JDK puts the credential in the message; if "
                            + "that is no longer true, re-evaluate the credential threat before removing anything: "
                            + thrown.getMessage());
        }
    }

    /**
     * <b>So the credential never reaches the request builder.</b>
     *
     * <p>Refused at construction rather than by suppressing the cause at the throw site, because a
     * credential containing CR, LF or NUL is not a usable credential in the first place — it is also a
     * malformed header and, with CRLF, a request-splitting attempt. One rejection closes both.</p>
     *
     * <p>Interior, because that is where the danger is and where trimming cannot help.</p>
     *
     * <p><b>Mutation proof.</b> Restore {@code ofNullable} to
     * {@code raw.isBlank() ? null : new AssistantCredential(raw.trim())} and every assertion here
     * reds.</p>
     */
    @Test
    void aCredentialWithAnInteriorControlCharacterIsRefusedAtConstruction() {
        for (String hostile : new String[] {SECRET + "\r\nX-Injected: 1", "sk-ant\nSECRET-tail",
                "sk-ant\rSECRET-tail", "sk-ant\0SECRET-tail", "sk-ant\007SECRET-tail"}) {
            assertNull(AssistantCredential.ofNullable(hostile),
                    () -> "a credential containing an interior control character must not be "
                            + "constructible: it would leak verbatim through the JDK's own rejection "
                            + "message");
        }
    }

    /**
     * A <em>trailing</em> control character is trimmed rather than refused, and what survives is safe.
     *
     * <p>Asserted separately because the distinction is easy to get wrong in either direction, and I
     * got it wrong first — my initial version of the test above listed trailing newlines as hostile
     * and failed against correct code. {@code trim()} strips every character at or below
     * {@code U+0020}, so a key pasted with a trailing newline or a stray NUL becomes the clean key
     * rather than being rejected. That is the behaviour to want — the common case is an operator
     * copying from a terminal, not an attack — and it is safe precisely because the value that
     * survives has no control character left in it. Trimming cannot rescue the interior case, which is
     * why that one is a refusal.</p>
     */
    @Test
    void aTrailingControlCharacterIsTrimmedRatherThanRefused() {
        for (String padded : new String[] {SECRET + "\n", SECRET + "\r", SECRET + "\0",
                " " + SECRET + " "}) {
            var credential = AssistantCredential.ofNullable(padded);
            assertNotNull(credential, "a key with surrounding whitespace must still be usable");
            assertEquals(SECRET, credential.expose(),
                    "the trimmed value must be the clean key, carrying no control character onward");
        }
    }

    /**
     * <b>A credential made only of control characters is not an empty credential — it is no
     * credential.</b>
     *
     * <p>The bug this pins is a vacuous truth inside the guard directly above, and the chain is worth
     * keeping because each link looks correct alone. {@code isBlank()} is defined by
     * {@code Character.isWhitespace}, which is {@code false} for NUL, SOH and STX but {@code true} for
     * 0x1C–0x1F — so {@code "\0"} survives the blank check while {@code ""} does not.
     * {@code trim()} then strips everything at or below {@code U+0020}, leaving {@code ""}. And
     * {@code isHeaderSafe("")} returns {@code true} <b>because its loop never executes</b>: a
     * character check over zero characters passes.</p>
     *
     * <p>The result was a constructible empty credential, which falsifies this class's own stated
     * guarantee that no code path can send a blank {@code x-api-key}. Once connected it would send an
     * empty header, take a provider 401, and report {@code PROVIDER_REJECTED} — "check the configured
     * profile" — sending an operator to hunt for a revoked key that was never revoked.</p>
     *
     * <p>{@code U+001F} is included as the control: it is whitespace to
     * {@code Character.isWhitespace}, so it takes the <em>other</em> path through the same chain --
     * refused by {@code isBlank} before trimming ever happens -- and must stay refused. Covering both
     * paths means a future "simplification" that deletes either check reds here rather than silently
     * changing which inputs are accepted.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code trimmed.isEmpty()} branch in {@code ofNullable} and
     * the NUL, SOH-STX and mixed cases red.</p>
     */
    @Test
    void aCredentialOfOnlyControlCharactersIsRefusedRatherThanBecomingEmpty() {
        for (String allControl : new String[] {"\0", "\001\002", "\0\001\002", "", "\0 \r\n", "\037"}) {
            var credential = AssistantCredential.ofNullable(allControl);
            assertNull(credential,
                    () -> "a value that trims away to nothing is no credential at all; accepting it as "
                            + "an empty one sends a blank x-api-key and reports the provider's 401 as a "
                            + "rejected key");
        }
    }

    /**
     * An ordinary key still works.
     *
     * <p>Asserted because a control that rejects everything is not a control, it is an outage.</p>
     */
    @Test
    void anOrdinaryCredentialIsStillAccepted() {
        assertNotNull(AssistantCredential.ofNullable(SECRET));
        assertNull(AssistantCredential.ofNullable("   "));
        assertNull(AssistantCredential.ofNullable(""));
        assertNull(AssistantCredential.ofNullable(null));
    }

    /** The redaction still holds for an accepted credential. */
    @Test
    void anAcceptedCredentialStillRedactsItself() {
        String rendered = AssistantCredential.ofNullable(SECRET).toString();
        assertTrue(rendered.contains("redacted"));
        assertFalse(rendered.contains(SECRET));
    }
}
