package ai.ravenroot.server.assistant;

import ai.ravenroot.core.security.OutboundHttpPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The operator's assistant configuration, read from the environment and from nowhere else under the
 * per-author credential and operator-egress contracts.
 *
 * <h2>"From nowhere else" is the point, not a detail</h2>
 * <p>Every field here comes from a {@code RAVENROOT_ASSISTANT_*} environment variable, following the
 * convention {@code RAVENROOT_NODE_PACKAGES}, {@code RAVENROOT_UNKNOWN_BEHAVIOR} and
 * {@code RAVENROOT_OTEL_*} already established. None of it is reachable from a graph, a request
 * payload, a chat turn or browser storage. That matters most for {@link #endpoint()}: a destination a
 * user could influence is content-driven egress, which the operator egress policy closes for every
 * endpoint, including MCP.
 * The provider host is operator-registered, checked
 * against {@link #egressPolicy()}, and resolved through the SEC-10 resolver like every other outbound
 * destination.</p>
 *
 * <p>{@link #fromEnvironment(Map)} takes the environment as a parameter rather than calling
 * {@code System.getenv()} itself, for the same reason {@code UnknownBehaviorPolicy.fromEnvironment}
 * does: a pure function of a supplied map is testable, and every inert state below can therefore be
 * provoked in a unit test rather than only by an operator with a shell.</p>
 *
 * <h2>Deny-by-default reach, and why the panel can be inert on a fully-keyed deployment</h2>
 * <p>{@code RAVENROOT_ASSISTANT_ALLOWED_HOSTS} has no default. An operator who sets a provider and a
 * key but no allowlist gets {@code host-not-allowlisted}, not a working panel. The two-key design is:
 * "the user brings the subscription; the operator
 * owns reach" — and it is stated here because it will otherwise read as a bug on first run. The panel
 * says which of the two keys is missing, which is the whole reason the inert reasons are
 * distinguished rather than pooled.</p>
 */
public record AssistantConfiguration(boolean enabled, String providerId, URI endpoint, String model,
                                     AssistantCredential credential, OutboundHttpPolicy egressPolicy,
                                     Duration timeout, int maxOutputTokens, int maxToolIterations,
                                     AssistantCredentialSource credentialSource,
                                     boolean allowLocalHttp) {

    /** Present and set to anything other than {@code false} means the deployment offers the service. */
    public static final String ENABLED_VARIABLE = "RAVENROOT_ASSISTANT_ENABLED";
    /** Selects the dedicated authoring-assistant provider adapter. */
    public static final String PROVIDER_VARIABLE = "RAVENROOT_ASSISTANT_PROVIDER";
    /** The provider credential. Read here, wrapped immediately, and never widened back to a String. */
    public static final String API_KEY_VARIABLE = "RAVENROOT_ASSISTANT_API_KEY";
    /** The model id, e.g. {@code claude-opus-5}. */
    public static final String MODEL_VARIABLE = "RAVENROOT_ASSISTANT_MODEL";
    /** Operator-only outbound allowlist for the provider host. No default: deny by default. */
    public static final String ALLOWED_HOSTS_VARIABLE = "RAVENROOT_ASSISTANT_ALLOWED_HOSTS";
    /** Operator-only outbound port allowlist. Local llama.cpp ports must be named explicitly. */
    public static final String ALLOWED_PORTS_VARIABLE = "RAVENROOT_ASSISTANT_ALLOWED_PORTS";
    /** Optional endpoint override, for a gateway, regional host or local model server. */
    public static final String ENDPOINT_VARIABLE = "RAVENROOT_ASSISTANT_ENDPOINT";
    /** Explicit opt-in for credential-free HTTP to a narrowly local endpoint. */
    public static final String ALLOW_LOCAL_HTTP_VARIABLE = "RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP";
    /** Per-request wall clock bound on the provider call. */
    public static final String TIMEOUT_VARIABLE = "RAVENROOT_ASSISTANT_TIMEOUT_SECONDS";
    /**
     * Which credential model this deployment uses: {@code api-key} (default) or {@code oauth}.
     *
     * <p>An explicit switch rather than a preference order — see {@link AssistantCredentialSource} for
     * why a fallback between the two is forbidden rather than merely discouraged.</p>
     */
    public static final String CREDENTIAL_SOURCE_VARIABLE = "RAVENROOT_ASSISTANT_CREDENTIAL_SOURCE";

    public static final String ANTHROPIC_PROVIDER = "anthropic";
    public static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";
    public static final URI ANTHROPIC_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    /**
     * The model used when the operator names a provider but no model.
     *
     * <p>Pinned rather than tracked: a model id that drifts with the calendar changes configuration
     * without operator action. An operator moves off this by setting {@link #MODEL_VARIABLE}.</p>
     */
    public static final String ANTHROPIC_DEFAULT_MODEL = "claude-opus-5";

    /**
     * Output ceiling for one provider turn.
     *
     * <p>Deliberately generous. On the current Anthropic models thinking is on by default and
     * {@code max_tokens} bounds thinking <em>plus</em> visible text together, so a ceiling sized for
     * the answer alone truncates the answer. A truncated turn is a real outcome this service reports
     * rather than hides (see {@code AssistantOutcome.Reply#truncated}), but it should be the
     * exception, not what a default produces.</p>
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 16_000;

    /**
     * How many provider turns one author message may consume before the loop stops.
     *
     * <p>The assistant is read-only, so a tool loop cannot damage anything — but it can spend the
     * author's subscription without bound, and an unbounded loop against a paid API is a defect
     * whether or not it is a security one. Exhausting this is a named failure, never a silent stop.</p>
     */
    public static final int DEFAULT_MAX_TOOL_ITERATIONS = 8;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * The compatibility shape, defaulting the credential source to the operator key.
     *
     * <p>Kept so that adding a credential model did not become an edit to every construction site: a
     * caller that never heard of OAuth gets exactly the behaviour it had before.</p>
     */
    public AssistantConfiguration(boolean enabled, String providerId, URI endpoint, String model,
                                  AssistantCredential credential, OutboundHttpPolicy egressPolicy,
                                  Duration timeout, int maxOutputTokens, int maxToolIterations) {
        this(enabled, providerId, endpoint, model, credential, egressPolicy, timeout, maxOutputTokens,
                maxToolIterations, AssistantCredentialSource.API_KEY, false);
    }

    /** The canonical compatibility shape for callers that do not opt into local HTTP. */
    public AssistantConfiguration(boolean enabled, String providerId, URI endpoint, String model,
                                  AssistantCredential credential, OutboundHttpPolicy egressPolicy,
                                  Duration timeout, int maxOutputTokens, int maxToolIterations,
                                  AssistantCredentialSource credentialSource) {
        this(enabled, providerId, endpoint, model, credential, egressPolicy, timeout, maxOutputTokens,
                maxToolIterations, credentialSource, false);
    }

    public AssistantConfiguration {
        java.util.Objects.requireNonNull(egressPolicy, "egressPolicy");
        credentialSource = credentialSource == null
                ? AssistantCredentialSource.API_KEY : credentialSource;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        maxToolIterations = maxToolIterations > 0 ? maxToolIterations : DEFAULT_MAX_TOOL_ITERATIONS;
    }

    /** The configuration a deployment that has set nothing gets: present, offered, and inert. */
    public static AssistantConfiguration disabled() {
        return new AssistantConfiguration(true, null, null, null, null,
                OutboundHttpPolicy.disabled(), DEFAULT_TIMEOUT, DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_TOOL_ITERATIONS, AssistantCredentialSource.API_KEY, false);
    }

    public static AssistantConfiguration fromEnvironment(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        boolean enabled = !"false".equalsIgnoreCase(trimmed(env.get(ENABLED_VARIABLE)));
        String providerId = lower(trimmed(env.get(PROVIDER_VARIABLE)));
        AssistantCredentialSource source =
                AssistantCredentialSource.parse(env.get(CREDENTIAL_SOURCE_VARIABLE));
        // In OAuth mode the operator key is not read at all. Not read rather than
        // read-and-ignored, so a deployment that has moved to per-author credentials cannot quietly
        // start serving turns on a leftover key if some later branch consults `credential`.
        AssistantCredential credential = source == AssistantCredentialSource.API_KEY
                ? AssistantCredential.ofNullable(env.get(API_KEY_VARIABLE))
                : null;
        var egress = OutboundHttpPolicy.fromCommaSeparated(
                trimmed(env.get(ALLOWED_HOSTS_VARIABLE)),
                trimmed(env.get(ALLOWED_PORTS_VARIABLE)), 0);
        String model = trimmed(env.get(MODEL_VARIABLE));
        URI endpoint = endpointFor(providerId, trimmed(env.get(ENDPOINT_VARIABLE)));
        boolean allowLocalHttp = "true".equalsIgnoreCase(trimmed(env.get(ALLOW_LOCAL_HTTP_VARIABLE)));
        if (ANTHROPIC_PROVIDER.equals(providerId) && model == null) {
            model = ANTHROPIC_DEFAULT_MODEL;
        }
        if (ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID.equals(providerId)) {
            model = model == null
                    ? ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID
                    : model;
        }
        return new AssistantConfiguration(enabled, providerId, endpoint, model, credential, egress,
                seconds(trimmed(env.get(TIMEOUT_VARIABLE))), DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_TOOL_ITERATIONS, source, allowLocalHttp);
    }

    /**
     * What this deployment says about itself, evaluated most-fundamental-first.
     *
     * <p>The order is {@code assistant-session.js}'s {@code INERT_REASON_ORDER}, and it is a contract
     * rather than an implementation accident: the three operator-actionable reasons and the one
     * user-actionable reason are actionable by different people, so the panel must always name the
     * thing that has to be fixed <em>first</em> rather than whichever check happened to fail last.</p>
     */
    public AssistantAvailability availability() {
        return availabilityWith(credential);
    }

    /**
     * The same evaluation, against a credential resolved for one author.
     *
     * <p>The deployment-level checks are identical and still run first: they are facts about the
     * deployment, true or false for everyone at once, and the order remains
     * {@code assistant-session.js}'s {@code INERT_REASON_ORDER} for the reason that has always applied
     * — the reasons are actionable by different people, so the one that must be fixed first is named
     * first. Only the last check varies, and only in who it points at.</p>
     */
    public AssistantAvailability availabilityWith(AssistantCredential resolved) {
        if (!enabled) {
            return AssistantAvailability.inert(AssistantAvailability.InertReason.SERVICE_UNAVAILABLE,
                    "This deployment has disabled the assistant service.");
        }
        if (providerId == null || endpoint == null || model == null) {
            return AssistantAvailability.inert(AssistantAvailability.InertReason.NO_PROFILE,
                    "No assistant provider profile is configured.");
        }
        if (ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID.equals(providerId)) {
            // The scripted adapter has no host to allowlist and nothing to authenticate to. Running
            // the remaining two checks against it would report an operator gap that does not exist and
            // would make the local development path inert for the wrong reason.
            return AssistantAvailability.ready(providerId);
        }
        if (insecureHttpRefused(resolved)) {
            return AssistantAvailability.inert(AssistantAvailability.InertReason.INSECURE_REFUSED,
                    "The configured HTTP provider endpoint is not eligible for the local plaintext exception.");
        }
        if (!hostAllowlisted()) {
            return AssistantAvailability.inert(AssistantAvailability.InertReason.HOST_NOT_ALLOWLISTED,
                    "The configured provider host is not permitted by this deployment's outbound policy.");
        }
        if (resolved == null && !OPENAI_COMPATIBLE_PROVIDER.equals(providerId)) {
            // Which of the two this is depends on whose credential is missing, and that is a
            // deployment decision rather than a guess about the value.
            //
            // NOT_SIGNED_IN would be wrong here: the panel renders it as "Your Ravenroot session is
            // not authenticated. Sign in to Ravenroot
            // and try again" -- a sentence an author reaching this branch has already satisfied,
            // since they were authenticated well enough for their subject to be resolved. Two
            // different facts had been projected onto one wire state, and the visible result was a
            // false instruction. NOT_LINKED is the fact this branch actually establishes: the
            // deployment is whole, the reader is authenticated to Ravenroot, and their own
            // connection to the provider is what is outstanding.
            return credentialSource == AssistantCredentialSource.OAUTH
                    ? AssistantAvailability.inert(AssistantAvailability.InertReason.NOT_LINKED,
                            "This author has not connected to the model provider.")
                    : AssistantAvailability.inert(AssistantAvailability.InertReason.NO_PROFILE,
                            "No assistant provider credential is configured.");
        }
        return AssistantAvailability.ready(providerId);
    }

    /**
     * Everything the operator owns, checked without asking about a credential.
     *
     * <p>Exists because in OAuth mode the credential is nobody's at composition time, so the
     * composition root cannot use {@link #availability()} to decide whether the deployment is worth
     * wiring — it would always answer {@code not-signed-in} and no adapter would ever be built. This
     * is the honest question at that moment: <em>is the operator's half in place?</em></p>
     */
    public boolean reachReady() {
        if (!enabled || providerId == null || endpoint == null || model == null) {
            return false;
        }
        return ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID.equals(providerId)
                || (!insecureHttpRefused(credential) && hostAllowlisted());
    }

    /** Whether the configured endpoint passes the operator allowlist and the SEC-10 literal filter. */
    public boolean hostAllowlisted() {
        if (endpoint == null) {
            return false;
        }
        try {
            egressPolicy.requireAllowed(endpoint);
            return true;
        } catch (SecurityException refused) {
            return false;
        }
    }

    private static URI endpointFor(String providerId, String override) {
        if (override != null) {
            URI parsed;
            try {
                parsed = new URI(override);
            } catch (java.net.URISyntaxException malformed) {
                throw new IllegalArgumentException(ENDPOINT_VARIABLE + " is not a valid URI", malformed);
            }
            String scheme = String.valueOf(parsed.getScheme()).toLowerCase(java.util.Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme))) {
                throw new IllegalArgumentException(ENDPOINT_VARIABLE + " must use https or explicitly allowed local http");
            }
            if (parsed.getHost() == null || parsed.getHost().isBlank()) {
                throw new IllegalArgumentException(ENDPOINT_VARIABLE + " must declare a host");
            }
            if (parsed.getUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
                throw new IllegalArgumentException(ENDPOINT_VARIABLE
                        + " must not contain credentials, a query, or a fragment");
            }
            return parsed;
        }
        if (ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID.equals(providerId)) {
            // Not a destination. Deliberately a non-routable scheme, so anything that mistakes it for
            // a URL and tries to open it fails immediately rather than resolving somewhere.
            return URI.create("scripted:///no-network");
        }
        return ANTHROPIC_PROVIDER.equals(providerId) ? ANTHROPIC_ENDPOINT : null;
    }

    /**
     * Plain HTTP is a deliberately narrow local exception: explicit switch, exact local host, and
     * no credential now or later. The normal host/port allowlist is checked separately afterwards.
     */
    private boolean insecureHttpRefused(AssistantCredential resolved) {
        if (endpoint == null || !"http".equalsIgnoreCase(endpoint.getScheme())) {
            return false;
        }
        return !allowLocalHttp || !isLocalHttpHost(endpoint.getHost())
                || resolved != null || credentialSource == AssistantCredentialSource.OAUTH;
    }

    private static boolean isLocalHttpHost(String host) {
        String value = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if ("localhost".equals(value) || value.endsWith(".localhost")
                || "host.docker.internal".equals(value) || "::1".equals(value)) {
            return true;
        }
        try {
            // Numeric-only: never resolve an operator-provided name merely to classify it as local.
            if (!value.matches("[0-9.]+")) {
                return false;
            }
            return java.net.InetAddress.getByName(value).isLoopbackAddress();
        } catch (java.net.UnknownHostException malformedLiteral) {
            return false;
        }
    }

    private static Duration seconds(String value) {
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notANumber) {
            return DEFAULT_TIMEOUT;
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Redacted by construction: the record's generated {@code toString()} would print
     * {@link AssistantCredential}'s own redaction, but it is overridden anyway so that adding a raw
     * secret field later cannot quietly start printing it.
     */
    @Override
    public String toString() {
        return "AssistantConfiguration[enabled=" + enabled + ", provider=" + providerId
                + ", endpoint=" + endpoint + ", model=" + model
                + ", credentialSource=" + credentialSource
                + ", credential=" + (credential == null ? "absent" : credential)
                + ", timeout=" + timeout + "]";
    }
}
