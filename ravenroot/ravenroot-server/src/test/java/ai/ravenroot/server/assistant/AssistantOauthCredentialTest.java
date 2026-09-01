package ai.ravenroot.server.assistant;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth credential path, beside the operator key rather than instead of it.
 *
 * <p>The operator's two keys stay where the operator-egress contract put them — "the user brings the
 * subscription; the operator owns reach" — so every environment below still carries a provider and an
 * allowlist. What none of them carries is {@code RAVENROOT_ASSISTANT_API_KEY}: the credential comes
 * from a signed-in author's token, which is the whole point.</p>
 */
class AssistantOauthCredentialTest {

    private static final String AUTHOR = "author-who-signed-in";

    /** Operator reach, no operator credential. The deployment half of the operator-egress contract, alone. */
    private static final Map<String, String> REACH_ONLY = Map.of(
            AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER,
            AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com",
            AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE, AssistantCredentialSource.OAUTH.wireValue());

    /**
     * <b>An OAuth token alone satisfies the credential requirement.</b>
     *
     * <p>An adapter that egresses, built with the author's OAuth token alone and no
     * {@code RAVENROOT_ASSISTANT_API_KEY}. If the configuration reads only that variable, a deployment
     * with a signed-in author and no
     * operator key is inert with "no assistant provider credential is configured", and no provider
     * adapter is wired at all.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code AssistantConfiguration.fromEnvironment} ignore the token
     * store and this test reds on the first assertion, because availability
     * reports an operator credential gap for a deployment whose credential is not the operator's.</p>
     */
    @Test
    void anEgressingAdapterIsBuiltFromTheAuthorsTokenAloneWithNoApiKeyVariable() {
        var store = signedIn(AUTHOR, "oauth-access-token-for-the-author");

        var service = AssistantService.fromEnvironment(REACH_ONLY, consentToEverything(), store);

        assertTrue(service.availability(AUTHOR).ready(),
                () -> "a signed-in author must reach a ready service with no operator key: "
                        + service.availability(AUTHOR).detail());
        var provider = service.providerFor(AUTHOR);
        assertNotNull(provider, "no provider adapter was wired for the signed-in author");
        assertTrue(provider.egresses(),
                "the assertion concerns an adapter that egresses, not the scripted one");
    }

    /**
     * <b>An author with no token is never served on the operator's key, and the panel is told which
     * kind of gap it is looking at.</b>
     *
     * <p>A path that tries OAuth and falls back to the key
     * would satisfy the test above and be wrong. It is wrong at the wire level rather than only by
     * policy — sending both credentials is a request the provider rejects outright, not a graceful
     * degradation — and it bills the wrong account, which is the distinction this test enforces.</p>
     *
     * <h2>Why the expected reason is provider-specific</h2>
     * <p>{@code NOT_SIGNED_IN} would wrongly say the Ravenroot session is missing. The panel must
     * instead identify the absent provider connection so the author can act.</p>
     *
     * <p><b>The name was wrong.</b> The panel's {@code not-signed-in} means the RAVENROOT session is
     * unauthenticated and reads "Sign in to Ravenroot and try again" — which an author reaching this
     * branch has already done, since their subject resolved. Two different facts had been projected
     * onto one wire state and the visible result was a false instruction. {@code NOT_LINKED} is the
     * fact this branch establishes.</p>
     *
     * <p><b>And "the user can act" depends on something this test can vary.</b> An author can only
     * act if the deployment has a connection path; without one, the operator has chosen per-author
     * credentials and supplied no way to obtain them, and offering a Connect control would invite
     * the author to fix what is not theirs. Both directions are asserted because the person who can
     * act differs between them.</p>
     *
     * <p><b>Mutation proof.</b> Add "if the token store has nothing, use the API key" to
     * {@code AssistantConfiguration} and this reds: availability becomes ready in both branches.
     * Delete the downgrade in {@code AssistantService#connectable} and the second half reds: an
     * author is offered a control that cannot lead anywhere.</p>
     */
    @Test
    void anAuthorWithNoTokenNeverBorrowsTheOperatorKeyAndTheGapIsNamedForWhoeverCanCloseIt() {
        var withOperatorKey = new java.util.HashMap<>(REACH_ONLY);
        withOperatorKey.put(AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-the-operators-own-key");
        var nobodySignedIn = signedIn("someone-else", "not-this-authors-token");

        var service = AssistantService.fromEnvironment(withOperatorKey, consentToEverything(),
                nobodySignedIn);

        var withoutAWay = service.availability(AUTHOR);
        assertFalse(withoutAWay.ready(),
                "an author with no token must not be served on the operator's key");
        assertEquals(AssistantAvailability.InertReason.NO_PROFILE, withoutAWay.reason(),
                () -> "with no connection path the author cannot act, so the panel must name the "
                        + "operator rather than offer a control that leads nowhere: "
                        + withoutAWay.reason());

        var connectable = service.withConnection(nothingInFlight()).availability(AUTHOR);
        assertFalse(connectable.ready(), "a connection path does not make an unconnected author ready");
        assertEquals(AssistantAvailability.InertReason.NOT_LINKED, connectable.reason(),
                () -> "with a connection path this IS the author's to fix, and it is not the "
                        + "Ravenroot session: " + connectable.reason());
    }

    /**
     * <b>A connection cannot be offered by a deployment whose turns would not use the token.</b>
     *
     * <p>An author would complete a sign-in and every question would still be refused — a state that
     * reports success and behaves like failure. Refusing at composition is what keeps that from
     * being discovered by the first author to try it.</p>
     */
    @Test
    void aDeploymentOnTheOperatorKeyCannotOfferAConnectionAtAll() {
        var keyed = Map.of(
                AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER,
                AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com",
                AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-the-operators-own-key");
        var service = AssistantService.fromEnvironment(keyed, consentToEverything(), null);

        var refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.withConnection(nothingInFlight()));

        assertTrue(refused.getMessage().contains(AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE),
                () -> "the refusal must say what would have to change: " + refused.getMessage());
    }

    /** A connection that is wired and has nothing in flight. Enough to make the offer real. */
    private static ai.ravenroot.server.assistant.oauth.AssistantConnection nothingInFlight() {
        return new ai.ravenroot.server.assistant.oauth.AssistantConnection() {
            @Override
            public Prompt begin(String subject) {
                throw new UnsupportedOperationException("not exercised here");
            }

            @Override
            public Progress poll(String subject) {
                return new Progress.None();
            }

            @Override
            public void abandon(String subject) {
                // Nothing is in flight, so there is nothing to abandon.
            }
        };
    }

    /**
     * <b>The operator key remains the default: with no explicit choice, nothing about OAuth
     * happens.</b>
     *
     * <p>Both directions are asserted because "explicit" is a property of the switch, not of one of
     * its positions. A deployment that silently preferred a present token over a configured key would
     * violate explicit selection just as much as one that fell back the other way — and it would move the
     * bill from the operator's account to the author's without anyone choosing it.</p>
     */
    @Test
    void withoutAnExplicitChoiceTheOperatorKeyIsUsedAndTheTokenStoreIsNeverConsulted() {
        var keyed = Map.of(
                AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER,
                AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com",
                AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-the-operators-own-key");
        var consulted = new java.util.concurrent.atomic.AtomicInteger();
        AssistantTokenStore counting = subject -> {
            consulted.incrementAndGet();
            return Optional.of(AssistantCredential.oauthToken("a-token-nobody-asked-for"));
        };

        var service = AssistantService.fromEnvironment(keyed, consentToEverything(), counting);

        assertTrue(service.availability(AUTHOR).ready(), "the keyed deployment must still work");
        assertEquals(AssistantCredential.Scheme.API_KEY,
                service.credentialSchemeFor(AUTHOR),
                "an unconfigured deployment must keep using the operator key, not a stray token");
        assertEquals(0, consulted.get(),
                "the token store must not even be asked when OAuth was not chosen: consulting it is "
                        + "how a silent replacement starts");
    }

    /**
     * <b>A misspelled credential source is the operator key, not OAuth.</b>
     *
     * <h2>Why this needed its own test, and what its absence looked like</h2>
     * <p>{@code AssistantCredentialSource.parse} already documented this property and why it matters:
     * a typo that moved a deployment onto OAuth would move it onto a model where nobody is signed in,
     * and the panel would go inert for every author at once. But nothing exercised it — no test ever
     * passed an invalid value — so the property was <em>stated</em> and not <em>held</em>. Changing
     * that branch to {@code return OAUTH;} left the whole suite green.</p>
     *
     * <p>Both halves are asserted for the same reason as the well-spelled cases: the scheme that
     * actually serves the author, and the fact that the token store was never even asked. A
     * deployment that fell through to OAuth would not merely pick a different credential — it would
     * move every author's turns onto their own account, or onto nothing at all.</p>
     *
     * <p><b>Not in the list, and deliberately so: {@code "Oauth "}.</b> It was tried, and it is
     * <em>accepted</em> — {@code parse} strips and lowercases before matching, so casing and stray
     * whitespace are normalised rather than rejected. That is the right behaviour and the same
     * normalisation {@code AssistantConfiguration} applies to every other variable, so an operator who
     * writes {@code OAUTH} gets what they meant. {@link #aNormalisedCredentialSourceIsStillRecognised}
     * pins it, which is what keeps this test's list honest: these are values with no meaning, not
     * values written untidily.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code parse}'s unrecognised branch return {@code OAUTH} and this
     * test reds on both assertions: the resolved scheme becomes null because no author is signed in,
     * and the counting store is consulted. Verified by making that edit, not by reasoning about it.</p>
     */
    @Test
    void aMisspelledCredentialSourceFallsBackToTheOperatorKeyAndNotToOauth() {
        for (String typo : new String[] {"oauth2", "OAUTH_DEVICE", "api key", "auth", "true"}) {
            var misconfigured = new java.util.HashMap<>(REACH_ONLY);
            misconfigured.put(AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE, typo);
            misconfigured.put(AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-the-operators-own-key");
            var consulted = new java.util.concurrent.atomic.AtomicInteger();
            AssistantTokenStore counting = subject -> {
                consulted.incrementAndGet();
                return Optional.of(AssistantCredential.oauthToken("a-token-nobody-asked-for"));
            };

            var service = AssistantService.fromEnvironment(misconfigured, consentToEverything(),
                    counting);

            assertEquals(AssistantCredential.Scheme.API_KEY, service.credentialSchemeFor(AUTHOR),
                    () -> "'" + typo + "' is not a credential source this build knows, and an "
                            + "unknown one must be the default rather than the one that signs "
                            + "everybody out");
            assertEquals(0, consulted.get(),
                    () -> "'" + typo + "' left the deployment consulting the token store, so a typo "
                            + "moved it onto per-author credentials");
        }
    }

    /**
     * <b>Casing and surrounding whitespace are normalised, not rejected.</b>
     *
     * <p>The other half of the test above, and the reason its list can be trusted. Without this, the
     * safest-looking repair to a "typo must not become OAuth" failure would be to make {@code parse}
     * stricter — and a deployment that had written {@code OAUTH} in its compose file would silently
     * move back onto the operator key, billing the deployment for every author's turns.</p>
     */
    @Test
    void aNormalisedCredentialSourceIsStillRecognised() {
        for (String written : new String[] {"OAUTH", " oauth ", "OAuth"}) {
            assertEquals(AssistantCredentialSource.OAUTH, AssistantCredentialSource.parse(written),
                    () -> "'" + written + "' is 'oauth' written untidily, not an unknown value");
        }
        assertEquals(AssistantCredentialSource.API_KEY, AssistantCredentialSource.parse(" API-KEY "),
                "the default is spelled the same way and normalised the same way");
    }

    // ---------------------------------------------------------------------------------------------

    private static AssistantTokenStore signedIn(String subject, String token) {
        return candidate -> subject.equals(candidate)
                ? Optional.of(AssistantCredential.oauthToken(token))
                : Optional.empty();
    }

    private static AssistantConsentStore consentToEverything() {
        return (subject, provider) -> EnumSet.allOf(AssistantContextClass.class);
    }
}
