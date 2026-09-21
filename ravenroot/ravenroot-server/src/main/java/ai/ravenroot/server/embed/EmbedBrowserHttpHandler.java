package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedProjectionResolution;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedViewerSource;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.DeploymentEventBatch;
import ai.ravenroot.api.application.DeploymentViewerView;
import ai.ravenroot.api.application.DurableProcessEventPage;
import ai.ravenroot.api.application.PublicExecutionDescription;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.server.AuthenticatedPrincipalAttribute;
import ai.ravenroot.server.HttpRequestContext;
import ai.ravenroot.server.DeploymentObservationCursorStore;
import ai.ravenroot.server.audit.JsonStrings;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Complete distinct-origin adapter for static v1 projections and additive v2 live-run viewing. */
public final class EmbedBrowserHttpHandler {
    public static final String CREATE_PATH = "/v1/embed/sessions";
    public static final String ACKNOWLEDGEMENT_PATH = "/v1/embed/acknowledgements";
    public static final String LAUNCH_PATH = "/v1/embed/launch";
    public static final String EXCHANGE_PATH = "/v1/embed/exchange";
    public static final String PROJECTION_PATH = "/v1/embed/projection";
    public static final String OBSERVATION_PATH = "/v1/embed/observation";
    public static final String RUNS_PATH = "/v1/embed/runs";
    public static final String START_EXECUTION_PATH = "/v1/embed/executions";
    public static final String BOOTSTRAP_SCRIPT_PATH = "/embed-bootstrap.js";
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final EmbedBrowserConfiguration configuration;
    private final EmbedLaunchTicketAuthority tickets;
    private final EmbedBrowserSessionAuthority sessions;
    private final P256EmbedProofVerifier proofs;
    private final AuthorizedRavenrootApplication deployments;
    private final DeploymentObservationCursorStore cursors;

    public EmbedBrowserHttpHandler(EmbedBrowserConfiguration configuration) {
        this(configuration, null, new DeploymentObservationCursorStore(configuration.clock()));
    }

    public EmbedBrowserHttpHandler(EmbedBrowserConfiguration configuration,
                                   AuthorizedRavenrootApplication deployments,
                                   DeploymentObservationCursorStore cursors) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (!configuration.active()) throw new IllegalArgumentException("embed browser is disabled");
        this.tickets = new EmbedLaunchTicketAuthority(configuration.clock(), configuration.ticketTtl(),
                configuration.ticketCapacity());
        this.sessions = new EmbedBrowserSessionAuthority(configuration.clock(), configuration.exchangeTtl(),
                configuration.bearerTtl(), configuration.sessionCapacity());
        this.proofs = new P256EmbedProofVerifier(configuration.clock(), configuration.proofTtl(),
                configuration.replayCapacity());
        this.deployments = deployments;
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    public void createSession(HttpExchange exchange) throws IOException {
        createSession(exchange, () -> AuthenticatedPrincipalAttribute.requestContext(exchange),
                () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void createSession(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        createSession(exchange, requestContext::applicationContext, requestContext::requestId);
    }

    private void createSession(HttpExchange exchange, Supplier<RequestContext> requestContext,
                               Supplier<String> requestId)
            throws IOException {
        if (!requireExactPath(exchange, CREATE_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || browserMetadataPresent(exchange) || hasCookie(exchange)) return;
        Map<String, String> body = body(exchange, Set.of("registrationId"));
        if (body == null) return;
        EmbedRegistrationResolution resolution = configuration.sessionCreation().resolve(
                requestContext.get(), body.get("registrationId"));
        if (!(resolution instanceof EmbedRegistrationResolution.Available available)) {
            unavailable(exchange, resolution instanceof EmbedRegistrationResolution.Temporary);
            return;
        }
        try {
            validatedParentOrigin(available.aggregate());
        } catch (IllegalArgumentException invalidAuthorityGrant) {
            unavailable(exchange, false);
            return;
        }
        try {
            var issued = tickets.issue(available.aggregate());
            audit(requestId.get(), available.aggregate(), EmbedSecurityAuditSink.Phase.SESSION_CREATED);
            String launchUrl = configuration.viewerOrigin().value() + LAUNCH_PATH + "?ticket=" + issued.value();
            json(exchange, 201, "{\"launchUrl\":\"" + JsonStrings.escape(launchUrl)
                    + "\",\"expiresAt\":\"" + issued.expiresAt() + "\"}");
        } catch (EmbedLaunchTicketAuthority.CapacityExceededException exhausted) {
            temporary(exchange);
        } catch (RuntimeException auditOrAuthorityFailure) {
            temporary(exchange);
        }
    }

    public void launch(HttpExchange exchange) throws IOException {
        launch(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void launch(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        launch(exchange, requestContext::requestId);
    }

    private void launch(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, LAUNCH_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "GET") || !fetch(exchange, "navigate", "iframe") || hasCookie(exchange)) return;
        String ticket = ticket(exchange);
        if (ticket == null) { invalid(exchange); return; }
        var resolution = tickets.consume(ticket, configuration.registrations());
        if (!(resolution instanceof EmbedLaunchTicketAuthority.Resolution.Available available)) {
            unavailable(exchange, false);
            return;
        }
        final EmbedParentOrigin parentOrigin;
        try {
            parentOrigin = validatedParentOrigin(available.registration());
        } catch (IllegalArgumentException invalidAuthorityGrant) {
            unavailable(exchange, false);
            return;
        }
        String suppliedOrigin = optionalSingleHeader(exchange, "Origin");
        if (suppliedOrigin != null && !parentOrigin.value().equals(suppliedOrigin)) {
            unavailable(exchange, false);
            return;
        }
        try {
            var bootstrap = sessions.begin(available.registration());
            boolean viewerV2 = available.registration().source() instanceof EmbedViewerSource.DeploymentV2;
            boolean showStartExecution = viewerV2
                    && ((EmbedViewerSource.DeploymentV2) available.registration().source()).showStartExecution();
            audit(requestId.get(), available.registration(), EmbedSecurityAuditSink.Phase.TICKET_CONSUMED);
            exchange.getResponseHeaders().remove("X-Frame-Options");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; base-uri 'none'; form-action 'none'; script-src 'self'; "
                            + "style-src 'self'; connect-src 'self'; img-src 'none'; font-src 'none'; "
                            + "worker-src 'none'; frame-ancestors "
                            + parentOrigin.value() + "; sandbox allow-scripts allow-same-origin");
            String bootstrapJson = "{\"exchangeId\":\"" + JsonStrings.escape(bootstrap.exchangeId())
                    + "\",\"challenge\":\"" + JsonStrings.escape(bootstrap.challenge())
                    + "\",\"channelId\":\"" + JsonStrings.escape(bootstrap.channelId())
                    + "\",\"acknowledgementId\":\"" + JsonStrings.escape(bootstrap.acknowledgementId())
                    + "\",\"grantRevision\":\"" + available.registration().revision()
                    + "\",\"expiresAt\":\"" + bootstrap.expiresAt()
                    + "\",\"viewerOrigin\":\"" + JsonStrings.escape(configuration.viewerOrigin().value())
                    + "\",\"parentOrigin\":\"" + JsonStrings.escape(parentOrigin.value())
                    + "\",\"theme\":" + available.registration().sessionGrant().themeOverride()
                    .map(theme -> "\"" + theme.wireValue() + "\"").orElse("null")
                    + (viewerV2 ? ",\"viewerSourceVersion\":\"2\",\"showStartExecution\":"
                    + showStartExecution : "") + "}";
            String themeAttribute = available.registration().sessionGrant().themeOverride()
                    .map(theme -> " data-theme=\"" + theme.wireValue() + "\"").orElse("");
            String viewControls = viewerV2
                    ? "<label class=\"embed-mode-label\">View <select data-viewer-mode>"
                    + "<option value=\"design\">Design</option>"
                    + "<option value=\"monitoring\">Monitoring</option></select></label>"
                    + "<button type=\"button\" data-viewer-command=\"render\">Render</button>"
                    : "<label class=\"embed-mode-label\">View <select data-viewer-mode>"
                    + "<option value=\"cyto\">Cyto</option><option value=\"n8n\">N8N</option>"
                    + "<option value=\"elastic\">Elastic</option></select></label>";
            String runControls = viewerV2
                    ? "<label class=\"embed-run-label\">Run <select data-viewer-run "
                    + "aria-label=\"Live run\" disabled><option value=\"\">No authorized runs</option>"
                    + "</select></label><span data-viewer-run-empty role=\"status\">No authorized runs.</span>"
                    + (showStartExecution
                    ? "<button type=\"button\" data-viewer-start>Start execution</button>" : "")
                    : "";
            String html = "<!doctype html><html lang=\"en\"" + themeAttribute
                    + "><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<meta name=\"referrer\" content=\"no-referrer\">"
                    + "<title>Ravenroot embedded graph</title>"
                    + "<link rel=\"stylesheet\" href=\"/embed-viewer.css\"></head><body>"
                    + "<span class=\"embed-focus-sentinel\" tabindex=\"0\" role=\"navigation\" "
                    + "aria-label=\"Start of embedded graph\"></span>"
                    + "<a class=\"embed-skip-link\" href=\"#ravenroot-embed-canvas\">Skip to graph</a>"
                    + "<main id=\"ravenroot-embed-viewer\" class=\"embed-viewer\" "
                    + "data-viewer-state=\"loading\" aria-labelledby=\"ravenroot-embed-title\">"
                    + "<header class=\"embed-viewer-header\"><h1 id=\"ravenroot-embed-title\" "
                    + "class=\"embed-viewer-title\">Graph view</h1><p class=\"embed-viewer-metadata\" "
                    + "data-viewer-metadata></p><nav class=\"embed-viewer-controls\" "
                    + "aria-label=\"Graph view controls\">" + viewControls + runControls
                    + "<button type=\"button\" "
                    + "data-viewer-command=\"zoom-out\" aria-label=\"Zoom out\">&minus;</button>"
                    + "<button type=\"button\" data-viewer-command=\"fit\">Fit</button>"
                    + "<button type=\"button\" data-viewer-command=\"zoom-in\" "
                    + "aria-label=\"Zoom in\">+</button></nav></header>"
                    + "<div id=\"ravenroot-embed-canvas\" class=\"embed-viewer-canvas\" "
                    + "data-viewer-canvas role=\"application\" tabindex=\"0\" "
                    + "aria-label=\"Read-only graph. Arrow keys pan, plus and minus zoom, "
                    + "and zero fits the graph.\"></div>"
                    + "<canvas class=\"embed-viewer-minimap\" data-viewer-minimap width=\"160\" "
                    + "height=\"104\" tabindex=\"0\" role=\"application\" "
                    + "aria-label=\"Graph overview. Arrow keys pan, Home fits, Escape returns to graph.\">"
                    + "Graph overview</canvas>"
                    + "<section class=\"embed-viewer-alternative\" aria-label=\"Graph contents\">"
                    + "<h2>Graph contents</h2><ol data-viewer-alternative></ol></section>"
                    + "<p class=\"embed-viewer-status\" data-viewer-status role=\"status\" "
                    + "aria-live=\"polite\">Loading graph&hellip;</p></main>"
                    + "<span class=\"embed-focus-sentinel\" tabindex=\"0\" role=\"navigation\" "
                    + "aria-label=\"End of embedded graph\"></span>"
                    + "<script id=\"ravenroot-embed-bootstrap\" type=\"application/json\">"
                    + bootstrapJson.replace("<", "\\u003c") + "</script>"
                    + "<script src=\"" + BOOTSTRAP_SCRIPT_PATH + "\"></script></body></html>";
            bytes(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    public void acknowledgeParent(HttpExchange exchange) throws IOException {
        acknowledgeParent(exchange, () -> AuthenticatedPrincipalAttribute.requestContext(exchange),
                () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void acknowledgeParent(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        acknowledgeParent(exchange, requestContext::applicationContext, requestContext::requestId);
    }

    private void acknowledgeParent(HttpExchange exchange, Supplier<RequestContext> requestContext,
                                   Supplier<String> requestId)
            throws IOException {
        if (!requireExactPath(exchange, ACKNOWLEDGEMENT_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || browserMetadataPresent(exchange) || hasCookie(exchange)) return;
        Map<String, String> body = body(exchange,
                Set.of("registrationId", "acknowledgementId", "channelId", "correlationId"));
        if (body == null) return;
        EmbedRegistrationResolution resolution = configuration.sessionCreation().resolve(
                requestContext.get(), body.get("registrationId"));
        if (!(resolution instanceof EmbedRegistrationResolution.Available available)) {
            unavailable(exchange, resolution instanceof EmbedRegistrationResolution.Temporary);
            return;
        }
        try {
            validatedParentOrigin(available.aggregate());
            if (!sessions.acknowledge(body.get("acknowledgementId"), body.get("channelId"),
                    body.get("correlationId"), available.aggregate(), configuration.registrations(),
                    () -> audit(requestId.get(), available.aggregate(),
                            EmbedSecurityAuditSink.Phase.PARENT_ACKNOWLEDGED))) {
                unavailable(exchange, false);
                return;
            }
            json(exchange, 200, "{\"acknowledged\":true}");
        } catch (IllegalArgumentException invalidAuthorityGrant) {
            unavailable(exchange, false);
        } catch (RuntimeException auditOrAuthorityFailure) {
            temporary(exchange);
        }
    }

    public void exchange(HttpExchange exchange) throws IOException {
        exchange(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void exchange(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        exchange(exchange, requestContext::requestId);
    }

    private void exchange(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, EXCHANGE_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || !viewerRequest(exchange) || hasCookie(exchange)) return;
        Map<String, String> body = body(exchange,
                Set.of("exchangeId", "channelId", "ackCorrelationId", "keyX", "keyY",
                        "nonce", "jti", "issuedAt", "signature"));
        if (body == null) return;
        var pending = sessions.acknowledged(body.get("exchangeId"), configuration.registrations());
        if (pending == null || !pending.challenge().equals(body.get("nonce"))
                || !pending.channelId().equals(body.get("channelId"))
                || !pending.ackCorrelationId().equals(body.get("ackCorrelationId"))) {
            unavailable(exchange, false);
            return;
        }
        try {
            validatedParentOrigin(pending.registration());
        } catch (IllegalArgumentException invalidAuthorityGrant) {
            unavailable(exchange, false);
            return;
        }
        try {
            ECPublicKey key = publicKey(body.get("keyX"), body.get("keyY"));
            Instant issuedAt = Instant.parse(body.get("issuedAt"));
            byte[] signature = decode(body.get("signature"), 64);
            if (!proofs.verifyExchangeAndConsume(body.get("exchangeId"), pending.registration().revision(),
                    body.get("nonce"), body.get("channelId"), body.get("ackCorrelationId"),
                    body.get("jti"), "POST", EXCHANGE_PATH, issuedAt, key, signature)) {
                unavailable(exchange, false);
                return;
            }
            var bearer = sessions.activate(body.get("exchangeId"), pending, key, configuration.registrations());
            if (bearer == null) { unavailable(exchange, false); return; }
            audit(requestId.get(), pending.registration(), EmbedSecurityAuditSink.Phase.BEARER_ISSUED);
            json(exchange, 200, "{\"tokenType\":\"Bearer\",\"bearer\":\""
                    + JsonStrings.escape(bearer.bearer()) + "\",\"challenge\":\""
                    + JsonStrings.escape(bearer.challenge()) + "\",\"expiresAt\":\""
                    + bearer.expiresAt() + "\"}");
        } catch (IllegalArgumentException invalid) {
            invalid(exchange);
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    public void projection(HttpExchange exchange) throws IOException {
        projection(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void projection(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        projection(exchange, requestContext::requestId);
    }

    private void projection(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, PROJECTION_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || !viewerRequest(exchange) || hasCookie(exchange)) return;
        String bearer = bearer(exchange);
        if (bearer == null) { unavailable(exchange, false); return; }
        Map<String, String> body = body(exchange, Set.of("nonce", "jti", "issuedAt", "signature"));
        if (body == null) return;
        var session = sessions.resolve(bearer, configuration.registrations());
        if (session == null || !session.challenge().equals(body.get("nonce"))) {
            unavailable(exchange, false);
            return;
        }
        try {
            validatedParentOrigin(session.registration());
        } catch (IllegalArgumentException invalidAuthorityGrant) {
            unavailable(exchange, false);
            return;
        }
        try {
            Instant issuedAt = Instant.parse(body.get("issuedAt"));
            byte[] signature = decode(body.get("signature"), 64);
            if (!proofs.verifyAndConsume(bearer, session.registration().revision(), body.get("nonce"),
                    body.get("jti"), "POST", PROJECTION_PATH, issuedAt, session.key(), signature)) {
                unavailable(exchange, false);
                return;
            }
            // The captured aggregate, not a fresh lookup: the payload served here belongs to the
            // same revision as the grant this bearer was minted against, because it is carried by it.
            var registration = session.registration();
            var grant = registration.sessionGrant();
            RequestContext context = new RequestContext(requestId.get(),
                    grant.workloadSubject(), PrincipalType.WORKLOAD, grant.workloadIssuer(), grant.tenantId(),
                    Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read"));
            String deploymentId = deploymentId(registration.source());
            if (deploymentId != null) {
                if (deployments == null
                        || !grant.capabilities().contains(EmbedCapability.DEPLOYMENT_OBSERVE)) {
                    unavailable(exchange, false);
                    return;
                }
                RequestContext deploymentContext = deploymentContext(requestId.get(), registration);
                var view = deployments.localDeploymentView(deploymentContext, deploymentId);
                if (view.isEmpty() || !sessions.bind(session, view.orElseThrow())) {
                    unavailable(exchange, false);
                    return;
                }
                audit(requestId.get(), registration, EmbedSecurityAuditSink.Phase.PROJECTION_READ);
                if (registration.source() instanceof EmbedViewerSource.DeploymentV2) {
                    json(exchange, 200, deploymentV2Json(registration, deploymentContext,
                            view.orElseThrow()));
                } else {
                    json(exchange, 200, view.orElseThrow().toJson());
                }
                return;
            }
            EmbedProjectionResolution resolution = configuration.projections().read(context, registration);
            if (resolution instanceof EmbedProjectionResolution.Available available) {
                audit(context.requestId(), registration, EmbedSecurityAuditSink.Phase.PROJECTION_READ);
                json(exchange, 200, available.projection().toJson());
            } else if (resolution instanceof EmbedProjectionResolution.DataTooLarge) {
                error(exchange, 413, "EMBED_DATA_TOO_LARGE");
            } else if (resolution instanceof EmbedProjectionResolution.TemporarilyUnavailable) {
                temporary(exchange);
            } else {
                unavailable(exchange, false);
            }
        } catch (IllegalArgumentException invalid) {
            invalid(exchange);
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    /** Signed, credentialed-fetch observation stream for an opt-in deployment registration. */
    public void observation(HttpExchange exchange) throws IOException {
        observation(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void observation(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        observation(exchange, requestContext::requestId);
    }

    private void observation(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, OBSERVATION_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || !viewerRequest(exchange) || hasCookie(exchange)) return;
        String bearer = bearer(exchange);
        if (bearer == null) { unavailable(exchange, false); return; }
        Map<String, String> body = bodyOneOf(exchange, Set.of(
                Set.of("nonce", "jti", "issuedAt", "signature", "cursor"),
                Set.of("nonce", "jti", "issuedAt", "signature", "cursor", "processInstanceId")));
        if (body == null) return;
        var session = sessions.resolve(bearer, configuration.registrations());
        if (session == null || !session.challenge().equals(body.get("nonce"))) {
            unavailable(exchange, false); return;
        }
        var registration = session.registration();
        String deploymentId = deploymentId(registration.source());
        if (deploymentId == null
                || !registration.sessionGrant().capabilities().contains(EmbedCapability.DEPLOYMENT_OBSERVE)
                || deployments == null) {
            unavailable(exchange, false); return;
        }
        try {
            validatedParentOrigin(registration);
            Instant issuedAt = Instant.parse(body.get("issuedAt"));
            byte[] signature = decode(body.get("signature"), 64);
            if (!proofs.verifyObservationAndConsume(bearer, registration.revision(), body.get("nonce"),
                    body.get("jti"), "POST", OBSERVATION_PATH, issuedAt, session.key(), signature)) {
                unavailable(exchange, false); return;
            }
            RequestContext context = deploymentContext(requestId.get(), registration);
            var view = deployments.localDeploymentView(context, deploymentId);
            if (view.isEmpty() || !sessions.bind(session, view.orElseThrow())) {
                unavailable(exchange, false); return;
            }
            var bound = view.orElseThrow();
            var binding = new DeploymentObservationCursorStore.Binding(
                    "embed:" + registration.registrationId() + ":" + registration.revision() + ":"
                            + EmbedLaunchTicketAuthority.digest(bearer), deploymentId,
                    bound.source().incarnationId(), bound.source().graphVersion(),
                    body.getOrDefault("processInstanceId", "").isBlank()
                            ? null : body.get("processInstanceId"));
            if (binding.processInstanceId() != null
                    && !authorizedRun(context, bound, binding.processInstanceId())) {
                unavailable(exchange, false); return;
            }
            long sequence;
            String supplied = body.get("cursor");
            if (supplied.isBlank()) sequence = 0;
            else {
                var resolved = cursors.resolve(supplied, binding);
                if (resolved == null) {
                    beginObservation(exchange);
                    try (var output = exchange.getResponseBody()) {
                        writeTerminal(output, "source-gap", binding, "CURSOR_UNAVAILABLE");
                    }
                    return;
                }
                sequence = resolved.sequence();
            }
            audit(requestId.get(), registration, EmbedSecurityAuditSink.Phase.OBSERVATION_READ);
            streamObservation(exchange, bearer, session, context, binding, sequence, supplied.isBlank());
        } catch (IllegalArgumentException invalid) {
            invalid(exchange);
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
            unavailable(exchange, false);
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    /** Authoritative, exact-deployment run selector source for v2 viewers. */
    public void runs(HttpExchange exchange) throws IOException {
        runs(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void runs(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        runs(exchange, requestContext::requestId);
    }

    private void runs(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, RUNS_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || !viewerRequest(exchange) || hasCookie(exchange)) return;
        String bearer = bearer(exchange);
        if (bearer == null) { unavailable(exchange, false); return; }
        Map<String, String> body = body(exchange, Set.of("nonce", "jti", "issuedAt", "signature"));
        if (body == null) return;
        var session = sessions.resolve(bearer, configuration.registrations());
        if (session == null || !session.challenge().equals(body.get("nonce"))) {
            unavailable(exchange, false); return;
        }
        var registration = session.registration();
        if (!(registration.source() instanceof EmbedViewerSource.DeploymentV2 source)
                || deployments == null
                || !registration.sessionGrant().capabilities().contains(EmbedCapability.DEPLOYMENT_RUN_READ)) {
            unavailable(exchange, false); return;
        }
        try {
            validatedParentOrigin(registration);
            if (!verifyProof(body, bearer, registration, session, RUNS_PATH)) {
                unavailable(exchange, false); return;
            }
            RequestContext context = deploymentContext(requestId.get(), registration);
            var view = deployments.localDeploymentView(context, source.deploymentId());
            if (view.isEmpty() || !sessions.bind(session, view.orElseThrow())) {
                unavailable(exchange, false); return;
            }
            json(exchange, 200, runsJson(context, view.orElseThrow()));
        } catch (IllegalArgumentException invalid) {
            invalid(exchange);
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
            unavailable(exchange, false);
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    /** Separately authorized, idempotent server-side start for an exact v2 deployment binding. */
    public void startExecution(HttpExchange exchange) throws IOException {
        startExecution(exchange, () -> AuthenticatedPrincipalAttribute.requestId(exchange));
    }

    public void startExecution(HttpExchange exchange, HttpRequestContext requestContext) throws IOException {
        startExecution(exchange, requestContext::requestId);
    }

    private void startExecution(HttpExchange exchange, Supplier<String> requestId) throws IOException {
        if (!requireExactPath(exchange, START_EXECUTION_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || !viewerRequest(exchange) || hasCookie(exchange)) return;
        String bearer = bearer(exchange);
        if (bearer == null) { unavailable(exchange, false); return; }
        Map<String, String> body = body(exchange,
                Set.of("nonce", "jti", "issuedAt", "signature", "requestId"));
        if (body == null) return;
        var session = sessions.resolve(bearer, configuration.registrations());
        if (session == null || !session.challenge().equals(body.get("nonce"))) {
            unavailable(exchange, false); return;
        }
        var registration = session.registration();
        if (!(registration.source() instanceof EmbedViewerSource.DeploymentV2 source)
                || !source.showStartExecution() || deployments == null
                || !registration.sessionGrant().capabilities().contains(EmbedCapability.DEPLOYMENT_EXECUTE)) {
            unavailable(exchange, false); return;
        }
        try {
            validatedParentOrigin(registration);
            if (!verifyProof(body, bearer, registration, session, START_EXECUTION_PATH)) {
                unavailable(exchange, false); return;
            }
            RequestContext context = deploymentContext(requestId.get(), registration);
            var view = deployments.localDeploymentView(context, source.deploymentId());
            if (view.isEmpty() || !sessions.bind(session, view.orElseThrow())) {
                unavailable(exchange, false); return;
            }
            var bound = view.orElseThrow();
            var result = deployments.startEmbedDeploymentExecution(context, source.deploymentId(),
                    bound.source().incarnationId(), bound.source().graphVersion(), body.get("requestId"));
            int status = switch (result.outcome()) {
                case ACCEPTED -> 202;
                case DUPLICATE -> 200;
                case REFUSED -> 409;
                case RECONCILE -> 503;
            };
            json(exchange, status, "{\"outcome\":\"" + result.outcome()
                    + "\",\"requestId\":\"" + JsonStrings.escape(result.requestId()) + "\"}");
        } catch (IllegalArgumentException invalid) {
            invalid(exchange);
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
            unavailable(exchange, false);
        } catch (RuntimeException failure) {
            temporary(exchange);
        }
    }

    private void streamObservation(HttpExchange exchange, String bearer,
                                   EmbedBrowserSessionAuthority.ActiveSession session,
                                   RequestContext context,
                                   DeploymentObservationCursorStore.Binding binding,
                                   long sequence, boolean firstAttachment) throws IOException {
        var wakeup = new ObservationWakeup();
        AutoCloseable subscription = deployments.subscribeToLocalDeploymentEvents(context,
                binding.deploymentId(), binding.incarnationId(), binding.graphVersion(), wakeup::signal);
        try {
            DeploymentEventBatch initial = deployments.localDeploymentEventsAfter(context, binding.deploymentId(),
                    binding.incarnationId(), binding.graphVersion(), sequence);
            if (initial.status() == DeploymentEventBatch.Status.UNAVAILABLE
                    || initial.status() == DeploymentEventBatch.Status.SOURCE_CHANGED) {
                unavailable(exchange, false); return;
            }
            beginObservation(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                if (initial.status() == DeploymentEventBatch.Status.GAP) {
                    writeTerminal(output, "source-gap", binding, "REPLAY_WINDOW_EXCEEDED"); return;
                }
                var resolved = resolveEmbedView(bearer, session, context, binding);
                if (resolved.view() == null) {
                    writeTerminal(output, "source-invalidated", binding, resolved.reason()); return;
                }
                var current = resolved.view();
                var lifecycle = current.lifecycle();
                writeLifecycle(output, binding, lifecycle);
                long sent;
                if (binding.processInstanceId() != null && firstAttachment) {
                    if (!writeDurableReplay(output, context, binding)) {
                        writeTerminal(output, "source-gap", binding, "DURABLE_REPLAY_UNAVAILABLE");
                        return;
                    }
                    sent = initial.latestSequence();
                } else {
                    sent = firstAttachment ? initial.latestSequence()
                            : writeObservationBatch(output, bearer, session, context, binding, initial, sequence);
                }
                if (sent < 0) return;
                while (!Thread.currentThread().isInterrupted()) {
                    wakeup.await(1_000);
                    resolved = resolveEmbedView(bearer, session, context, binding);
                    if (resolved.view() == null) {
                        writeTerminal(output, "source-invalidated", binding, resolved.reason()); return;
                    }
                    current = resolved.view();
                    if (current.lifecycle() != lifecycle) {
                        lifecycle = current.lifecycle();
                        writeLifecycle(output, binding, lifecycle);
                    }
                    DeploymentEventBatch batch = deployments.localDeploymentEventsAfter(context,
                            binding.deploymentId(), binding.incarnationId(), binding.graphVersion(), sent);
                    if (batch.status() == DeploymentEventBatch.Status.GAP) {
                        if (binding.processInstanceId() == null
                                || !writeDurableReplay(output, context, binding)) {
                            writeTerminal(output, "source-gap", binding, "REPLAY_WINDOW_EXCEEDED"); return;
                        }
                        DeploymentEventBatch currentBatch = deployments.localDeploymentEventsAfter(context,
                                binding.deploymentId(), binding.incarnationId(), binding.graphVersion(), 0);
                        if (currentBatch.status() != DeploymentEventBatch.Status.AVAILABLE) return;
                        sent = currentBatch.latestSequence();
                        continue;
                    }
                    if (batch.status() != DeploymentEventBatch.Status.AVAILABLE) {
                        resolved = resolveEmbedView(bearer, session, context, binding);
                        writeTerminal(output, "source-invalidated", binding,
                                resolved.view() == null ? resolved.reason() : "VERSION_MISMATCH");
                        return;
                    }
                    long before = sent;
                    sent = writeObservationBatch(output, bearer, session, context, binding, batch, sent);
                    if (sent < 0) return;
                    if (before == sent) {
                        output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException revoked) {
            // The next credentialed fetch must establish current authority again.
        } finally {
            try { subscription.close(); } catch (Exception ignored) { }
            exchange.close();
        }
    }

    /** One pending observation hint; event batches, not hint counts, are the delivery authority. */
    static final class ObservationWakeup {
        private static final Object SIGNAL = new Object();
        private final ArrayBlockingQueue<Object> pending = new ArrayBlockingQueue<>(1);

        void signal(ai.ravenroot.api.application.ExecutionEvent ignored) {
            pending.offer(SIGNAL);
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS) != null;
        }
    }

    private long writeObservationBatch(OutputStream output, String bearer,
                                       EmbedBrowserSessionAuthority.ActiveSession session,
                                       RequestContext context,
                                       DeploymentObservationCursorStore.Binding binding,
                                       DeploymentEventBatch batch, long sequence) throws IOException {
        long sent = sequence;
        for (var event : batch.events()) {
            if (binding.processInstanceId() != null
                    && !binding.processInstanceId().equals(event.processInstanceId().toString())) {
                sent = event.sequence();
                continue;
            }
            var resolved = resolveEmbedView(bearer, session, context, binding);
            if (resolved.view() == null) {
                writeTerminal(output, "source-invalidated", binding, resolved.reason());
                return -1;
            }
            String cursor = cursors.issue(binding, event.sequence());
            String body = executionJson(binding, event);
            output.write(("id: " + cursor + "\nevent: execution\ndata: " + body + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            sent = event.sequence();
        }
        return Math.max(sent, batch.latestSequence());
    }

    private boolean writeDurableReplay(OutputStream output, RequestContext context,
                                       DeploymentObservationCursorStore.Binding binding) throws IOException {
        java.util.UUID processId = java.util.UUID.fromString(binding.processInstanceId());
        long sequence = 0;
        output.write(("event: runtime-reset\ndata: {\"type\":\"runtime-reset\",\"deploymentId\":\""
                + JsonStrings.escape(binding.deploymentId()) + "\",\"incarnationId\":\""
                + JsonStrings.escape(binding.incarnationId()) + "\",\"graphVersion\":\""
                + JsonStrings.escape(binding.graphVersion()) + "\",\"processInstanceId\":\""
                + binding.processInstanceId() + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
        while (sequence < 100_000) {
            final DurableProcessEventPage page;
            try {
                page = deployments.embedDeploymentRunReplayPage(context, binding.deploymentId(),
                        binding.incarnationId(), binding.graphVersion(), processId, sequence, 512);
            } catch (ai.ravenroot.api.persistence.ExecutionStoreException truncated) {
                return false;
            }
            long pageEnd = validateDurableReplayPage(sequence, page, 512);
            if (pageEnd < 0) return false;
            for (var event : page.events()) {
                output.write(("event: execution\ndata: " + durableExecutionJson(binding, event) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            sequence = pageEnd;
            if (page.events().isEmpty()) {
                break;
            }
            if (page.events().size() < 512) {
                break;
            }
        }
        if (sequence >= 100_000) return false;
        output.flush();
        return true;
    }

    static long validateDurableReplayPage(long afterSequence, DurableProcessEventPage page, int limit) {
        if (afterSequence + 1 < page.retainedFromSequence()) return -1;
        long expected = afterSequence + 1;
        for (var event : page.events()) {
            if (event.streamSequence() != expected++) return -1;
        }
        long pageEnd = expected - 1;
        if (page.events().size() < limit && pageEnd + 1 != page.nextSequence()) return -1;
        return pageEnd;
    }

    private EmbedViewResolution resolveEmbedView(String bearer,
                                                  EmbedBrowserSessionAuthority.ActiveSession expected,
                                                  RequestContext context,
                                                  DeploymentObservationCursorStore.Binding binding) {
        var currentSession = sessions.resolve(bearer, configuration.registrations());
        if (currentSession != expected) return new EmbedViewResolution(null, "AUTHORITY_CHANGED");
        var view = deployments.localDeploymentView(context, binding.deploymentId());
        if (view.isEmpty()) return new EmbedViewResolution(null, "UNDEPLOYED");
        var current = view.orElseThrow();
        if (!current.source().incarnationId().equals(binding.incarnationId())
                || !current.source().graphVersion().equals(binding.graphVersion())
                || !sessions.bind(expected, current)) {
            return new EmbedViewResolution(null, "VERSION_MISMATCH");
        }
        return new EmbedViewResolution(current, null);
    }

    private record EmbedViewResolution(DeploymentViewerView view, String reason) { }

    private boolean authorizedRun(RequestContext context, DeploymentViewerView view, String processInstanceId) {
        try {
            return deployments.embedDeploymentRun(context, view.source().deploymentId(),
                    view.source().incarnationId(), view.source().graphVersion(),
                    java.util.UUID.fromString(processInstanceId)).isPresent();
        } catch (IllegalArgumentException invalidIdentity) {
            return false;
        }
    }

    private boolean verifyProof(Map<String, String> body, String bearer,
                                EmbedRegistrationAggregate registration,
                                EmbedBrowserSessionAuthority.ActiveSession session,
                                String path) {
        Instant issuedAt = Instant.parse(body.get("issuedAt"));
        byte[] signature = decode(body.get("signature"), 64);
        return proofs.verifyRequestAndConsume(bearer, registration.revision(), body.get("nonce"),
                body.get("jti"), "POST", path, issuedAt, session.key(), signature);
    }

    private String deploymentV2Json(EmbedRegistrationAggregate registration, RequestContext context,
                                    DeploymentViewerView view) {
        String base = view.toJson().replace("\"viewerSourceVersion\":\"1\"",
                "\"viewerSourceVersion\":\"2\"");
        return base.substring(0, base.length() - 1)
                + ",\"showStartExecution\":"
                + ((EmbedViewerSource.DeploymentV2) registration.source()).showStartExecution()
                + ",\"runs\":" + runsArray(context, view) + "}";
    }

    private String runsJson(RequestContext context, DeploymentViewerView view) {
        return "{\"deploymentId\":\"" + JsonStrings.escape(view.source().deploymentId())
                + "\",\"incarnationId\":\"" + JsonStrings.escape(view.source().incarnationId())
                + "\",\"graphVersion\":\"" + JsonStrings.escape(view.source().graphVersion())
                + "\",\"runs\":" + runsArray(context, view) + "}";
    }

    private String runsArray(RequestContext context, DeploymentViewerView view) {
        Instant terminalCutoff = configuration.clock().instant().minus(Duration.ofHours(1));
        List<ai.ravenroot.api.persistence.ProcessInventoryEntry> rows = deployments.embedDeploymentRuns(
                context, view.source().deploymentId(), view.source().incarnationId(),
                view.source().graphVersion(), 256).stream()
                .filter(row -> !row.status().terminal() || !row.updatedAt().isBefore(terminalCutoff))
                .sorted(java.util.Comparator.comparing(
                        ai.ravenroot.api.persistence.ProcessInventoryEntry::updatedAt).reversed())
                .limit(64)
                .toList();
        var json = new StringBuilder("[");
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (index > 0) json.append(',');
            String outcome = row.terminationReason() == null
                    ? (row.status().terminal() ? row.status().name() : null)
                    : row.terminationReason().name();
            json.append("{\"processInstanceId\":\"")
                    .append(row.key().processInstanceId()).append("\",\"status\":\"")
                    .append(row.status()).append("\",\"outcome\":")
                    .append(nullable(outcome)).append(",\"updatedAt\":\"")
                    .append(row.updatedAt()).append("\"}");
        }
        return json.append(']').toString();
    }

    private static String deploymentId(EmbedViewerSource source) {
        if (source instanceof EmbedViewerSource.Deployment deployment) return deployment.deploymentId();
        if (source instanceof EmbedViewerSource.DeploymentV2 deployment) return deployment.deploymentId();
        return null;
    }

    private static RequestContext deploymentContext(String requestId,
                                                     EmbedRegistrationAggregate registration) {
        var grant = registration.sessionGrant();
        var scopes = new HashSet<String>();
        scopes.add("ravenroot.embed.graph.read");
        scopes.add("ravenroot.deployment.observe");
        if (grant.capabilities().contains(EmbedCapability.DEPLOYMENT_RUN_READ)) {
            scopes.add("ravenroot.embed.deployment.runs.read");
        }
        if (grant.capabilities().contains(EmbedCapability.DEPLOYMENT_EXECUTE)) {
            scopes.add("ravenroot.embed.deployment.execute");
        }
        return new RequestContext(requestId, grant.workloadSubject(),
                PrincipalType.WORKLOAD, grant.workloadIssuer(), grant.tenantId(), Set.of(Role.VIEWER),
                Set.copyOf(scopes));
    }

    private static void beginObservation(HttpExchange exchange) throws IOException {
        privateResponse(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Ravenroot-Event-Source", "DEPLOYMENT_RING");
        exchange.getResponseHeaders().set("X-Ravenroot-Event-Continuity", "PROCESS_LOCAL");
        exchange.sendResponseHeaders(200, 0);
    }

    private static String executionJson(DeploymentObservationCursorStore.Binding binding,
                                        ai.ravenroot.api.application.ExecutionEvent event) {
        String description = PublicExecutionDescription.forType(event.type(), event.publicReason());
        return "{\"type\":\"execution\",\"deploymentId\":\"" + JsonStrings.escape(binding.deploymentId())
                + "\",\"incarnationId\":\"" + JsonStrings.escape(binding.incarnationId())
                + "\",\"graphVersion\":\"" + JsonStrings.escape(binding.graphVersion())
                + "\",\"processInstanceId\":\"" + event.processInstanceId()
                + "\",\"event\":{\"occurredAt\":\"" + event.occurredAt() + "\",\"type\":\""
                + event.type() + "\",\"nodeId\":" + nullable(event.nodeId())
                + ",\"edgeId\":" + nullable(event.edgeId())
                + ",\"activeInstances\":" + event.activeInstances()
                + ",\"inFlightArrivals\":" + event.inFlightArrivals()
                + ",\"fallback\":" + event.fallback()
                + ",\"executionId\":\"" + event.executionId() + "\",\"traversalId\":\""
                + event.traversalId() + "\",\"publicReason\":" + nullable(event.publicReason())
                + ",\"description\":\"" + JsonStrings.escape(description) + "\"}}";
    }

    private static String durableExecutionJson(DeploymentObservationCursorStore.Binding binding,
                                                ai.ravenroot.api.application.DurableExecutionEvent event) {
        return "{\"type\":\"execution\",\"deploymentId\":\""
                + JsonStrings.escape(binding.deploymentId()) + "\",\"incarnationId\":\""
                + JsonStrings.escape(binding.incarnationId()) + "\",\"graphVersion\":\""
                + JsonStrings.escape(binding.graphVersion()) + "\",\"processInstanceId\":\""
                + event.processInstanceId() + "\",\"event\":{\"occurredAt\":\""
                + event.occurredAt() + "\",\"type\":\"" + JsonStrings.escape(event.eventType())
                + "\",\"nodeId\":" + nullable(event.nodeId()) + ",\"edgeId\":"
                + nullable(event.edgeId()) + ",\"activeInstances\":0,\"inFlightArrivals\":0,"
                + "\"fallback\":false,\"executionId\":\"" + event.traversalId()
                + "\",\"traversalId\":\"" + event.traversalId() + "\",\"sequence\":"
                + event.streamSequence() + ",\"publicReason\":null,\"description\":\"\"}}";
    }

    private static String nullable(String value) {
        return value == null ? "null" : "\"" + JsonStrings.escape(value) + "\"";
    }

    private static void writeTerminal(OutputStream output, String type,
                                      DeploymentObservationCursorStore.Binding binding,
                                      String reason) throws IOException {
        String body = "{\"type\":\"" + type + "\",\"deploymentId\":\""
                + JsonStrings.escape(binding.deploymentId()) + "\",\"incarnationId\":\""
                + JsonStrings.escape(binding.incarnationId()) + "\",\"graphVersion\":\""
                + JsonStrings.escape(binding.graphVersion()) + "\",\"reason\":\""
                + JsonStrings.escape(reason) + "\"}";
        output.write(("event: " + type + "\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeLifecycle(OutputStream output,
                                       DeploymentObservationCursorStore.Binding binding,
                                       ai.ravenroot.api.application.LocalDeploymentState lifecycle)
            throws IOException {
        String body = "{\"type\":\"lifecycle\",\"deploymentId\":\""
                + JsonStrings.escape(binding.deploymentId()) + "\",\"incarnationId\":\""
                + JsonStrings.escape(binding.incarnationId()) + "\",\"graphVersion\":\""
                + JsonStrings.escape(binding.graphVersion()) + "\",\"lifecycle\":\""
                + lifecycle + "\"}";
        output.write(("event: lifecycle\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void audit(String requestId, EmbedRegistrationAggregate registration,
                       EmbedSecurityAuditSink.Phase phase) {
        var grant = Objects.requireNonNull(registration, "registration").sessionGrant();
        configuration.audit().record(new EmbedSecurityAuditSink.Event(configuration.clock().instant(),
                requestId, grant.tenantId(), grant.workloadSubject(),
                phase, EmbedSecurityAuditSink.Outcome.ALLOWED));
    }

    private EmbedParentOrigin validatedParentOrigin(EmbedRegistrationAggregate registration) {
        return EmbedOriginBoundary.fromAuthority(
                Objects.requireNonNull(registration, "registration").sessionGrant().parentOrigin(),
                configuration.viewerOrigin()).parent();
    }

    /**
     * JDK {@code HttpServer} contexts are prefix matched. Pin every embed request to its declared
     * route before authentication or consumption of any one-use credential.
     */
    public static boolean requireExactPath(HttpExchange exchange, String expectedPath) throws IOException {
        if (Objects.requireNonNull(expectedPath, "expectedPath")
                .equals(exchange.getRequestURI().getPath())) return true;
        privateResponse(exchange);
        unavailable(exchange, false);
        return false;
    }

    private boolean viewerRequest(HttpExchange exchange) throws IOException {
        String origin = requiredSingleHeader(exchange, "Origin");
        if (!configuration.viewerOrigin().value().equals(origin)
                || !fetch(exchange, "cors", "empty")
                || !"same-origin".equals(requiredSingleHeader(exchange, "Sec-Fetch-Site"))) {
            unavailable(exchange, false);
            return false;
        }
        return true;
    }

    private static boolean fetch(HttpExchange exchange, String mode, String destination) throws IOException {
        if (!mode.equals(requiredSingleHeader(exchange, "Sec-Fetch-Mode"))
                || !destination.equals(requiredSingleHeader(exchange, "Sec-Fetch-Dest"))) {
            unavailable(exchange, false);
            return false;
        }
        return true;
    }

    private static boolean browserMetadataPresent(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().containsKey("Origin")
                || exchange.getRequestHeaders().keySet().stream()
                .anyMatch(name -> name.regionMatches(true, 0, "Sec-Fetch-", 0, 10))) {
            invalid(exchange);
            return true;
        }
        return false;
    }

    private static boolean hasCookie(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().containsKey("Cookie")) {
            unavailable(exchange, false);
            return true;
        }
        return false;
    }

    private static Map<String, String> body(HttpExchange exchange, Set<String> schema) throws IOException {
        return bodyOneOf(exchange, Set.of(schema));
    }

    private static Map<String, String> bodyOneOf(HttpExchange exchange, Set<Set<String>> schemas)
            throws IOException {
        String contentType = optionalSingleHeader(exchange, "Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            invalid(exchange); return null;
        }
        byte[] bytes;
        try (var input = exchange.getRequestBody()) { bytes = input.readNBytes(MAX_BODY_BYTES + 1); }
        if (bytes.length > MAX_BODY_BYTES) { error(exchange, 413, "EMBED_REQUEST_TOO_LARGE"); return null; }
        try { return EmbedRequestJson.parseOneOf(bytes, schemas); }
        catch (IllegalArgumentException invalid) { invalid(exchange); return null; }
    }

    private static ECPublicKey publicKey(String x, String y) {
        byte[] xBytes = decode(x, 32); byte[] yBytes = decode(y, 32);
        try {
            var point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(point, P256EmbedProofVerifier.parameters()));
        } catch (java.security.GeneralSecurityException invalid) {
            throw new IllegalArgumentException("invalid P-256 key", invalid);
        }
    }

    private static byte[] decode(String value, int expectedLength) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != expectedLength || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(value)) throw new IllegalArgumentException("encoding");
            return decoded;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("encoding", invalid); }
    }

    private static String bearer(HttpExchange exchange) throws IOException {
        String value = optionalSingleHeader(exchange, "Authorization");
        return value != null && value.startsWith("Bearer ") && value.length() > 7
                ? value.substring(7) : null;
    }

    private static String ticket(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || !raw.startsWith("ticket=") || raw.indexOf('&') >= 0) return null;
        try {
            String value = URLDecoder.decode(raw.substring(7), StandardCharsets.UTF_8);
            return value.isBlank() ? null : value;
        } catch (IllegalArgumentException invalidEncoding) {
            return null;
        }
    }

    private static String requiredSingleHeader(HttpExchange exchange, String name) throws IOException {
        String value = optionalSingleHeader(exchange, name);
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        return value;
    }

    private static String optionalSingleHeader(HttpExchange exchange, String name) throws IOException {
        var values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1) return null;
        return values.getFirst();
    }

    private static boolean method(HttpExchange exchange, String allowed) throws IOException {
        if (allowed.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", allowed);
        error(exchange, 405, "EMBED_METHOD_NOT_ALLOWED");
        return false;
    }

    private static void privateResponse(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().remove("Access-Control-Allow-Origin");
        exchange.getResponseHeaders().remove("Access-Control-Allow-Credentials");
        exchange.getResponseHeaders().remove("Set-Cookie");
    }

    private static void invalid(HttpExchange exchange) throws IOException { error(exchange, 400, "EMBED_REQUEST_INVALID"); }
    private static void temporary(HttpExchange exchange) throws IOException { error(exchange, 503, "EMBED_TEMPORARILY_UNAVAILABLE"); }
    private static void unavailable(HttpExchange exchange, boolean temporary) throws IOException {
        error(exchange, temporary ? 503 : 403,
                temporary ? "EMBED_TEMPORARILY_UNAVAILABLE" : "EMBED_SESSION_UNAVAILABLE");
    }
    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        json(exchange, status, "{\"error\":\"" + code + "\"}");
    }
    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }
    private static void bytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
