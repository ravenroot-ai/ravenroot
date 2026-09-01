package ai.ravenroot.server.assistant;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.assistant.provider.AssistantProviderException;
import ai.ravenroot.server.assistant.tools.AssistantInternalContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The author's delegate, server-side (ADR 0025).
 *
 * <h2>Where the loop lives, and why here rather than in the adapter</h2>
 * <p>The tool loop — ask the provider, run whatever reads it asks for, ask again — is this class's,
 * not the provider adapter's. Every decision it makes is about the author's session rather than about
 * a wire format: how many turns one message may consume, what a denied read does to the turn, whether
 * a truncated answer is worth showing. A per-provider copy of those decisions would be one copy per
 * provider to keep agreeing, and the second provider is where they would stop agreeing.</p>
 *
 * <h2>What this class guarantees</h2>
 * <ul>
 *   <li><b>Every internal read passes the author's own authorization.</b> The tool context is built
 *       per request from the same {@link AuthorizedRavenrootApplication} the HTTP handlers use, with
 *       the same {@link RequestContext}. An {@code AuthorizationDeniedException} raised inside a tool
 *       is <em>not</em> caught here — see {@link #send}.</li>
 *   <li><b>The turn produces words, one inert proposal, or a named failure.</b> Neither textual
 *       outcome can be blank, so there is no expression in this method that evaluates to an empty
 *       assistant turn.</li>
 *   <li><b>Nothing is sent when the panel is inert.</b> {@link #availability()} is consulted before a
 *       provider exists to call, and the route refuses the turn rather than composing one.</li>
 * </ul>
 *
 * <h2>Instruction-pack boundary</h2>
 * <p>What the model is told, and which tools it gets. {@link #instructions} is a placeholder that says
 * only what is structurally true of this deployment; a versioned instruction-and-knowledge pack and
 * the final tool set are supplied through this seam. The seam is
 * deliberate: swapping either is a change to two constructor arguments, not to this loop.</p>
 */
public final class AssistantService {

    /** Smaller than the provider-turn ceiling: malformed proposals cannot consume the whole loop. */
    static final int MAX_PROPOSAL_RECOVERIES = 2;
    private static final String MIXED_PROPOSAL_FEEDBACK = "{\"code\":\"GRAPH_PROPOSAL_NOT_ISOLATED\","
            + "\"instruction\":\"After using any read results, call the proposal tool once and as "
            + "the only tool call. The proposal remains unapplied until the author confirms it.\"}";

    private final AssistantConfiguration configuration;
    private final AssistantProvider provider;

    /**
     * The register the composer reads before every request. It is a field, not merely a construction
     * gate, because {@link #send} consults it for each provider request.
     */
    private final AssistantConsentStore consent;

    /** Where signed-in authors' tokens live. Null in API-key deployments, which never ask. */
    private final AssistantTokenStore tokens;

    /**
     * The adapter kind to build per author, set only in OAuth mode.
     *
     * <p>Non-null here means "this deployment egresses, has passed both boot gates, and is waiting
     * for an author to sign in" -- a state that did not exist before per-author credentials.</p>
     */
    private final Adapter perAuthorAdapter;

    /**
     * How an author connects their own account, or null when this deployment offers no way.
     *
     * <p>The device-flow endpoints have no defaults; see {@code AssistantDeviceAuthorization}'s note
     * on why guessing them would be worse than leaving them absent. {@link AssistantComposition}
     * supplies a connection only when the operator provides the complete endpoint configuration.
     * Otherwise {@link #connectable} reports the operator's gap, so no author sees a control that
     * cannot work.</p>
     */
    private final ai.ravenroot.server.assistant.oauth.AssistantConnection connection;

    /**
     * The refusal both construction paths raise, stated once.
     *
     * <p>Extracted rather than duplicated because both the immediate and deferred per-author paths
     * use the same gate, and two copies of a security refusal can drift
     * into disagreeing about what they refuse.</p>
     */
    private static final String CONSENT_REFUSAL = "refusing to build an assistant around a provider that "
            + "egresses while no consent store is present. Per-user, per-provider consent is required "
            + "before any context is sent; token and egress posture and per-provider terms "
            + "verification must also be configured. Set " + AssistantConfiguration.PROVIDER_VARIABLE
            + "=" + ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID
            + " to run the assistant without a network provider.";

    /**
     * Builds a service with no consent store, which is therefore only legal around a provider that
     * does not egress.
     *
     * @param provider {@code null} when the deployment is inert. That is representable on purpose: a
     *                 service with no provider is the normal state of an unconfigured deployment, and
     *                 modelling it as {@code null} here rather than as a "disabled provider"
     *                 implementation means there is no object that could accidentally acquire the
     *                 ability to call out.
     * @throws IllegalStateException if {@code provider} egresses — see the canonical constructor
     */
    public AssistantService(AssistantConfiguration configuration, AssistantProvider provider) {
        this(configuration, provider, null);
    }

    /**
     * Canonical constructor, and <b>the consent gate</b>.
     *
     * <h4>Why the check lives here rather than only in {@link #fromEnvironment}</h4>
     * <p>Because a gate on the composition root is a gate on one code path, and this class had a
     * public two-argument constructor sitting beside it — an unguarded second door that let any caller
     * assemble an egressing service directly. The property wanted is not "the factory refuses" but
     * "the type cannot exist": there is now no way to obtain an {@code AssistantService} holding an
     * outbound-capable adapter without a consent store, whichever constructor you reach for.</p>
     *
     * <h4>Why it keys on {@link AssistantProvider#egresses()} and not on the provider id</h4>
     * <p>The previous shape checked for the Anthropic provider specifically, after an early return
     * that exempted the scripted adapter. A second network provider added by copying that early
     * return — the most visible pattern in the file — bypassed consent, and no test caught it. Keying
     * on a property every adapter must declare means the exemption belongs to <em>adapters that do not
     * egress</em>, not to a list of names someone has to remember to keep current.</p>
     *
     * <p>The residual is honest and worth stating: this cannot be widened by configuration, and it can
     * be widened by an edit — an adapter author who writes {@code egresses() == false} while opening a
     * socket defeats it. What the design removes is the accidental path, not the deliberate one.</p>
     *
     * @throws IllegalStateException when {@code provider} egresses and {@code consent} is null
     */
    public AssistantService(AssistantConfiguration configuration, AssistantProvider provider,
                            AssistantConsentStore consent) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (provider != null && provider.egresses() && consent == null) {
            throw new IllegalStateException(CONSENT_REFUSAL);
        }
        this.provider = provider;
        this.consent = consent;
        this.tokens = null;
        this.perAuthorAdapter = null;
        this.connection = null;
    }

    private AssistantService(AssistantConfiguration configuration, AssistantProvider provider,
                             AssistantConsentStore consent, AssistantTokenStore tokens,
                             Adapter perAuthorAdapter,
                             ai.ravenroot.server.assistant.oauth.AssistantConnection connection) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (provider != null && provider.egresses() && consent == null) {
            throw new IllegalStateException(CONSENT_REFUSAL);
        }
        if (perAuthorAdapter != null && consent == null) {
            // The same gate, for the deferred-construction path. Without this an OAuth deployment
            // would pass composition with no consent store and only fail once an author signed in,
            // turning a boot-time fact into a runtime surprise.
            throw new IllegalStateException(CONSENT_REFUSAL);
        }
        this.provider = provider;
        this.consent = consent;
        this.tokens = tokens;
        this.perAuthorAdapter = perAuthorAdapter;
        if (connection != null && perAuthorAdapter == null) {
            // A connection with nothing to authenticate would let an author complete a sign-in that
            // no turn could ever use: the panel would report itself connected and every question
            // would still be refused. Refusing at composition keeps that from being a runtime
            // surprise, which is the same shape as the consent gate above.
            throw new IllegalStateException("refusing to offer an assistant connection in a "
                    + "deployment that does not use per-author credentials: nothing would consume "
                    + "the obtained token. Set " + AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE
                    + "=" + AssistantCredentialSource.OAUTH.wireValue() + " to use it.");
        }
        this.connection = connection;
    }

    /**
     * The same service, able to conduct connections.
     *
     * <p>A wither rather than a factory parameter preserves existing construction sites. It is the
     * seam {@link AssistantComposition} uses when an operator supplies the device-flow endpoints, and
     * the seam every test that exercises the route uses.</p>
     *
     * @throws IllegalStateException when this deployment does not use per-author credentials
     */
    public AssistantService withConnection(
            ai.ravenroot.server.assistant.oauth.AssistantConnection offered) {
        return new AssistantService(configuration, provider, consent, tokens, perAuthorAdapter,
                java.util.Objects.requireNonNull(offered, "offered"));
    }

    /**
     * Whether {@link #withConnection} would be accepted — that is, whether this deployment has an
     * adapter waiting for an author to sign in.
     *
     * <p>Exists because the composition root has to ask before it offers a connection, and the only
     * alternative was to re-derive the answer from the configuration: OAuth mode <em>and</em>
     * {@code reachReady()} <em>and</em> the provider id being one this build wires. That is three
     * facts the factory above already combined, and a second copy of them is a second copy that can
     * drift. This reads the same field {@code withConnection}'s refusal reads, so the two cannot
     * disagree by construction rather than by discipline.</p>
     */
    public boolean expectsPerAuthorConnection() {
        return perAuthorAdapter != null;
    }

    /** The service a deployment that configured nothing gets. Answers, and answers "not configured". */
    public static AssistantService inert() {
        return new AssistantService(AssistantConfiguration.disabled(), null);
    }

    /**
     * The composition root's factory: environment in, wired service out.
     *
     * <p><b>This is the only place an HTTP client is built for the assistant</b>, and it is built by
     * {@code EgressHttpClients} — the SEC-10 constructor that cannot follow a redirect, cannot be
     * proxied and cannot skip TLS validation. The adapter receives it; the adapter never makes one.
     * Keeping the construction here rather than inside the adapter is what makes that property
     * checkable by reading one method instead of auditing every adapter that will ever exist.</p>
     *
     * <p>No provider is constructed unless the configuration is <em>fully</em> ready — profile,
     * allowlisted host and credential. An inert deployment therefore has no object capable of
     * outbound calls at all, rather than a configured one that declines to use it.</p>
     */
    public static AssistantService fromEnvironment(java.util.Map<String, String> environment) {
        return fromEnvironment(environment, null);
    }

    /**
     * @param consent the consent store. <b>A network-backed adapter is not wired without one, and the
     *                absence throws rather than degrading.</b> See {@link AssistantConsentStore}:
     *                consent, token and egress posture, and per-provider terms verification each
     *                gate the first outbound byte. Making that a
     *                boot-time failure rather than a configuration convention is the difference
     *                between a closed gate and a gate someone can open by setting three environment
     *                variables without satisfying the security contract.
     * @throws IllegalStateException when the configuration would produce an outbound-capable adapter
     *                               and no consent store is present
     */
    public static AssistantService fromEnvironment(java.util.Map<String, String> environment,
                                                   AssistantConsentStore consent) {
        return fromEnvironment(environment, consent, null);
    }

    private static void requireConsent(AssistantConsentStore consent) {
        if (consent == null) {
            throw new IllegalStateException("refusing to wire an assistant provider that egresses: no "
                    + "consent store is present. Per-user, per-provider consent is required before "
                    + "any context is sent; token and egress posture and per-provider terms "
                    + "verification must also be configured. Set "
                    + AssistantConfiguration.PROVIDER_VARIABLE + "="
                    + ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID
                    + " to run the assistant without a network provider.");
        }
    }

    /**
     * The second gate: an adapter with open connection blockers is not connected, consent or not.
     *
     * <p>Independent of consent on purpose. Consent is recorded separately and can change independently;
     * these blockers describe defects in the adapter itself. Folding them into one
     * check would mean satisfying the first silently satisfied the second.</p>
     */
    private static void requireNoOpenBlockers(Adapter adapter) {
        java.util.List<String> blockers = adapter.openBlockers.get();
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("refusing to wire the '" + adapter.id + "' assistant "
                    + "provider: it has open security findings that must be closed before it is "
                    + "connected to a real model. " + String.join(" | ", blockers)
                    + " -- see AnthropicAssistantProvider.OPEN_CONNECTION_BLOCKERS.");
        }
    }

    /**
     * What this build can wire, and what each one declares about itself.
     *
     * <p>A registration rather than a chain of {@code if (id.equals(...))} in the factory, because
     * both gates key on <em>declared properties</em> — does it egress, does it have open blockers —
     * and a chain of identity checks is what let the previous shape gate one named provider while a
     * second network adapter walked past. Adding an adapter here is one row that must answer both
     * questions.</p>
     */
    private enum Adapter {
        ANTHROPIC(AssistantConfiguration.ANTHROPIC_PROVIDER, true,
                () -> ai.ravenroot.server.assistant.provider.AnthropicAssistantProvider
                        .OPEN_CONNECTION_BLOCKERS),
        OPENAI_COMPATIBLE(AssistantConfiguration.OPENAI_COMPATIBLE_PROVIDER, true,
                java.util.List::of),
        SCRIPTED(ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID, false,
                java.util.List::of);

        private final String id;
        private final boolean egresses;
        private final java.util.function.Supplier<java.util.List<String>> openBlockers;

        Adapter(String id, boolean egresses,
                java.util.function.Supplier<java.util.List<String>> openBlockers) {
            this.id = id;
            this.egresses = egresses;
            this.openBlockers = openBlockers;
        }

        static Adapter forProvider(String providerId) {
            return java.util.Arrays.stream(values())
                    .filter(candidate -> candidate.id.equals(providerId))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * @param credential the credential this adapter authenticates with. Passed rather than read
         *                   off the configuration because in OAuth mode it belongs to <em>an
         *                   author</em>, not to the deployment, so there is no single value the
         *                   configuration could hold.
         */
        AssistantProvider build(AssistantConfiguration configuration, AssistantCredential credential) {
            return switch (this) {
                case ANTHROPIC -> new ai.ravenroot.server.assistant.provider.AnthropicAssistantProvider(
                        ai.ravenroot.core.security.egress.EgressHttpClients.create(),
                        configuration.endpoint(), configuration.model(), credential,
                        configuration.timeout());
                case OPENAI_COMPATIBLE ->
                        new ai.ravenroot.server.assistant.provider.OpenAiCompatibleAssistantProvider(
                                ai.ravenroot.core.security.egress.EgressHttpClients.create(),
                                configuration.endpoint(), configuration.model(), credential,
                                configuration.timeout(), configuration.allowLocalHttp());
                case SCRIPTED -> new ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider();
            };
        }
    }

    /**
     * The composition root's factory when authors bring their own credential.
     *
     * <h4>Why the adapter cannot be built here in OAuth mode, and what that costs</h4>
     * <p>In API-key mode the deployment has one credential, so one adapter is built once and reused.
     * In OAuth mode the credential belongs to <em>an author</em>: there is no value to build an
     * adapter with until a signed-in author asks for a turn. So this factory wires no provider, keeps
     * the adapter <em>kind</em>, and {@link #providerFor} constructs one per author.</p>
     *
     * <p><b>Both boot gates still run here</b>, and that is the part worth checking rather than
     * assuming. Deferring construction must not defer the refusals: the consent store and the
     * adapter's connection-blocker list are checked at composition time in OAuth mode too, so a deployment
     * that would refuse to connect still refuses at startup rather than at the first author's first
     * question. What is deferred is the credential, not the gate.</p>
     *
     * @param tokens where signed-in authors' tokens live. Consulted only in OAuth mode — see
     *               {@link AssistantCredentialSource} for why a mode never consults the other's
     *               credential, not even to check.
     */
    public static AssistantService fromEnvironment(java.util.Map<String, String> environment,
                                                   AssistantConsentStore consent,
                                                   AssistantTokenStore tokens) {
        AssistantConfiguration configuration = AssistantConfiguration.fromEnvironment(environment);
        Adapter adapter = Adapter.forProvider(configuration.providerId());
        if (adapter == null) {
            return new AssistantService(configuration, null, consent, tokens, null, null);
        }
        boolean oauth = configuration.credentialSource() == AssistantCredentialSource.OAUTH;
        if (adapter.egresses && !configuration.reachReady()) {
            // Enabled, profile and allowlist are deployment facts and are checked at boot in both
            // modes. The credential is not one of them in OAuth mode, so it is deliberately excluded
            // from this check -- see reachReady().
            return new AssistantService(configuration, null, consent, tokens, null, null);
        }
        if (adapter.egresses && !oauth && configuration.credential() == null
                && adapter != Adapter.OPENAI_COMPATIBLE) {
            return new AssistantService(configuration, null, consent, tokens, null, null);
        }
        if (adapter.egresses) {
            requireConsent(consent);
            requireNoOpenBlockers(adapter);
        }
        if (oauth) {
            // No credential yet, by design. The adapter kind is retained so providerFor can build one
            // per author; nothing outbound-capable exists until an author has actually signed in.
            return new AssistantService(configuration, null, consent, tokens, adapter, null);
        }
        return new AssistantService(configuration, adapter.build(configuration,
                configuration.credential()), consent, tokens, null, null);
    }

    /**
     * What this deployment says about itself <em>for one author</em>.
     *
     * <p>The deployment-level checks are unchanged and still run first, in the order
     * {@code assistant-session.js} expects. Only the credential check differs, and only in OAuth mode,
     * where an absent credential is the author's to fix rather than the operator's — which is what
     * {@code not-signed-in} has always meant and, until now, had no way to be reached.</p>
     */
    public AssistantAvailability availability(String subject) {
        AssistantAvailability declared =
                connectable(configuration.availabilityWith(credentialFor(subject)));
        if (declared.ready() && provider == null && perAuthorAdapter == null) {
            // The same reconciliation {@link #availability()} makes, applied on the per-author path
            // used by the routes. Configuration says ready and no
            // adapter of either kind was wired: the composition root and this service disagree, and
            // the operator-actionable reason is the honest answer rather than accepting a turn there
            // is nothing to serve. The OAuth case is excluded by `perAuthorAdapter`, where having no
            // built provider is the normal state rather than a disagreement.
            return AssistantAvailability.inert(AssistantAvailability.InertReason.NO_PROFILE,
                    "No provider adapter is wired for the configured profile.");
        }
        return declared;
    }

    /**
     * Downgrades {@code not-linked} to the operator's gap when this deployment cannot begin a
     * connection.
     *
     * <h2>Why the downgrade exists at all</h2>
     * <p>{@code not-linked} is the panel's one user-actionable reason, and the panel answers it by
     * offering a Connect control. That control is honest only if pressing it can lead somewhere. In
     * OAuth mode with no {@link ai.ravenroot.server.assistant.oauth.AssistantConnection} wired,
     * pressing it cannot. What is actually missing then is the
     * operator's configuration, so the panel is told that instead, and the control does not appear.</p>
     *
     * <p>This is the same discipline the inert reasons were separated for. A Connect button in front
     * of an author whose deployment has no connection path would invite them to fix something that
     * is not theirs to fix — the failure mode {@code host-not-allowlisted}'s wording exists to
     * avoid.</p>
     */
    private AssistantAvailability connectable(AssistantAvailability availability) {
        if (availability.reason() != AssistantAvailability.InertReason.NOT_LINKED
                || connection != null) {
            return availability;
        }
        return AssistantAvailability.inert(AssistantAvailability.InertReason.NO_PROFILE,
                "This deployment expects each author to connect to the provider, but no connection "
                        + "path is configured.");
    }

    /**
     * How an author connects, or {@code null} when this deployment offers no way to.
     *
     * <p>See {@link ai.ravenroot.server.assistant.oauth.AssistantConnection} for why the provider's
     * endpoints are undefaulted, and {@link #connectable} for what the panel is told when no complete
     * connection is configured. The route reads this and refuses when it is absent, rather than the
     * absence being discovered halfway through an exchange.</p>
     */
    public ai.ravenroot.server.assistant.oauth.AssistantConnection connection() {
        return connection;
    }

    /**
     * The adapter that will serve this author, or {@code null} if none can be.
     *
     * <p>In OAuth mode this constructs one per author, holding that author's token. It returns
     * {@code null} rather than an adapter with no credential when the author has not signed in: an
     * adapter that could exist without a credential is an adapter that could be called without one.</p>
     */
    public ai.ravenroot.server.assistant.provider.AssistantProvider providerFor(String subject) {
        if (perAuthorAdapter == null) {
            return provider;
        }
        AssistantCredential credential = credentialFor(subject);
        return credential == null ? null : perAuthorAdapter.build(configuration, credential);
    }

    /** Which credential model actually serves this author. Diagnostic; never the value. */
    public AssistantCredential.Scheme credentialSchemeFor(String subject) {
        AssistantCredential resolved = credentialFor(subject);
        return resolved == null ? null : resolved.scheme();
    }

    /**
     * This author's credential, from exactly one source.
     *
     * <p><b>No branch here falls through to the other source.</b> In API-key mode the token store is
     * not consulted at all — not asked and its answer discarded, but never asked, which is what the
     * counting assertion in {@code AssistantOauthCredentialTest} pins. In OAuth mode
     * {@code configuration.credential()} is already {@code null} because
     * {@code AssistantConfiguration.fromEnvironment} did not read the variable.</p>
     */
    private AssistantCredential credentialFor(String subject) {
        if (configuration.credentialSource() == AssistantCredentialSource.API_KEY) {
            return configuration.credential();
        }
        if (tokens == null || subject == null) {
            return null;
        }
        return tokens.tokenFor(subject).orElse(null);
    }

    public AssistantAvailability availability() {
        AssistantAvailability declared = configuration.availability();
        if (declared.ready() && provider == null) {
            // Configuration says ready but no adapter was wired: the composition root and this service
            // disagree. Report the operator-actionable reason rather than accepting a turn there is
            // nothing to serve.
            return AssistantAvailability.inert(AssistantAvailability.InertReason.NO_PROFILE,
                    "No provider adapter is wired for the configured profile.");
        }
        return declared;
    }

    /** Whether the operator has switched the service off entirely (answered as 404, not as a body). */
    public boolean offered() {
        return configuration.enabled();
    }

    /**
     * Runs one author turn to completion.
     *
     * <p><b>An authorization denial is not caught.</b> {@code AuthorizationDeniedException} propagates
     * out of this method and becomes the same {@code ACCESS_DENIED} response the author's own UI call
     * would have received, because {@code RavenrootServer#protectedRequest} already maps it. That is
     * the strict reading of "a denial to the user is a denial to the panel", and the strict reading is
     * the right one here: converting a denial into a failed tool result would let the model retry a
     * read its author is not permitted to perform, and would let the turn continue reasoning about why
     * the data was unavailable — which is a slower path to the same disclosure.</p>
     */
    public AssistantOutcome send(RequestContext context, AuthorizedRavenrootApplication application,
                                 AssistantTurn turn) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(turn, "turn");
        // Per author, not per deployment. In API-key mode this is the same answer for everyone
        // and nothing changes; in OAuth mode an author who has not signed in is stopped here, before
        // anything is composed, rather than reaching a provider call with no credential.
        AssistantAvailability availability = availability(context.subject());
        if (!availability.ready()) {
            // Reached only if a caller skipped the route's own precondition. Never composes a request.
            return new AssistantOutcome.Failure(AssistantOutcome.Reason.INVALID_TURN,
                    availability.detail());
        }
        AssistantProvider turnProvider = providerFor(context.subject());
        if (turnProvider == null) {
            // Availability said ready, so a credential resolved a moment ago and has since gone --
            // a token revoked between the two calls. Refusing is the only honest answer: there is no
            // credential to call with, and borrowing another would violate the explicit source.
            return new AssistantOutcome.Failure(AssistantOutcome.Reason.INVALID_TURN,
                    "The assistant is not available for this author.");
        }

        var tools = new AssistantInternalContext(application);
        var messages = new ArrayList<AssistantProvider.Message>();
        messages.add(AssistantProvider.Message.author(turn.render()));
        int proposalRecoveries = 0;

        for (int iteration = 0; iteration < configuration.maxToolIterations(); iteration++) {
            // The register is read before composing, every time. Inside the
            // loop rather than above it, because a turn can span several requests and a revocation
            // that only took effect on the next turn would keep sending context the author has already
            // withdrawn. One snapshot governs one request and the tool results answered against it, so
            // the model is never served a class it was not offered in the same breath.
            var granted = consentedClasses(context, turnProvider.id());
            AssistantProvider.Turn answer;
            try {
                var offeredTools = new ArrayList<AssistantProvider.ToolSpec>(tools.specs(granted));
                if (AssistantGraphProposal.canOffer(turn)) offeredTools.add(AssistantGraphProposal.toolSpec());
                answer = turnProvider.complete(new AssistantProvider.Request(configuration.model(),
                        instructions(), messages, offeredTools,
                        configuration.maxOutputTokens()));
            } catch (AssistantProviderException failed) {
                return failed.asOutcome();
            }

            switch (answer) {
                case AssistantProvider.Turn.Answer text -> {
                    return new AssistantOutcome.Reply(text.text(), text.model(), text.truncated());
                }
                case AssistantProvider.Turn.Refused refused -> {
                    return new AssistantOutcome.Failure(AssistantOutcome.Reason.PROVIDER_REFUSED, null);
                }
                case AssistantProvider.Turn.ToolCalls calls -> {
                    var proposalCalls = calls.calls().stream()
                            .filter(call -> AssistantGraphProposal.TOOL_NAME.equals(call.name()))
                            .toList();
                    if (proposalCalls.isEmpty()) {
                        messages.add(new AssistantProvider.Message(AssistantProvider.Role.ASSISTANT,
                                calls.assistantContent()));
                        messages.add(new AssistantProvider.Message(AssistantProvider.Role.AUTHOR,
                                runTools(calls.calls(), tools, context, granted)));
                        continue;
                    }
                    if (proposalCalls.size() == 1 && calls.calls().size() == 1) {
                        try {
                            return new AssistantOutcome.Proposal(AssistantGraphProposal.read(
                                    proposalCalls.get(0).inputJson(), turn, calls.model()));
                        } catch (IllegalArgumentException invalid) {
                            proposalRecoveries++;
                            if (!canRecoverProposal(iteration, proposalRecoveries)) {
                                return invalidModelProposal();
                            }
                            messages.add(new AssistantProvider.Message(AssistantProvider.Role.ASSISTANT,
                                    calls.assistantContent()));
                            messages.add(new AssistantProvider.Message(AssistantProvider.Role.AUTHOR,
                                    List.of(new AssistantProvider.Content.ToolResult(
                                            proposalCalls.get(0).id(),
                                            AssistantGraphProposal.safeValidationFeedback(invalid), true))));
                            continue;
                        }
                    }

                    proposalRecoveries++;
                    if (!canRecoverProposal(iteration, proposalRecoveries)) return invalidModelProposal();
                    messages.add(new AssistantProvider.Message(AssistantProvider.Role.ASSISTANT,
                            calls.assistantContent()));
                    var results = new ArrayList<AssistantProvider.Content>(calls.calls().size());
                    for (AssistantProvider.Content.ToolUse call : calls.calls()) {
                        if (!AssistantGraphProposal.TOOL_NAME.equals(call.name())) {
                            results.addAll(runTools(List.of(call), tools, context, granted));
                            continue;
                        }
                        String feedback;
                        try {
                            AssistantGraphProposal.read(call.inputJson(), turn, calls.model());
                            feedback = MIXED_PROPOSAL_FEEDBACK;
                        } catch (IllegalArgumentException invalid) {
                            feedback = AssistantGraphProposal.safeValidationFeedback(invalid);
                        }
                        results.add(new AssistantProvider.Content.ToolResult(call.id(), feedback, true));
                    }
                    messages.add(new AssistantProvider.Message(AssistantProvider.Role.AUTHOR, results));
                }
            }
        }
        return new AssistantOutcome.Failure(AssistantOutcome.Reason.TOOL_LOOP_EXHAUSTED, null);
    }

    private boolean canRecoverProposal(int iteration, int proposalRecoveries) {
        return proposalRecoveries <= MAX_PROPOSAL_RECOVERIES
                && iteration + 1 < configuration.maxToolIterations();
    }

    private static AssistantOutcome.Failure invalidModelProposal() {
        return new AssistantOutcome.Failure(AssistantOutcome.Reason.MODEL_PROPOSAL_INVALID, null);
    }

    /**
     * Runs the reads the model asked for.
     *
     * <p>An <em>unknown</em> tool name comes back as a failed tool result rather than ending the turn:
     * that is the model asking for something this build does not offer, which it can recover from. An
     * authorization <em>denial</em> is the opposite case and is not caught — see {@link #send}.</p>
     */
    private List<AssistantProvider.Content> runTools(List<AssistantProvider.Content.ToolUse> calls,
                                                     AssistantInternalContext tools,
                                                     RequestContext context,
                                                     java.util.Set<AssistantContextClass> granted) {
        var results = new ArrayList<AssistantProvider.Content>(calls.size());
        for (AssistantProvider.Content.ToolUse call : calls) {
            if (!tools.knows(call.name())) {
                results.add(new AssistantProvider.Content.ToolResult(call.id(),
                        "This deployment does not offer a tool by that name.", true));
                continue;
            }
            // A tool this build has, over a class this author refused. The model was not offered
            // it -- specs(granted) left it out -- so reaching here means it named a tool it never saw,
            // which is exactly the case the design must not rest on the model getting right. Refused
            // as a failed tool result rather than by ending the turn, because the author's refusal is
            // not an error: the model should say what it cannot see and answer with the rest.
            if (!tools.permits(call.name(), granted)) {
                results.add(new AssistantProvider.Content.ToolResult(call.id(),
                        "The signed-in author has not consented to sending this class of context to "
                                + "the configured model provider, so this read is not available in "
                                + "this turn.", true));
                continue;
            }
            // AuthorizationDeniedException deliberately escapes this loop and this method.
            results.add(new AssistantProvider.Content.ToolResult(call.id(),
                    tools.invoke(call.name(), context, granted), false));
        }
        return results;
    }

    /**
     * The classes of context this author has consented to send to the configured provider.
     *
     * <h2>Why a null register grants everything here, and why that is not a hole</h2>
     * <p>A null {@link AssistantConsentStore} is only reachable around a provider that declares
     * {@code egresses() == false} — the canonical constructor refuses to build the service otherwise,
     * and {@link #fromEnvironment} refuses to wire the adapter. So the branch below is not "consent is
     * optional", it is "no bytes leave this JVM, so there is nothing for the author to consent to".
     * The moment an adapter that egresses is involved, the register exists and this method reads it.</p>
     *
     * <p>Note the asymmetry with {@link AssistantConsentStore#consentedClasses}, which answers an
     * unknown author with the empty set. That is the safe default where a real provider is configured;
     * this is the different case where no provider can reach the network at all.</p>
     */
    private java.util.Set<AssistantContextClass> consentedClasses(RequestContext context,
                                                                  String providerId) {
        if (consent == null) {
            return java.util.EnumSet.allOf(AssistantContextClass.class);
        }
        java.util.Set<AssistantContextClass> granted =
                consent.consentedClasses(context.subject(), providerId);
        // A register that answers null is a register that would otherwise be read as "everything" by
        // the next contains() call this data reaches. Read it as the author having chosen nothing.
        return granted == null ? java.util.Set.of() : granted;
    }

    /**
     * The instruction pack. <b>A placeholder, and marked as one.</b>
     *
     * <p>It states only what is structurally true of this implementation — reads are authorized and graph
     * proposals are inert pending explicit confirmation; its
     * reads are the author's own, and it must not claim capabilities it does not have. A versioned
     * catalog-and-invariants pack can replace this wholesale. It is written here rather than left empty because an empty system prompt
     * is itself a design decision, and a silent one.</p>
     */
    String instructions() {
        return "You are Ravenroot's authoring assistant, answering for the signed-in author about this "
                + "Ravenroot deployment. You can inspect this deployment through the read tools provided. "
                + "When the proposal tool is offered, you may propose graph edits as inert structured data; "
                + "you never apply them, and must say they still require the author's explicit confirmation. "
                + "You cannot start, cancel or deploy anything, and must not claim an edit was applied. "
                + "Every read tool call runs under the author's own "
                + "permissions, so a read that is refused is genuinely not available to them -- say so "
                + "plainly rather than guessing at what the data would have shown. If you do not know "
                + "something and no tool can tell you, say that.";
    }
}
