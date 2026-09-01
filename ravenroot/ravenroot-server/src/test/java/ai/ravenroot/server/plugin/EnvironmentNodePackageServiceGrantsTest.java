package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.security.nodepackage.TenantCredentialResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator surface for service grants. Two properties define its safety boundary: an absent
 * variable concedes nothing, and a variable an operator got wrong stops
 * startup instead of quietly becoming an absent variable.
 */
class EnvironmentNodePackageServiceGrantsTest {

    /** The real package id of the shipped object-storage bundle, from {@code StorageNodePackage.id()}. */
    private static final String STORAGE_PACKAGE_ID = "ai.ravenroot.extensions.storage";
    private static final String STORAGE_VARIABLE = "RAVENROOT_NODE_PACKAGE_SERVICES_"
            + "61692E726176656E726F6F742E657874656E73696F6E732E73746F72616765";

    /** A grant with no clear-text path: the SigV4 union applies and nothing is refused. */
    private static final Set<NodePackageCapability> EGRESS_ONLY =
            Set.of(NodePackageCapability.OUTBOUND_HTTP);

    private static final TenantCredentialResolver NO_CREDENTIALS =
            (packageId, tenantId, reference) -> Optional.empty();

    @Test
    void anEmptyEnvironmentConcedesNothingAndIsTheSameObjectAsNoRegistryAtAll() {
        // No grant is ever implicit. Identity against empty() is the
        // strongest available statement of "this composes what registerWithInventory already passed
        // when it took no registry at all", so the shipped default cannot drift by construction.
        NodePackageServiceRegistry registry =
                EnvironmentNodePackageServiceGrants.fromEnvironment(Map.of(), NO_CREDENTIALS);

        assertSame(NodePackageServiceRegistry.empty(), registry);
        assertTrue(registry.capabilitiesFor(STORAGE_PACKAGE_ID).isEmpty());
    }

    @Test
    void anUnrelatedEnvironmentIsNotMistakenForAGrant() {
        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of("RAVENROOT_ENABLED_PLUGINS", STORAGE_PACKAGE_ID,
                        "RAVENROOT_OBJECT_STORAGE_PROFILE_6D61696E", "irrelevant"),
                NO_CREDENTIALS);

        assertSame(NodePackageServiceRegistry.empty(), registry);
    }

    @Test
    void aVariableSetToEmptyReadsAsUnsetRatherThanAsAMalformedGrant() {
        // Compose delivers optional settings as `NAME: ${NAME:-}`, i.e. present and empty. An empty
        // value states nothing, so it grants nothing -- and it must not stop startup either.
        assertSame(NodePackageServiceRegistry.empty(),
                EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, ""), NO_CREDENTIALS));
        assertSame(NodePackageServiceRegistry.empty(),
                EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, "   "), NO_CREDENTIALS));
    }

    @Test
    void theVariableNameIsTheOneDerivationEveryOtherResolverUses() {
        assertEquals(STORAGE_VARIABLE,
                EnvironmentNodePackageServiceGrants.environmentVariableName(STORAGE_PACKAGE_ID));
    }

    @Test
    void aGrantConcedesExactlyTheCapabilitiesItNames() {
        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode("""
                        {"capabilities":["outbound-http","credential-resolution"],
                         "origins":[{"scheme":"https","host":"s3.example.com","port":443}],
                         "httpMethods":["GET","PUT"],
                         "requestHeaders":["content-type"],
                         "responseHeaders":["etag"]}""")),
                NO_CREDENTIALS);

        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP,
                        NodePackageCapability.CREDENTIAL_RESOLUTION),
                registry.capabilitiesFor(STORAGE_PACKAGE_ID));
        assertTrue(registry.capabilitiesFor("some.other.package").isEmpty(),
                "a grant is keyed to one exact package id and reaches no other");
    }

    @Test
    void omittedCeilingsAreAcceptedAndDeclaredOnesAreValidatedByThePolicy() {
        // The minimal grant an operator can write. It must be enough, because activation only ever
        // asks which capabilities were conceded -- every ceiling has a policy default behind it.
        NodePackageServiceRegistry minimal = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode("{\"capabilities\":[\"outbound-http\"]}")),
                NO_CREDENTIALS);
        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP),
                minimal.capabilitiesFor(STORAGE_PACKAGE_ID));

        // And a ceiling the policy refuses is refused here, without this class restating the rule.
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],"limits":{"maxResponseBytes":0}}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void nonCanonicalBase64IsRefusedRatherThanDecodedLeniently() {
        // "e30=" and "e31=" both decode to the two bytes "{}": the final character's low bits are
        // padding the encoder must zero and the decoder does not check. Refusing the second is what
        // makes one grant have exactly one spelling; JDK Base64 accepts both without complaint.
        assertEquals("e30=", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{}", new String(Base64.getDecoder().decode("e31="), StandardCharsets.UTF_8));

        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, "e31="), NO_CREDENTIALS));
        assertEquals(STORAGE_VARIABLE, refused.variableName());
        assertTrue(refused.getMessage().contains("canonical"), refused::getMessage);
    }

    @Test
    void textThatIsNotBase64AtAllIsRefusedWithTheVariableNamed() {
        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, "{\"capabilities\":[\"outbound-http\"]}"),
                        NO_CREDENTIALS));
        assertEquals(STORAGE_VARIABLE, refused.variableName());
    }

    @Test
    void anUnknownMemberIsRefusedInsteadOfSilentlyIgnored() {
        // The failure mode this rejects: an operator writes "capability" for "capabilities", or
        // "origin" for "origins", and gets a narrower grant than they believe they wrote.
        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],"origin":"https://s3.example.com"}""")),
                        NO_CREDENTIALS));
        assertTrue(refused.getMessage().contains("origin"), refused::getMessage);
    }

    @Test
    void anUnknownMemberInsideLimitsIsRefusedToo() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],"limits":{"maxResponseByte":4096}}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void aJsonNullIsRefusedAsAGrantRejectionRatherThanEscapingAsANullPointer() {
        // JSON null projects to a Java null. It must reach the field check that can name the member,
        // not a copyOf() that answers with a NullPointerException nobody can act on.
        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("{\"capabilities\":[null]}")), NO_CREDENTIALS));
        assertTrue(refused.getMessage().contains("capabilities"), refused::getMessage);

        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],
                                 "origins":[{"scheme":"https","host":null,"port":443}]}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void anUnknownCapabilityNameIsRefusedRatherThanDroppedFromTheGrant() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("{\"capabilities\":[\"outbound-smtp\"]}")),
                        NO_CREDENTIALS));
    }

    @Test
    void aGrantThatConcedesNothingIsAMistakeRatherThanAWayToWriteNoGrant() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("{\"capabilities\":[]}")), NO_CREDENTIALS));
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("{\"origins\":[]}")), NO_CREDENTIALS));
    }

    @Test
    void aNonCanonicalHexSuffixIsRefusedRatherThanKeyingAGrantNobodyWrote() {
        String lowerCaseSuffix = STORAGE_VARIABLE.toLowerCase(java.util.Locale.ROOT)
                .replace("ravenroot_node_package_services_", "RAVENROOT_NODE_PACKAGE_SERVICES_");

        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(lowerCaseSuffix, encode("{\"capabilities\":[\"outbound-http\"]}")),
                        NO_CREDENTIALS));
        assertEquals(lowerCaseSuffix, refused.variableName());
    }

    @Test
    void aSuffixThatDecodesToSomethingThatIsNotAPackageIdIsRefused() {
        // "Not A Package Id" -- valid hex, valid UTF-8, and not a lawful package id. The registry's
        // own validation is what refuses it; this asserts the refusal arrives with the variable
        // attached rather than as a bare IllegalArgumentException nobody can act on.
        String suffix = java.util.HexFormat.of().withUpperCase()
                .formatHex("Not A Package Id".getBytes(StandardCharsets.UTF_8));
        String variable = "RAVENROOT_NODE_PACKAGE_SERVICES_" + suffix;

        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(variable, encode("{\"capabilities\":[\"outbound-http\"]}")),
                        NO_CREDENTIALS));
        assertEquals(variable, refused.variableName());
    }

    @Test
    void aCredentialHeaderThatCouldAlterTransportAuthorityIsRefusedByThePolicyItIsHandedTo() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],
                                 "credentialBindings":[{"bindingId":"b",
                                   "origin":{"scheme":"https","host":"s3.example.com","port":443},
                                   "headerName":"Host","prefix":""}]}""")),
                        NO_CREDENTIALS));
    }

    // ---- C2: a credential binding may not target a cleartext origin -------------------------------

    @Test
    void aCredentialBindingToACleartextOriginIsRefused() {
        // This surface can express the dangerous binding: an HTTP origin plus an Authorization header
        // ships a deployment secret in the clear, so it must be refused.
        for (String scheme : new String[] {"http", "ws"}) {
            NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                    () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                            Map.of(STORAGE_VARIABLE, encode("""
                                    {"capabilities":["outbound-http"],
                                     "credentialBindings":[{"bindingId":"api",
                                       "origin":{"scheme":"%s","host":"api.example.com","port":80},
                                       "headerName":"Authorization","prefix":"Bearer "}]}""".formatted(scheme))),
                            NO_CREDENTIALS),
                    () -> "scheme " + scheme + " must not carry a credential binding");
            assertEquals(STORAGE_VARIABLE, refused.variableName());
            assertTrue(refused.getMessage().contains("credentialBindings.origin"), refused::getMessage);
        }
    }

    @Test
    void loopbackIsNotExceptedFromTheCleartextRefusal() {
        // Recorded as a decision, not an oversight: a literal-loopback exception can be added later
        // against a real deployment, while withdrawing one would break a grant already written.
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],
                                 "credentialBindings":[{"bindingId":"sidecar",
                                   "origin":{"scheme":"http","host":"127.0.0.1","port":8080},
                                   "headerName":"X-Api-Key","prefix":""}]}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void anEncryptedCredentialBindingIsAccepted() {
        // The other half of the rule: https and wss still compose, so the refusal is a scheme check
        // and not a credential binding that stopped working.
        for (String scheme : new String[] {"https", "wss"}) {
            NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                    Map.of(STORAGE_VARIABLE, encode("""
                            {"capabilities":["outbound-http"],
                             "credentialBindings":[{"bindingId":"api",
                               "origin":{"scheme":"%s","host":"api.example.com","port":443},
                               "headerName":"X-Api-Key","prefix":""}]}""".formatted(scheme))),
                    NO_CREDENTIALS);
            assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP),
                    registry.capabilitiesFor(STORAGE_PACKAGE_ID));
        }
    }

    @Test
    void aPlainDestinationOriginMayStillBeCleartextBecauseItCarriesNoCredential() {
        // The rule is about credential bindings, not about egress. Narrowing `origins` too would be
        // a different (and unasked) policy change, and this pins that it did not happen by accident.
        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode("""
                        {"capabilities":["outbound-http"],
                         "origins":[{"scheme":"http","host":"internal.example.com","port":80}]}""")),
                NO_CREDENTIALS);

        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP),
                registry.capabilitiesFor(STORAGE_PACKAGE_ID));
    }

    @Test
    void aSigV4BindingForAServiceOtherThanS3IsRefusedByThePolicyItIsHandedTo() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http"],
                                 "awsSigV4Bindings":[{"bindingId":"b",
                                   "origin":{"scheme":"https","host":"kinesis.example.com","port":443},
                                   "credentialReference":"r","region":"eu-west-1","service":"kinesis"}]}""")),
                        NO_CREDENTIALS));
    }

    // ---- C1: the admissible-reference list, the only boundary credential-resolution can have -------

    @Test
    void withNoReferenceListTheResolverIsHandedThroughUntouched() {
        // The pin that matters for "absent grants nothing new": not merely equivalent behaviour, the
        // same object. There is no restricted view to get wrong when no list was written.
        assertSame(NO_CREDENTIALS, EnvironmentNodePackageServiceGrants.credentialScope(
                STORAGE_VARIABLE, null, Map.of(), EnumSet.allOf(NodePackageCapability.class),
                NO_CREDENTIALS));
    }

    @Test
    void aReferenceListAdmitsWhatItNamesAndNothingElse() {
        TenantCredentialResolver scoped = EnvironmentNodePackageServiceGrants.credentialScope(
                STORAGE_VARIABLE, List.of("allowed"), Map.of(), EGRESS_ONLY, recording());

        assertTrue(scoped.resolve("pkg", "tenant", "allowed").isPresent());
        assertTrue(scoped.resolve("pkg", "tenant", "denied").isEmpty());
        assertTrue(scoped.resolve("pkg", "tenant", null).isEmpty());
    }

    @Test
    void aReferenceTheOperatorBoundForSigningIsAdmittedWithoutBeingRepeatedInTheList() {
        // Otherwise adding the list would silently break the operator's own SigV4 binding at runtime.
        TenantCredentialResolver scoped = EnvironmentNodePackageServiceGrants.credentialScope(
                STORAGE_VARIABLE, List.of("api-key"), Map.of("storage", "storage-key"), EGRESS_ONLY,
                recording());

        assertTrue(scoped.resolve("pkg", "tenant", "api-key").isPresent());
        assertTrue(scoped.resolve("pkg", "tenant", "storage-key").isPresent());
        assertTrue(scoped.resolve("pkg", "tenant", "anything-else").isEmpty());
    }

    @Test
    void whitespaceAroundAReferenceIsStrippedOnBothSidesOfTheComparison() {
        // ManagedNodePackageServices.safeReference strips before asking the resolver, and core's
        // safeToken strips what it stores for a signing binding. A list that kept the operator's
        // spacing would match neither, producing the mute CREDENTIAL_UNAVAILABLE the union exists to
        // avoid -- a closed failure, but an unexplained one.
        TenantCredentialResolver scoped = EnvironmentNodePackageServiceGrants.credentialScope(
                STORAGE_VARIABLE, List.of("  api-key  "), Map.of("storage", "storage-key"), EGRESS_ONLY,
                recording());

        assertTrue(scoped.resolve("pkg", "tenant", "api-key").isPresent());
        assertTrue(scoped.resolve("pkg", "tenant", "storage-key").isPresent());
        assertTrue(scoped.resolve("pkg", "tenant", "other").isEmpty());
    }

    @Test
    void aListOfNothingButWhitespaceIsAnEmptyList() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["credential-resolution"],
                                 "credentialReferences":["   "]}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void withCredentialResolutionAListThatOmitsASigningReferenceRefusesStartup() {
        // The operator wrote a constraint; a reference admitted here is readable in the clear, so it
        // is not added behind their back. The binding id is named, the reference never is.
        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["outbound-http","credential-resolution"],
                                 "credentialReferences":["api-key"],
                                 "awsSigV4Bindings":[{"bindingId":"storage",
                                   "origin":{"scheme":"https","host":"s3.example.com","port":443},
                                   "credentialReference":"storage-key","region":"eu-west-1",
                                   "service":"s3"}]}""")),
                        NO_CREDENTIALS));

        assertEquals(STORAGE_VARIABLE, refused.variableName());
        assertTrue(refused.getMessage().contains("'storage'"), refused::getMessage);
        assertFalseContains(refused.getMessage(), "storage-key");
    }

    @Test
    void withCredentialResolutionAListThatNamesTheSigningReferenceComposes() {
        // The positive direction: the operator writes it, and the grant builds. The exposure is the
        // same one the refusal was protecting them from inheriting -- now it is on the page.
        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode("""
                        {"capabilities":["outbound-http","credential-resolution"],
                         "credentialReferences":["api-key","storage-key"],
                         "awsSigV4Bindings":[{"bindingId":"storage",
                           "origin":{"scheme":"https","host":"s3.example.com","port":443},
                           "credentialReference":"storage-key","region":"eu-west-1",
                           "service":"s3"}]}""")),
                NO_CREDENTIALS);

        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP,
                        NodePackageCapability.CREDENTIAL_RESOLUTION),
                registry.capabilitiesFor(STORAGE_PACKAGE_ID));
    }

    @Test
    void withoutCredentialResolutionTheSameGrantIsUntouchedByTheNewRule() {
        // The property that makes the rule safe to add: an egress-only grant -- the common case, and
        // the one ravenroot-object-storage actually uses -- composes exactly as it did before, with
        // the signing reference still admitted silently because nothing can read it.
        String json = """
                {"capabilities":["outbound-http"],
                 "credentialReferences":["api-key"],
                 "awsSigV4Bindings":[{"bindingId":"storage",
                   "origin":{"scheme":"https","host":"s3.example.com","port":443},
                   "credentialReference":"storage-key","region":"eu-west-1","service":"s3"}]}""";

        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode(json)), NO_CREDENTIALS);
        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP),
                registry.capabilitiesFor(STORAGE_PACKAGE_ID));

        TenantCredentialResolver scoped = EnvironmentNodePackageServiceGrants.credentialScope(
                STORAGE_VARIABLE, List.of("api-key"), Map.of("storage", "storage-key"), EGRESS_ONLY,
                recording());
        assertTrue(scoped.resolve("pkg", "tenant", "storage-key").isPresent(),
                "the signing binding must keep working, which is why the union exists at all");
    }

    @Test
    void anEmptyReferenceListIsAMistakeRatherThanAWayToWriteNoGrant() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["credential-resolution"],"credentialReferences":[]}""")),
                        NO_CREDENTIALS));
    }

    @Test
    void aReferenceListParsesAsPartOfARealGrant() {
        NodePackageServiceRegistry registry = EnvironmentNodePackageServiceGrants.fromEnvironment(
                Map.of(STORAGE_VARIABLE, encode("""
                        {"capabilities":["credential-resolution"],
                         "credentialReferences":["storage-key"]}""")),
                NO_CREDENTIALS);

        assertEquals(Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                registry.capabilitiesFor(STORAGE_PACKAGE_ID));
    }

    @Test
    void aReferenceListMustBeAListOfText() {
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["credential-resolution"],
                                 "credentialReferences":"storage-key"}""")),
                        NO_CREDENTIALS));
        assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, encode("""
                                {"capabilities":["credential-resolution"],
                                 "credentialReferences":[null]}""")),
                        NO_CREDENTIALS));
    }

    private static TenantCredentialResolver recording() {
        return (packageId, tenantId, reference) ->
                Optional.of(new SecretValue(("secret-for-" + reference).toCharArray()));
    }

    @Test
    void theExceptionNamesTheVariableAndNeverCarriesItsValue() {
        String secretish = encode("""
                {"capabilities":["outbound-http"],
                 "awsSigV4Bindings":[{"bindingId":"b",
                   "origin":{"scheme":"https","host":"s3.example.com","port":443},
                   "credentialReference":"super-secret-reference","region":"eu-west-1",
                   "service":"kinesis"}]}""");

        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        Map.of(STORAGE_VARIABLE, secretish), NO_CREDENTIALS));

        assertEquals(STORAGE_VARIABLE, refused.variableName());
        assertFalseContains(refused.getMessage(), "super-secret-reference");
        assertFalseContains(refused.getMessage(), secretish);
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(haystack == null || !haystack.contains(needle),
                () -> "message must not carry grant content: " + haystack);
    }

    private static String encode(String json) {
        // Text blocks above are indented for readability; JSON is whitespace-insensitive, and the
        // canonical-Base64 rule is about the ENCODING being canonical, not the document being minified.
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
