package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticIdentifierTest {
    @Test
    void ordinaryIdentifiersStayReadableAndReferencesNeverContainText() {
        var formatted = DiagnosticIdentifier.node("mail-source_1");
        assertEquals("mail-source_1", formatted.display());
        assertTrue(formatted.reference().matches("sha256:[0-9a-f]{32}"));
        assertFalse(formatted.reference().contains("mail"));
    }

    @Test
    void controlsBidiCredentialsLocationsUrisAndLengthAreBoundedWithoutChangingTheReferenceIdentity() {
        String raw = "node\n\r\t\u202e" + "x".repeat(400);
        var formatted = DiagnosticIdentifier.node(raw);
        assertFalse(formatted.display().contains("\n"));
        assertFalse(formatted.display().contains("\r"));
        assertFalse(formatted.display().contains("\t"));
        assertTrue(formatted.display().getBytes(StandardCharsets.UTF_8).length
                <= DiagnosticIdentifier.MAX_IDENTIFIER_UTF8_BYTES);
        assertTrue(formatted.display().contains("~#"));
        assertEquals(DiagnosticIdentifier.reference(raw), formatted.reference());

        var secret = DiagnosticIdentifier.node("node-secret=password=hunter2-host=private.example");
        assertFalse(secret.display().contains("hunter2"));
        assertFalse(secret.display().contains("private.example"));
        assertTrue(secret.display().contains("redacted"));
        assertFalse(secret.reference().contains("private"));

        var location = DiagnosticIdentifier.node(
                "listener;host=private.example;profile=production;url=https://operator:pw@internal.example:8443/a");
        assertFalse(location.display().contains("private.example"));
        assertFalse(location.display().contains("production"));
        assertFalse(location.display().contains("internal.example"));
        assertFalse(location.display().contains("operator"));
        assertTrue(location.display().contains("redacted:host"));
        assertTrue(location.display().contains("redacted:profile"));
        assertEquals(DiagnosticIdentifier.reference(
                "listener;host=private.example;profile=production;url=https://operator:pw@internal.example:8443/a"),
                location.reference());
    }

    @Test
    void publicFindingConstructorRejectsRawControlsSecretsAndUnpairedLocators() {
        assertThrows(IllegalArgumentException.class, () -> new GraphAdmissionFinding(
                GraphAdmissionFinding.CONTRACT, GraphAdmissionPhase.PROPERTY_SCHEMA,
                GraphAdmissionReason.PROPERTY_TYPE_INVALID, "node\nspoof", "sha256:" + "a".repeat(32),
                "batchSize", "incident:1"));
        assertThrows(IllegalArgumentException.class, () -> new GraphAdmissionFinding(
                GraphAdmissionFinding.CONTRACT, GraphAdmissionPhase.PROPERTY_SCHEMA,
                GraphAdmissionReason.PROPERTY_TYPE_INVALID, "password=hunter2", "sha256:" + "a".repeat(32),
                "batchSize", "incident:1"));
        assertThrows(IllegalArgumentException.class, () -> new GraphAdmissionFinding(
                GraphAdmissionFinding.CONTRACT, GraphAdmissionPhase.PROPERTY_SCHEMA,
                GraphAdmissionReason.PROPERTY_TYPE_INVALID, "node", null,
                "batchSize", "incident:1"));
    }

    @Test
    void propertyNamesUseAClosedGrammarAndUnsafeInputBecomesAnOpaqueToken() {
        assertEquals("headers.1.displayName", DiagnosticIdentifier.property("headers.1.displayName").display());
        for (String raw : java.util.List.of("bad/name", "bad=name", "bad\nname", "profile=prod",
                "password=hunter2", "x".repeat(200), "bad|delimiter", "bad\u202ename")) {
            String token = DiagnosticIdentifier.property(raw).display();
            assertTrue(token.matches("property-[0-9a-f]{16}"), token);
            assertFalse(token.contains(raw), token);
            assertTrue(DiagnosticIdentifier.isSafePropertyToken(token), token);
        }
        assertThrows(IllegalArgumentException.class, () -> new GraphAdmissionFinding(
                GraphAdmissionFinding.CONTRACT, GraphAdmissionPhase.PROPERTY_SCHEMA,
                GraphAdmissionReason.INVALID_PROPERTY, "node", "sha256:" + "a".repeat(32),
                "profile=prod", "incident:1"));
    }
}
