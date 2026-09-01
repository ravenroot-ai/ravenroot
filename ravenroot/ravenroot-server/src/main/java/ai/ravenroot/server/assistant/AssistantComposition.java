package ai.ravenroot.server.assistant;

import ai.ravenroot.server.assistant.oauth.AssistantDeviceAuthorization;
import ai.ravenroot.server.assistant.oauth.DeviceFlowAssistantConnection;
import ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The shipped composition root's assistant, assembled from the operator's environment.
 *
 * <h2>Why composition is centralized here</h2>
 * <p>The per-author OAuth path comprises the token store, device grant, connection port and route,
 * and consent register. Each part must be wired together. Calling
 * {@link AssistantService#fromEnvironment(Map)} delegates to
 * {@code fromEnvironment(environment, null, null)}. Consent store {@code null}, token store
 * {@code null}, and no production caller for {@link AssistantService#withConnection} at all.</p>
 *
 * <p>The consequences of missing composition are concrete: a deployment naming a network provider
 * <em>throws at startup</em> from
 * {@code requireConsent(null)}, and an OAuth deployment had nowhere to put a redeemed token, so the
 * device flow could not finish.</p>
 *
 * <p>This class keeps that wiring in one place a reader can check, rather than four lines spread
 * through {@code RavenrootServerMain}. It owns the two stores' lifecycles and hands back a service
 * the server constructor takes as-is.</p>
 *
 * <h2>Which token store, and why this one is not a choice</h2>
 * <p>{@link AssistantTokenStore}'s own contract fixes two deployment shapes: a <b>local single-user
 * composition</b> may bind a keychain-backed store, because the OS session and the token's owner are
 * one principal; a <b>multi-tenant server composition</b> may bind <em>only</em> a session-scoped
 * in-memory store, because a shared server's keychain belongs to the service account, so persisting
 * there would put one author's provider token in the operator's vault.</p>
 *
 * <p><b>This is the multi-tenant server composition, so it binds
 * {@link InMemoryAssistantTokenStore}.</b> {@code RavenrootServerMain} is the shipped HTTP server: it
 * binds a socket, authenticates callers through {@code RequestAuthenticator}, and keys everything it
 * does per {@code RequestContext#subject()} — several authors at once, on a host whose OS account is
 * the operator's, not any author's. The keychain shape is not merely unnecessary here; it is the
 * combination that contract says must have no code path. It also has no implementation in this build:
 * {@code grep -rn "implements AssistantTokenStore"} over {@code ravenroot/} returns
 * {@link InMemoryAssistantTokenStore} and nothing else; see that class's own note. The server therefore
 * has no persistent token-store implementation it could wire accidentally.</p>
 *
 * <p>The cost is stated rather than hidden: tokens are lost on restart and every author signs in
 * again. This is what lets the service hold no refresh token, rotation state or revocation list.</p>
 *
 * <h2>When the consent register is opened</h2>
 * <p>Only when this deployment names a provider that reaches the network. An inert deployment — no
 * {@code RAVENROOT_ASSISTANT_PROVIDER}, or the scripted one — gets no database file and no directory,
 * exactly as today. That predicate is deliberately a <b>superset</b> of the cases in which
 * {@link AssistantService#fromEnvironment} actually demands a store: it opens the register for a
 * provider id this build does not recognise, and for one whose host is not allowlisted. The superset
 * is the safe direction. Opening it where it turns out not to be needed costs an empty SQLite file;
 * not opening it where it is needed causes a boot refusal.</p>
 */
public final class AssistantComposition implements AutoCloseable {

    /**
     * RFC 8628's device-authorization endpoint, for the provider an author signs into.
     *
     * <p><b>No default, and that is deliberate rather than unfinished.</b>
     * {@link AssistantConfiguration#ENDPOINT_VARIABLE} defaults the <em>messages</em> endpoint because
     * that URL is published, stable and exercised by this build. Neither OAuth endpoint is: no test in
     * this repository has ever made a real request to one, so shipping a guessed URL as a default
     * would turn an unverified constant into something an operator reasonably assumes was tested. That
     * reasoning is {@link AssistantDeviceAuthorization}'s, and this class does not revisit it — it
     * supplies the configuration path that class said was missing, not a value.</p>
     */
    public static final String DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE =
            "RAVENROOT_ASSISTANT_DEVICE_AUTHORIZATION_ENDPOINT";

    /** RFC 8628's token endpoint, where a grant is redeemed. No default, for the reason above. */
    public static final String TOKEN_ENDPOINT_VARIABLE = "RAVENROOT_ASSISTANT_TOKEN_ENDPOINT";

    /**
     * The provider's public client identifier.
     *
     * <p>Not a secret: RFC 8628 device clients are public clients and hold no client secret. It is
     * therefore an ordinary configuration string here, and is deliberately <em>not</em> wrapped in
     * {@link AssistantCredential} — pretending it were a secret would dilute what that type means.</p>
     */
    public static final String OAUTH_CLIENT_ID_VARIABLE = "RAVENROOT_ASSISTANT_OAUTH_CLIENT_ID";

    /**
     * How long a redeemed token is honoured, in minutes.
     *
     * <p><b>This one does have a default, and the difference from the two endpoints above is not
     * arbitrary.</b> An endpoint is a fact about the provider that this build cannot check, so
     * guessing it is a claim. A session lifetime is a policy about this deployment, it cannot be
     * wrong in the same way, and the store it feeds forgets everything on restart regardless — so the
     * worst a default can do is ask an author to sign in again.</p>
     */
    public static final String SESSION_MINUTES_VARIABLE = "RAVENROOT_ASSISTANT_SESSION_MINUTES";

    /**
     * Eight hours: about one working day. The token cannot outlive the process whatever this says,
     * because the store that holds it is in memory — so this only bounds a session inside one run.
     */
    public static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofHours(8);

    private final AssistantService service;
    private final AutoCloseable consentRegister;
    private final List<String> startupNotes;

    private AssistantComposition(AssistantService service, AutoCloseable consentRegister,
                                 List<String> startupNotes) {
        this.service = service;
        this.consentRegister = consentRegister;
        this.startupNotes = List.copyOf(startupNotes);
    }

    /** The composition root's entry point: environment in, a wired assistant out. */
    public static AssistantComposition fromEnvironment(Map<String, String> environment) {
        return fromEnvironment(environment,
                ai.ravenroot.core.security.egress.EgressHttpClients::create);
    }

    /**
     * The same assembly against a supplied egress client, so the whole path can be exercised without
     * a network.
     *
     * <p>The supplier is called at most once, and only when a connection is actually built — a
     * deployment that configures no OAuth endpoints constructs no client at all, which is the same
     * "no object capable of outbound calls exists" property {@link AssistantService#fromEnvironment}
     * keeps for the provider adapter.</p>
     */
    static AssistantComposition fromEnvironment(Map<String, String> environment,
                                                Supplier<HttpClient> egressClients) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        Objects.requireNonNull(egressClients, "egressClients");
        AssistantConfiguration configuration = AssistantConfiguration.fromEnvironment(env);
        List<String> notes = new ArrayList<>();

        // Every configuration refusal is raised here, before anything is opened or constructed, so a
        // malformed variable cannot leave a half-built composition behind for a caller to unwind.
        DeviceEndpoints endpoints = DeviceEndpoints.fromEnvironment(env);
        Duration sessionLifetime = endpoints == null ? null : sessionLifetime(env);

        SqliteAssistantConsentStore consent = reachesTheNetwork(configuration)
                ? SqliteAssistantConsentStore.fromEnvironment(env)
                : null;
        InMemoryAssistantTokenStore tokens = new InMemoryAssistantTokenStore();
        AssistantService service;
        try {
            service = AssistantService.fromEnvironment(env, consent, tokens);
        } catch (RuntimeException | Error refused) {
            closeQuietly(consent);
            throw refused;
        }

        if (endpoints != null && service.expectsPerAuthorConnection()) {
            service = service.withConnection(new DeviceFlowAssistantConnection(
                    new AssistantDeviceAuthorization(egressClients.get(),
                            endpoints.deviceAuthorization(), endpoints.token(), endpoints.clientId(),
                            configuration.egressPolicy(), configuration.timeout()),
                    tokens, sessionLifetime));
        } else if (endpoints != null) {
            // Configured and unusable. The panel reports explicitly that the three variables took no
            // effect. Deliberately not a startup refusal: the reasons this branch is reached --
            // API-key mode, or an operator half of the configuration still missing -- are ones the
            // panel already reports as named, fixable inert states, and turning a soft report into a
            // dead process would be worse than the named, fixable inert state.
            notes.add(DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE + " and " + TOKEN_ENDPOINT_VARIABLE
                    + " are set but no connection was offered: this deployment has no adapter waiting "
                    + "for an author to sign in. Either " + AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE
                    + " is not " + AssistantCredentialSource.OAUTH.wireValue()
                    + ", or the operator half is incomplete ("
                    + AssistantConfiguration.ENABLED_VARIABLE + ", "
                    + AssistantConfiguration.PROVIDER_VARIABLE + ", "
                    + AssistantConfiguration.ALLOWED_HOSTS_VARIABLE
                    + "). The panel reports which of the two it is.");
        }
        return new AssistantComposition(service, consent, notes);
    }

    /** The wired service. Never null; an unconfigured deployment gets an inert one. */
    public AssistantService service() {
        return service;
    }

    /**
     * Operator-facing observations made while assembling, in the order they were made.
     *
     * <p>Returned rather than printed, so this class writes to no stream and a test can read them.
     * The composition root prints them; see {@code RavenrootServerMain}.</p>
     */
    public List<String> startupNotes() {
        return startupNotes;
    }

    /** Closes the consent register if one was opened. The token store holds no resource. */
    @Override
    public void close() {
        closeQuietly(consentRegister);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Whether this deployment names a provider that opens a socket.
     *
     * <p>Keyed on the two ids this build knows rather than on {@code AssistantService}'s private
     * adapter registry, which is not visible here. The asymmetry is on purpose and is why this
     * answers {@code true} for an unrecognised id: see the class comment on why a superset is the
     * safe direction.</p>
     */
    private static boolean reachesTheNetwork(AssistantConfiguration configuration) {
        String providerId = configuration.providerId();
        return providerId != null && !ScriptedAssistantProvider.ID.equals(providerId);
    }

    private static Duration sessionLifetime(Map<String, String> env) {
        String configured = trimmed(env.get(SESSION_MINUTES_VARIABLE));
        if (configured == null) {
            return DEFAULT_SESSION_LIFETIME;
        }
        long minutes;
        try {
            minutes = Long.parseLong(configured);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(SESSION_MINUTES_VARIABLE
                    + " must be a positive whole number of minutes");
        }
        if (minutes <= 0) {
            // DeviceFlowAssistantConnection refuses a non-positive lifetime for the same reason, and
            // refusing here names the variable instead of the argument.
            throw new IllegalArgumentException(SESSION_MINUTES_VARIABLE
                    + " must be a positive whole number of minutes");
        }
        return Duration.ofMinutes(minutes);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort: this runs either on shutdown or while unwinding a startup failure, and in
            // both cases the reason we are here matters more than the close.
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * The three operator-supplied halves of a device flow, present together or not at all.
     *
     * <p><b>A partial set is refused rather than ignored.</b> An operator who set one of these
     * intended a connection; skipping silently would produce a panel reporting that no connection
     * path is configured, an indistinguishable-from-unbuilt state that would also look like a typo in
     * a variable name.</p>
     */
    private record DeviceEndpoints(URI deviceAuthorization, URI token, String clientId) {

        static DeviceEndpoints fromEnvironment(Map<String, String> env) {
            String device = trimmed(env.get(DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE));
            String token = trimmed(env.get(TOKEN_ENDPOINT_VARIABLE));
            String clientId = trimmed(env.get(OAUTH_CLIENT_ID_VARIABLE));
            if (device == null && token == null && clientId == null) {
                return null;
            }
            List<String> missing = new ArrayList<>();
            if (device == null) missing.add(DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE);
            if (token == null) missing.add(TOKEN_ENDPOINT_VARIABLE);
            if (clientId == null) missing.add(OAUTH_CLIENT_ID_VARIABLE);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("an assistant device flow needs all three of "
                        + DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE + ", " + TOKEN_ENDPOINT_VARIABLE
                        + " and " + OAUTH_CLIENT_ID_VARIABLE + "; missing: "
                        + String.join(", ", missing));
            }
            return new DeviceEndpoints(https(device, DEVICE_AUTHORIZATION_ENDPOINT_VARIABLE),
                    https(token, TOKEN_ENDPOINT_VARIABLE), clientId);
        }

        /**
         * HTTPS is required at parse time; the operator allowlist is not checked here.
         *
         * <p>Not an omission. {@link AssistantDeviceAuthorization} checks every request against the
         * policy it was given, and its own comment says why that check lives there rather than only
         * at configuration time — the class can be constructed directly, and a credential exchange is
         * the last place to rely on a caller having checked something. Re-checking here would add a
         * second answer to the same question without removing the first.</p>
         */
        private static URI https(String value, String variable) {
            URI parsed;
            try {
                parsed = URI.create(value);
            } catch (IllegalArgumentException notAUri) {
                // Deliberately not carrying the cause: the message repeats the raw value, and the only
                // useful answer at startup is that this setting is unusable.
                throw new IllegalArgumentException(variable + " must be an https URL");
            }
            if (!"https".equals(String.valueOf(parsed.getScheme()).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(variable + " must be an https URL");
            }
            return parsed;
        }
    }
}
