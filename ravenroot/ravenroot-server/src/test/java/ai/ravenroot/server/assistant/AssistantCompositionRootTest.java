package ai.ravenroot.server.assistant;

import ai.ravenroot.server.assistant.provider.AnthropicAssistantProvider;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate is structural, not configurational.
 *
 * <p>Consent, token and egress posture, and per-provider verification that subscription terms permit
 * use by a third-party product's panel each gate the first outbound byte. These tests are what turn
 * "we have not connected a model yet" from a thing three environment variables can quietly undo into a
 * boot-time fact.</p>
 */
class AssistantCompositionRootTest {

    private static final Map<String, String> FULLY_CONFIGURED = Map.of(
            AssistantConfiguration.PROVIDER_VARIABLE, "anthropic",
            AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-would-work",
            AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com");

    /**
     * <b>A fully configured deployment refuses to wire a network adapter without a consent store.</b>
     *
     * <p>This is the test that would have to be deleted to connect a model early, which is exactly the
     * property wanted: opening the gate is a visible edit here, not a deployment variable.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code consent == null} branch in
     * {@code AssistantService#fromEnvironment} and this test reds — the service constructs, and the
     * assertion that it throws fails.</p>
     */
    @Test
    void aNetworkProviderIsNotWiredWithoutAConsentStore() {
        var refused = assertThrows(IllegalStateException.class,
                () -> AssistantService.fromEnvironment(FULLY_CONFIGURED, null));
        assertTrue(refused.getMessage().contains("consent"),
                () -> "the refusal must name what is missing: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("sk-ant-would-work"),
                "the refusal must not quote the credential it declined to use");
    }

    /**
     * <b>Consent plus no open blockers actually connects.</b>
     *
     * <p>A fully configured deployment is ready only when both the consent gate and adapter-blocker
     * gate allow connection. With {@code OPEN_CONNECTION_BLOCKERS} empty, satisfying consent reaches
     * {@code adapter.build} and constructs a network-capable {@link AnthropicAssistantProvider}.</p>
     *
     * <p>The two gates were always independent, and remain so: this test says nothing about the
     * blocker gate being gone forever, only that nothing is open today. A future defect reopens it by
     * adding an entry back to {@code OPEN_CONNECTION_BLOCKERS}, and {@link
     * #aNetworkProviderIsNotWiredWithoutAConsentStore} above still proves the consent gate refuses on
     * its own, with no consent store at all, regardless of what this one does.</p>
     *
     * <p><b>Mutation proof.</b> Reintroduce any entry into {@code OPEN_CONNECTION_BLOCKERS} and this
     * test reds — {@code assertDoesNotThrow} fails because {@code requireNoOpenBlockers} refuses
     * again, which is exactly the coupling this test exists to prove.</p>
     */
    @Test
    void consentAndNoOpenBlockersConnectsTheAdapter() {
        AssistantConsentStore consented =
                (subject, provider) -> java.util.EnumSet.allOf(AssistantContextClass.class);
        var service = assertDoesNotThrow(
                () -> AssistantService.fromEnvironment(FULLY_CONFIGURED, consented),
                "with consent given and no open blockers, nothing should still be refusing connection");
        assertTrue(service.availability().ready(), "a fully configured, consented deployment is ready");
        assertEquals("anthropic", service.availability().provider());
    }

    /**
     * The blocker list is what the gate reads. Its emptiness means the adapter has no known connection
     * blockers, so only consent remains, proved
     * independently above and by {@link #aNetworkProviderIsNotWiredWithoutAConsentStore}.
     *
     * <p>Asserted directly, and not merely implied by the test above succeeding, because the list is
     * the coupling between known defects and the gate: if a future defect were found and the list not
     * updated to name it, a reader would have no way to tell "nothing is open" from "something is open
     * and nobody recorded it".</p>
     *
     * <p><b>Mutation proof.</b> Reintroduce a resolved technical blocker into the list and this test
     * reds naming the stale entry; leave the list non-empty for any other reason and the first
     * assertion reds.</p>
     */
    @Test
    void theBlockerListIsEmptyBecauseEveryFindingIsFixed() {
        assertTrue(AnthropicAssistantProvider.OPEN_CONNECTION_BLOCKERS.isEmpty(),
                "the adapter has no known connection blockers: "
                        + "credentials reject control characters, "
                        + "responses are bounded as read, and complete() classifies RuntimeException");
        for (String fixed : List.of("credential control character", "unbounded response",
                "unchecked exception", "response-size limit", "header injection")) {
            assertTrue(AnthropicAssistantProvider.OPEN_CONNECTION_BLOCKERS.stream()
                            .noneMatch(blocker -> blocker.contains(fixed)),
                    () -> fixed + " is fixed, so listing it here would be a false claim in the "
                            + "cautious direction -- and over-claiming erodes the entries that matter "
                            + "if this list is ever non-empty again.");
        }
    }

    /**
     * <b>The gate is on the type, not only on the composition root.</b>
     *
     * <p>The public two-argument constructor sat beside the guarded factory as an unguarded second
     * door: any caller could assemble an egressing service directly. The property wanted is not "the
     * factory refuses" but "the type cannot exist", so the check moved into the canonical constructor
     * and both doors now lead through it.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code provider.egresses() && consent == null} branch in
     * {@code AssistantService}'s canonical constructor and this test reds.</p>
     */
    @Test
    void anEgressingProviderCannotBeHeldWithoutConsentEvenByDirectConstruction() {
        var refused = assertThrows(IllegalStateException.class,
                () -> new AssistantService(AssistantHarness.readyConfiguration(), egressingStub()));
        assertTrue(refused.getMessage().contains("egresses"),
                () -> "the refusal must name the property it keys on: " + refused.getMessage());

        // ...and the same provider is fine once a consent store is supplied, so the gate is the
        // consent store's absence rather than a blanket refusal of every direct construction.
        assertDoesNotThrow(() -> new AssistantService(AssistantHarness.readyConfiguration(),
                egressingStub(),
                (subject, provider) -> java.util.EnumSet.allOf(AssistantContextClass.class)));
    }

    /**
     * <b>The exemption is keyed on a declared property, so a second network adapter inherits the gate
     * instead of escaping it.</b>
     *
     * <p>This is the shape the previous design got wrong: the throw sat after a provider-identity
     * check, below a highly visible scripted-provider early return. Copying that early return for a
     * second network provider bypassed consent, and nothing caught it. Here a stub that merely
     * <em>declares</em> it egresses is refused, with no mention of it anywhere in the service.</p>
     */
    @Test
    void aNewNetworkAdapterInheritsTheGateByDeclaringThatItEgresses() {
        assertTrue(egressingStub().egresses(), "the stub must declare egress for this test to mean anything");
        assertThrows(IllegalStateException.class,
                () -> new AssistantService(AssistantHarness.readyConfiguration(), egressingStub()),
                "an adapter this service has never heard of is gated purely by what it declares");
    }

    /** An adapter that reaches the network, known to the service only by its own declaration. */
    private static AssistantProvider egressingStub() {
        return new AssistantProvider() {
            @Override
            public String id() {
                return "a-second-network-provider";
            }

            @Override
            public URI endpoint() {
                return URI.create("https://example.invalid/v1/messages");
            }

            @Override
            public boolean egresses() {
                return true;
            }

            @Override
            public Turn complete(Request request) {
                throw new UnsupportedOperationException("never called: construction is refused first");
            }
        };
    }

    /**
     * <b>The scripted provider is wired without consent, because it makes no outbound call.</b>
     *
     * <p>It is also selected the same way every other provider is — through
     * {@code RAVENROOT_ASSISTANT_PROVIDER}, in the composition root — rather than through a flag the
     * service branches on. That is what makes it a conformance vehicle for the port instead of a
     * bypass around it.</p>
     */
    @Test
    void theScriptedProviderIsSelectedLikeAnyOtherAndNeedsNoConsent() {
        var service = AssistantService.fromEnvironment(
                Map.of(AssistantConfiguration.PROVIDER_VARIABLE, ScriptedAssistantProvider.ID), null);
        assertTrue(service.availability().ready(),
                "the scripted provider makes the panel usable with no key, no host and no consent");
        assertEquals(ScriptedAssistantProvider.ID, service.availability().provider());
    }

    /**
     * The scripted provider answers in its own voice and never echoes the author.
     *
     * <p>Asserted because a development provider that reflected the prompt back would be the precise
     * defect the provider contract forbids, wearing a provider interface as a disguise.</p>
     */
    @Test
    void theScriptedProviderNeverEchoesTheAuthor() throws Exception {
        var provider = new ScriptedAssistantProvider();
        String prompt = "a-distinctive-prompt-the-provider-must-not-repeat";
        var turn = provider.complete(new ai.ravenroot.server.assistant.provider.AssistantProvider.Request(
                "irrelevant", null,
                java.util.List.of(ai.ravenroot.server.assistant.provider.AssistantProvider.Message
                        .author(prompt)),
                java.util.List.of(), 100));
        var answer = (ai.ravenroot.server.assistant.provider.AssistantProvider.Turn.Answer) turn;
        assertFalse(answer.text().contains(prompt), "the scripted provider must not echo the prompt");
        assertTrue(answer.text().contains("no model is connected"),
                "it must say plainly that no model is connected");
    }
}
