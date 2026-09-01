package ai.ravenroot.server.assistant;

import ai.ravenroot.server.assistant.oauth.AssistantConnection;
import ai.ravenroot.server.assistant.oauth.DeviceFlowAssistantConnection;
import ai.ravenroot.server.assistant.oauth.ScriptedHttpClient;
import ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring for assistant credentials and consent.
 *
 * <p>{@code AssistantCompositionRootTest} pins what {@link AssistantService#fromEnvironment} refuses.
 * This one pins what the <em>shipped</em> composition root actually hands it. Null stores make a
 * deployment naming a network provider throw at startup and leave an OAuth deployment nowhere to
 * put a redeemed token.</p>
 *
 * <p><b>What this establishes and what it cannot.</b> The device flow below runs against a scripted
 * {@link HttpClient}: no request leaves the process, and no provider is involved. So this proves the
 * whole chain from environment variables to a stored per-author token executes — not that any
 * authorization server accepts it; the first real run remains an experiment.</p>
 */
class AssistantCompositionTest {

    @TempDir
    Path consentDirectory;

    private static final String AUTHOR = "author-who-signed-in";
    private static final String DEVICE_ENDPOINT = "https://api.anthropic.com/v1/oauth/device";
    private static final String TOKEN_ENDPOINT = "https://api.anthropic.com/v1/oauth/token";

    private static final String GRANT = """
            {"device_code":"the-half-that-redeems-it","user_code":"WDJB-MJHT",
             "verification_uri":"https://provider.example/device","interval":5,"expires_in":900}""";
    private static final String REDEEMED =
            "{\"access_token\":\"the-authors-own-token\",\"token_type\":\"bearer\"}";

    /**
     * <b>A fully configured API-key deployment starts instead of refusing to.</b>
     *
     * <p>For an operator who does not use OAuth, a provider, an allowlisted host and a key must let
     * the listener bind. A composition with null stores reaches {@code requireConsent(null)} and
     * throws {@code IllegalStateException} instead of producing a degraded panel.</p>
     *
     * <p>Passing {@code null} for both stores makes this test fail with the
     * {@code IllegalStateException} naming consent; wiring the real stores makes it pass.</p>
     */
    @Test
    void aFullyConfiguredApiKeyDeploymentComposesInsteadOfRefusingToStart() {
        try (var composed = assertDoesNotThrow(() -> AssistantComposition.fromEnvironment(apiKeyDeployment()),
                "a deployment with a provider, an allowlisted host and a key must start")) {
            assertTrue(composed.service().availability().ready(),
                    "the panel must be usable, not merely constructible");
            assertEquals("anthropic", composed.service().availability().provider());
        }
    }

    /**
     * <b>The consent register the deployment gets is the real one, on disk.</b>
     *
     * <p>Asserted against the file rather than against the store being non-null, because "a store is
     * present" is also true of a double and does not prove anything asks the register a question. The
     * file is the thing an operator backs
     * up and the thing that survives a restart.</p>
     *
     * <p><b>Mutation proof.</b> Replace the {@link SqliteAssistantConsentStore} in
     * {@code AssistantComposition} with an in-memory lambda and this reds — no database file is
     * created.</p>
     */
    @Test
    void theConsentRegisterIsTheDurableOneAndItIsCreatedWhereTheOperatorAsked() throws Exception {
        try (var composed = AssistantComposition.fromEnvironment(apiKeyDeployment())) {
            assertTrue(composed.service().availability().ready());
        }
        assertTrue(Files.exists(consentDirectory.resolve(SqliteAssistantConsentStore.FILE_NAME)),
                () -> "no consent database under " + consentDirectory);
    }

    /**
     * <b>A deployment that names no provider gets no database.</b>
     *
     * <p>The counterpart to the test above, and the reason the register is opened conditionally: an
     * operator who never configured an assistant should not find a SQLite file appear in their data
     * volume because a composition root opened one unconditionally.</p>
     *
     * <p><b>Mutation proof.</b> Remove the {@code reachesTheNetwork} guard in
     * {@code AssistantComposition} so the register is always opened, and this reds.</p>
     */
    @Test
    void anInertDeploymentOpensNoConsentDatabase() {
        Map<String, String> inert = new HashMap<>();
        inert.put(SqliteAssistantConsentStore.DIRECTORY_VARIABLE, consentDirectory.toString());
        try (var composed = AssistantComposition.fromEnvironment(inert)) {
            assertFalse(composed.service().availability().ready());
        }
        assertFalse(Files.exists(consentDirectory.resolve(SqliteAssistantConsentStore.FILE_NAME)),
                "an unconfigured deployment must not create an assistant consent database");
    }

    /** The scripted provider egresses nowhere, so it needs no consent register either. */
    @Test
    void theScriptedProviderOpensNoConsentDatabase() {
        Map<String, String> scripted = new HashMap<>();
        scripted.put(SqliteAssistantConsentStore.DIRECTORY_VARIABLE, consentDirectory.toString());
        scripted.put(AssistantConfiguration.PROVIDER_VARIABLE, ScriptedAssistantProvider.ID);
        try (var composed = AssistantComposition.fromEnvironment(scripted)) {
            assertTrue(composed.service().availability().ready(),
                    "the local development path must still work");
        }
        assertFalse(Files.exists(consentDirectory.resolve(SqliteAssistantConsentStore.FILE_NAME)),
                "the scripted adapter reaches no network and needs no consent register");
    }

    /**
     * <b>An OAuth deployment offers an author a connection they can actually complete.</b>
     *
     * <p>{@code AssistantService#connectable} must retain the author-actionable {@code not-linked}
     * state when the composition root calls {@code withConnection}; downgrading it to the operator's
     * {@code no-profile} would hide the Connect control from every author.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code withConnection(...)} call in
     * {@code AssistantComposition} and this reds twice over: the connection is null, and the reason
     * downgrades to {@code NO_PROFILE}.</p>
     */
    @Test
    void anOauthDeploymentWithDeviceEndpointsOffersTheAuthorAConnection() {
        try (var composed = AssistantComposition.fromEnvironment(oauthDeployment(), unusedClient())) {
            assertInstanceOf(DeviceFlowAssistantConnection.class, composed.service().connection(),
                    "the composition root must build the real device flow, not leave the seam empty");
            assertEquals(AssistantAvailability.InertReason.NOT_LINKED,
                    composed.service().availability(AUTHOR).reason(),
                    "an author who can connect must be told that they, not the operator, are what is "
                            + "outstanding");
            assertTrue(composed.startupNotes().isEmpty(),
                    () -> "a correct configuration must produce no operator warning: "
                            + composed.startupNotes());
        }
    }

    /**
     * <b>The whole path executes: environment in, an author's own token stored, the panel ready.</b>
     *
     * <p>Driven rather than asserted. The connection under test is the one
     * {@code AssistantComposition} built from environment variables — not a double — so this
     * exercises {@code AssistantDeviceAuthorization}, {@code DeviceFlowAssistantConnection} and
     * {@code InMemoryAssistantTokenStore} as the composition root actually assembles them. Only the
     * socket is replaced.</p>
     *
     * <p>The final assertion is the one that matters: the same service that reported
     * {@code not-linked} for this author before the exchange reports {@code ready} after it, which
     * means the redeemed token reached the store the turn path reads. That is the link that did not
     * exist, and it is checked by running it rather than by reading the wiring.</p>
     */
    @Test
    void anAuthorCanCompleteTheDeviceFlowAndIsThenServed() {
        var requests = new ArrayList<HttpRequest>();
        var bodies = new ArrayList<String>();
        Deque<String> responses = new ArrayDeque<>(List.of(GRANT, REDEEMED));

        try (var composed = AssistantComposition.fromEnvironment(oauthDeployment(),
                () -> new ScriptedHttpClient(requests, bodies, responses))) {
            AssistantConnection connection = composed.service().connection();
            assertNotNull(connection, "the deployment must offer a connection at all");

            AssistantConnection.Prompt prompt = connection.begin(AUTHOR);
            assertEquals("WDJB-MJHT", prompt.userCode());
            assertEquals(AssistantAvailability.InertReason.NOT_LINKED,
                    composed.service().availability(AUTHOR).reason(),
                    "beginning a grant is not finishing one");

            assertInstanceOf(AssistantConnection.Progress.Linked.class, connection.poll(AUTHOR));

            assertTrue(composed.service().availability(AUTHOR).ready(),
                    "after signing in, this author's panel must be usable");
            assertEquals(AssistantCredential.Scheme.OAUTH_BEARER,
                    composed.service().credentialSchemeFor(AUTHOR),
                    "the author must be served on their own token, never on an operator key");
            assertEquals(List.of(DEVICE_ENDPOINT, TOKEN_ENDPOINT),
                    requests.stream().map(request -> request.uri().toString()).toList(),
                    "both halves of RFC 8628 must go to the endpoints the operator configured");
        }
    }

    /**
     * <b>Somebody else's sign-in is not this author's.</b>
     *
     * <p>The token store is keyed by subject, and this is what makes that a property of the composed
     * deployment rather than of a unit test around the store. A second author on the same server sees
     * {@code not-linked} while the first is served.</p>
     */
    @Test
    void oneAuthorSigningInDoesNotServeAnother() {
        Deque<String> responses = new ArrayDeque<>(List.of(GRANT, REDEEMED));
        try (var composed = AssistantComposition.fromEnvironment(oauthDeployment(),
                () -> new ScriptedHttpClient(new ArrayList<>(), new ArrayList<>(), responses))) {
            composed.service().connection().begin(AUTHOR);
            composed.service().connection().poll(AUTHOR);

            assertTrue(composed.service().availability(AUTHOR).ready());
            assertEquals(AssistantAvailability.InertReason.NOT_LINKED,
                    composed.service().availability("somebody-else").reason());
            assertNull(composed.service().credentialSchemeFor("somebody-else"),
                    "a second author must hold no credential because the first signed in");
        }
    }

    /**
     * <b>Without the device-flow variables, the deployment reports the operator's gap as before.</b>
     *
     * <p>An OAuth deployment whose operator has not
     * supplied the endpoints still shows no Connect control, because pressing it could not lead
     * anywhere. {@code AssistantService#connectable} already decided that; this proves the new
     * composition path did not quietly bypass it.</p>
     */
    @Test
    void anOauthDeploymentWithoutDeviceEndpointsStillReportsTheOperatorGap() {
        Map<String, String> withoutEndpoints = oauthDeployment();
        withoutEndpoints.remove(AssistantComposition.DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE);
        withoutEndpoints.remove(AssistantComposition.TOKEN_ENDPOINT_VARIABLE);
        withoutEndpoints.remove(AssistantComposition.OAUTH_CLIENT_ID_VARIABLE);

        try (var composed = AssistantComposition.fromEnvironment(withoutEndpoints, noProviderReached())) {
            assertNull(composed.service().connection());
            assertEquals(AssistantAvailability.InertReason.NO_PROFILE,
                    composed.service().availability(AUTHOR).reason(),
                    "no author may be offered a control that cannot work");
        }
    }

    /**
     * <b>Half a device-flow configuration is refused by name.</b>
     *
     * <p>Silently skipping would produce a panel saying no connection path is configured — the same
     * state as having configured nothing, and therefore indistinguishable from a typo in a variable
     * name. That indistinguishability is itself a failure, so it is refused here.</p>
     */
    @Test
    void aPartialDeviceFlowConfigurationIsRefusedAndNamesWhatIsMissing() {
        Map<String, String> partial = oauthDeployment();
        partial.remove(AssistantComposition.TOKEN_ENDPOINT_VARIABLE);

        var refused = assertThrows(IllegalArgumentException.class,
                () -> AssistantComposition.fromEnvironment(partial, noProviderReached()));

        assertTrue(refused.getMessage().contains(AssistantComposition.TOKEN_ENDPOINT_VARIABLE),
                () -> "the refusal must name the missing variable: " + refused.getMessage());
    }

    /** A credential exchange over plaintext is refused at parse, not attempted and audited later. */
    @Test
    void aDeviceEndpointThatIsNotHttpsIsRefused() {
        Map<String, String> plaintext = oauthDeployment();
        plaintext.put(AssistantComposition.TOKEN_ENDPOINT_VARIABLE,
                "http://api.anthropic.com/v1/oauth/token");

        var refused = assertThrows(IllegalArgumentException.class,
                () -> AssistantComposition.fromEnvironment(plaintext, noProviderReached()));

        assertTrue(refused.getMessage().contains("https"), refused.getMessage());
        assertFalse(refused.getMessage().contains("api.anthropic.com"),
                "the refusal names the setting, not the value it repeats back");
    }

    /**
     * <b>Device endpoints in an API-key deployment are reported, not silently dropped.</b>
     *
     * <p>Deliberately a note rather than a refusal. The deployment is otherwise valid and the panel
     * already reports its state correctly; turning a soft, fixable report into a dead process would
     * be worse than the defect. But an operator who set three variables that took no effect has to
     * be told because the configured parts otherwise remain disconnected.</p>
     */
    @Test
    void deviceEndpointsInAnApiKeyDeploymentAreReportedRatherThanIgnored() {
        Map<String, String> mixed = apiKeyDeployment();
        mixed.put(AssistantComposition.DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE, DEVICE_ENDPOINT);
        mixed.put(AssistantComposition.TOKEN_ENDPOINT_VARIABLE, TOKEN_ENDPOINT);
        mixed.put(AssistantComposition.OAUTH_CLIENT_ID_VARIABLE, "ravenroot-device-client");

        try (var composed = AssistantComposition.fromEnvironment(mixed, noProviderReached())) {
            assertNull(composed.service().connection());
            assertTrue(composed.service().availability().ready(),
                    "the API-key deployment must still work");
            assertEquals(1, composed.startupNotes().size(),
                    () -> "the operator must be told: " + composed.startupNotes());
            assertTrue(composed.startupNotes().get(0)
                            .contains(AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE),
                    () -> "the note must name the variable that would fix it: "
                            + composed.startupNotes());
        }
    }

    /** A non-positive session lifetime stores nothing, so it is refused where the variable is named. */
    @Test
    void aNonPositiveSessionLifetimeIsRefusedByVariableName() {
        Map<String, String> zeroed = oauthDeployment();
        zeroed.put(AssistantComposition.SESSION_MINUTES_VARIABLE, "0");

        var refused = assertThrows(IllegalArgumentException.class,
                () -> AssistantComposition.fromEnvironment(zeroed, noProviderReached()));

        assertTrue(refused.getMessage().contains(AssistantComposition.SESSION_MINUTES_VARIABLE),
                refused.getMessage());
    }

    /**
     * <b>The shipped composition root still calls this.</b>
     *
     * <p>A source scan, in the style {@code DeploymentProbeWiringTest} already uses in this
     * repository, and for the same reason: the property is "this line exists". Every behavioral test
     * above would keep passing if {@code RavenrootServerMain} stopped calling
     * {@code AssistantComposition}, leaving the feature tested but unreachable.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code RavenrootServerMain} use the constructor overload that
     * defaults the assistant, and this reds on the second assertion.</p>
     */
    @Test
    void theShippedCompositionRootWiresTheAssistantThroughThisClass() throws Exception {
        Path main = Path.of("src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
                .toAbsolutePath().normalize();
        String source = Files.readString(main);

        assertTrue(source.contains("AssistantComposition.fromEnvironment(System.getenv())"),
                () -> main + " must compose the assistant's real stores");
        assertTrue(source.contains("assistantComposition.service()"),
                () -> main + " must hand the composed service to the server, not let the constructor "
                        + "default it back to AssistantService.fromEnvironment(System.getenv()) -- "
                        + "which is the null-store overload that leaves the composed stores unused");
        assertTrue(source.contains("assistantComposition.close()"),
                () -> main + " must close the consent register it opened");
    }

    // ---------------------------------------------------------------------------------------------

    /** Provider, allowlisted host and an operator key: the deployment that used to refuse to start. */
    private Map<String, String> apiKeyDeployment() {
        Map<String, String> env = new HashMap<>();
        env.put(AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER);
        env.put(AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-would-work");
        env.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com");
        env.put(SqliteAssistantConsentStore.DIRECTORY_VARIABLE, consentDirectory.toString());
        return env;
    }

    /** The same operator half, with authors bringing their own credential and a device flow to do it. */
    private Map<String, String> oauthDeployment() {
        Map<String, String> env = new HashMap<>();
        env.put(AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER);
        env.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com");
        env.put(AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE,
                AssistantCredentialSource.OAUTH.wireValue());
        env.put(SqliteAssistantConsentStore.DIRECTORY_VARIABLE, consentDirectory.toString());
        env.put(AssistantComposition.DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE, DEVICE_ENDPOINT);
        env.put(AssistantComposition.TOKEN_ENDPOINT_VARIABLE, TOKEN_ENDPOINT);
        env.put(AssistantComposition.OAUTH_CLIENT_ID_VARIABLE, "ravenroot-device-client");
        return env;
    }

    /**
     * An egress client supplier that fails if it is ever asked for one.
     *
     * <p>Used by every test that must not reach the provider. It is an assertion in its own right:
     * the composition must not build an HTTP client for a deployment that offers no connection.</p>
     */
    private static java.util.function.Supplier<HttpClient> noProviderReached() {
        return () -> {
            throw new AssertionError("no egress client should be built for this deployment");
        };
    }

    /** A client for a connection that is built but never asked to speak. */
    private static java.util.function.Supplier<HttpClient> unusedClient() {
        return () -> new ScriptedHttpClient(new ArrayList<>(), new ArrayList<>(), new ArrayDeque<>());
    }
}
