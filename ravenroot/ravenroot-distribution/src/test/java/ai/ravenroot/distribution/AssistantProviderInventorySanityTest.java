package ai.ravenroot.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RED control for the {@code AssistantProvider} inventory (P9).
 *
 * <h2>Why this test exists rather than trusting the inventory's own output</h2>
 * <p>P9 is a report, not a refusal: it prints the concrete implementations found and refuses
 * nothing. A report has a failure mode a refusal does not — it can find nothing because there is
 * nothing to find, or because it is not looking, and both render as an empty list that reads as
 * calm. {@link ReleaseArtifactBoundaryChecks#sanityViolations} therefore fails on an empty
 * inventory, and a green {@link ReleaseArtifactModelAdapterBoundaryIT} run proves only that the
 * branch was not taken — it says nothing about whether the branch works. This test takes it.</p>
 *
 * <p>A check inside a condition that silently never fires is indistinguishable from a check that
 * fires and passes, until someone injects the failure and looks.</p>
 */
class AssistantProviderInventorySanityTest {

    @Test
    void anIndexThatSeesNothingFailsTheAssistantProviderSanityGuardRatherThanReportingAnEmptyInventory() {
        // An index over nothing at all: the shape of a scan that is not reaching the artifact's real
        // contents, which is precisely what an empty P9 inventory would otherwise look like.
        var blindIndex = new ClassGraphIndex();

        List<String> violations = ReleaseArtifactBoundaryChecks.sanityViolations(blindIndex, "an empty index");

        assertTrue(violations.stream().anyMatch(v -> v.contains(ReleaseArtifactBoundaryChecks.ASSISTANT_PROVIDER_FQCN)),
                () -> "sanityViolations did not fail on an index containing no AssistantProvider at all. "
                        + "Without this branch, the inventory would print an empty list for a "
                        + "scan that never saw ravenroot-server, and that empty list would be "
                        + "indistinguishable from an artifact that genuinely ships no assistant "
                        + "provider. Violations were: " + violations);
        assertEquals(List.of(), ReleaseArtifactBoundaryChecks.assistantProviderInventory(blindIndex));
    }

    /**
     * The GREEN half, over this module's own resolved classpath rather than the built artifact
     * (which does not exist at the {@code test} phase — see {@link
     * DefaultDistributionModelAdapterBoundaryTest}'s Javadoc for the same constraint). Proves the
     * interface's internal name is spelled correctly and that ancestry resolution actually reaches
     * it: a typo in {@code ASSISTANT_PROVIDER_INTERNAL} would make every P9 inventory empty and
     * every sanity check fail, which is loud — but a subtler mistake, matching the interface while
     * finding no implementations, would be silent without this assertion.
     */
    @Test
    void theClasspathIndexFindsTheAssistantProviderImplementationsThisDistributionShips() throws IOException {
        ClassGraphIndex index = ClassGraphIndex.scanJavaClassPath(Set.of());

        assertTrue(index.containsClass(ReleaseArtifactBoundaryChecks.ASSISTANT_PROVIDER_INTERNAL),
                ReleaseArtifactBoundaryChecks.ASSISTANT_PROVIDER_FQCN + " is not on this module's classpath; "
                        + "the internal name in ReleaseArtifactBoundaryChecks is wrong, or ravenroot-server "
                        + "is no longer a dependency of ravenroot-distribution.");
        List<String> inventory = ReleaseArtifactBoundaryChecks.assistantProviderInventory(index);
        assertFalse(inventory.isEmpty(), "No concrete AssistantProvider implementation was found on the "
                + "classpath, but ravenroot-server ships ScriptedAssistantProvider, "
                + "AnthropicAssistantProvider and OpenAiCompatibleAssistantProvider. Found: " + inventory);
        assertTrue(inventory.stream().anyMatch(name -> name.contains("OpenAiCompatibleAssistantProvider")),
                () -> "The production provider is absent from the P9 inventory: " + inventory);
    }
}
