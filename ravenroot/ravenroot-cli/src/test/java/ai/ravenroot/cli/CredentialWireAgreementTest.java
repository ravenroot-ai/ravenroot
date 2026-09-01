package ai.ravenroot.cli;

import ai.ravenroot.server.credential.CredentialScheme;
import ai.ravenroot.server.credential.UserCredentialWire;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The CLI's request body and the server's reader agree.
 *
 * <h2>The gap this closes, named</h2>
 * <p>{@code CredentialsCliTest} proves the CLI builds a body; {@code UserCredentialWireTest} in the
 * server module proves the reader refuses everything it should. Neither says the two <em>agree</em>,
 * and the requirement is precisely a claim about agreement: "from the CLI and the interface, both
 * <b>through the same API</b>." Two suites that pass while disagreeing on a field name is the shape that
 * claim fails in, and it would fail at a customer's first `credentials add` rather than here.</p>
 *
 * <p>No server is started. The reader is a pure function of bytes, so the honest test is to hand it
 * the exact bytes the CLI sends — which is what a live round trip would ultimately be testing anyway,
 * with a socket in the way. What this therefore does <b>not</b> establish is transport: headers,
 * authentication and status handling are {@code RemoteBackend}'s and are covered by its own tests.</p>
 */
class CredentialWireAgreementTest {

    /**
     * <b>Every scheme the CLI accepts produces a body the server's reader accepts.</b>
     *
     * <p>The body is built the way {@code RemoteBackend#addCredential} builds it, field for field and
     * in the same order, rather than by calling it — calling it would need a socket. If that method's
     * field set ever diverges from this one, {@link #theBodyShapeIsTheOneRemoteBackendActuallySends}
     * below is what notices.</p>
     */
    @Test
    void everySchemeTheCliAcceptsIsAcceptedByTheServersReader() {
        record Case(String scheme, String username, CredentialScheme expected) { }
        for (var candidate : java.util.List.of(
                new Case("api-key", "", CredentialScheme.API_KEY),
                new Case("basic", "operator", CredentialScheme.BASIC),
                new Case("oauth-token", "", CredentialScheme.OAUTH_TOKEN))) {
            byte[] body = cliBody("Claude connection", candidate.scheme(), candidate.username(),
                    "the-secret-value");

            var parsed = assertDoesNotThrow(() -> UserCredentialWire.readCreate(body),
                    () -> "the server refused the body the CLI sends for scheme " + candidate.scheme());

            assertEquals(candidate.expected(), parsed.scheme());
            assertEquals("Claude connection", parsed.label());
            assertEquals(candidate.username(), parsed.username());
            assertEquals("the-secret-value", new String(parsed.value()));
        }
    }

    /**
     * <b>An empty username on a scheme that carries none is accepted, and a non-empty one is not.</b>
     *
     * <p>This is the exact seam the two halves could have disagreed on and nothing would have caught
     * it: the CLI always puts a {@code username} member in the body, and the server refuses a
     * <em>non-empty</em> username on {@code api-key} while permitting an empty one. Had the server
     * refused the member's mere presence, every {@code credentials add --scheme api-key} would have
     * failed and both suites would still have been green.</p>
     */
    @Test
    void theAlwaysPresentUsernameMemberIsAcceptedWhenEmptyAndRefusedWhenNot() {
        assertDoesNotThrow(() -> UserCredentialWire.readCreate(
                cliBody("k", "api-key", "", "v")));

        assertThrows(IllegalArgumentException.class, () -> UserCredentialWire.readCreate(
                cliBody("k", "api-key", "someone", "v")),
                "a username on a scheme that carries none must be refused, not dropped");
    }

    /**
     * <b>The CLI sends exactly the members the server knows, and no member it would refuse.</b>
     *
     * <p>Read off {@code RemoteBackend}'s source rather than asserted from memory, in the style
     * {@code DeploymentProbeWiringTest} uses elsewhere in this repository: the property is "these four
     * put calls and no fifth", and a fifth added later is a body the closed field set refuses at
     * runtime and nothing catches at build time.</p>
     */
    @Test
    void theBodyShapeIsTheOneRemoteBackendActuallySends() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/ai/ravenroot/cli/remote/RemoteBackend.java").toAbsolutePath().normalize();
        String text = java.nio.file.Files.readString(source);
        int from = text.indexOf("public CredentialView addCredential(");
        assertEquals(true, from > 0, () -> "addCredential not found in " + source);
        String method = text.substring(from, text.indexOf("\n    }", from));

        var members = new java.util.TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("request\\.put\\(\"([a-zA-Z_]+)\"").matcher(method);
        while (matcher.find()) {
            members.add(matcher.group(1));
        }

        assertEquals(new java.util.TreeSet<>(java.util.Set.of("label", "scheme", "username", "value")),
                members, () -> "the CLI's request members must be exactly what the server's closed "
                        + "field set knows; anything else is refused by POST /v1/credentials at "
                        + "runtime. Source: " + source);
    }

    /** The body {@code RemoteBackend#addCredential} composes, in the same order it composes it. */
    private static byte[] cliBody(String label, String scheme, String username, String value) {
        var request = new LinkedHashMap<String, Object>();
        request.put("label", label);
        request.put("scheme", scheme);
        request.put("username", username);
        request.put("value", value);
        return json(request).getBytes(StandardCharsets.UTF_8);
    }

    private static String json(Map<String, Object> request) {
        var body = new StringBuilder("{");
        boolean first = true;
        for (var entry : request.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append('"').append(entry.getKey()).append("\":\"")
                    .append(String.valueOf(entry.getValue()).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return body.append('}').toString();
    }
}
