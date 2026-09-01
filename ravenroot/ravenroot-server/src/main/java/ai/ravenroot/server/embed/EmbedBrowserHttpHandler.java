package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedProjectionResolution;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.server.AuthenticatedPrincipalAttribute;
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
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete five-route server adapter for the distinct-origin, static embedded projection. */
public final class EmbedBrowserHttpHandler {
    public static final String CREATE_PATH = "/v1/embed/sessions";
    public static final String ACKNOWLEDGEMENT_PATH = "/v1/embed/acknowledgements";
    public static final String LAUNCH_PATH = "/v1/embed/launch";
    public static final String EXCHANGE_PATH = "/v1/embed/exchange";
    public static final String PROJECTION_PATH = "/v1/embed/projection";
    public static final String BOOTSTRAP_SCRIPT_PATH = "/embed-bootstrap.js";
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final EmbedBrowserConfiguration configuration;
    private final EmbedLaunchTicketAuthority tickets;
    private final EmbedBrowserSessionAuthority sessions;
    private final P256EmbedProofVerifier proofs;

    public EmbedBrowserHttpHandler(EmbedBrowserConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (!configuration.active()) throw new IllegalArgumentException("embed browser is disabled");
        this.tickets = new EmbedLaunchTicketAuthority(configuration.clock(), configuration.ticketTtl(),
                configuration.ticketCapacity());
        this.sessions = new EmbedBrowserSessionAuthority(configuration.clock(), configuration.exchangeTtl(),
                configuration.bearerTtl(), configuration.sessionCapacity());
        this.proofs = new P256EmbedProofVerifier(configuration.clock(), configuration.proofTtl(),
                configuration.replayCapacity());
    }

    public void createSession(HttpExchange exchange) throws IOException {
        if (!requireExactPath(exchange, CREATE_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || browserMetadataPresent(exchange) || hasCookie(exchange)) return;
        Map<String, String> body = body(exchange, Set.of("registrationId"));
        if (body == null) return;
        EmbedRegistrationResolution resolution = configuration.sessionCreation().resolve(
                AuthenticatedPrincipalAttribute.requestContext(exchange), body.get("registrationId"));
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
            audit(exchange, available.aggregate(), EmbedSecurityAuditSink.Phase.SESSION_CREATED);
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
            audit(exchange, available.registration(), EmbedSecurityAuditSink.Phase.TICKET_CONSUMED);
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
                    .map(theme -> "\"" + theme.wireValue() + "\"").orElse("null") + "}";
            String themeAttribute = available.registration().sessionGrant().themeOverride()
                    .map(theme -> " data-theme=\"" + theme.wireValue() + "\"").orElse("");
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
                    + "aria-label=\"Graph view controls\"><label class=\"embed-mode-label\">View "
                    + "<select data-viewer-mode><option value=\"cyto\">Cyto</option>"
                    + "<option value=\"n8n\">N8N</option><option value=\"elastic\">Elastic</option>"
                    + "</select></label><button type=\"button\" "
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
        if (!requireExactPath(exchange, ACKNOWLEDGEMENT_PATH)) return;
        privateResponse(exchange);
        if (!method(exchange, "POST") || browserMetadataPresent(exchange) || hasCookie(exchange)) return;
        Map<String, String> body = body(exchange,
                Set.of("registrationId", "acknowledgementId", "channelId", "correlationId"));
        if (body == null) return;
        EmbedRegistrationResolution resolution = configuration.sessionCreation().resolve(
                AuthenticatedPrincipalAttribute.requestContext(exchange), body.get("registrationId"));
        if (!(resolution instanceof EmbedRegistrationResolution.Available available)) {
            unavailable(exchange, resolution instanceof EmbedRegistrationResolution.Temporary);
            return;
        }
        try {
            validatedParentOrigin(available.aggregate());
            if (!sessions.acknowledge(body.get("acknowledgementId"), body.get("channelId"),
                    body.get("correlationId"), available.aggregate(), configuration.registrations(),
                    () -> audit(exchange, available.aggregate(),
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
            audit(exchange, pending.registration(), EmbedSecurityAuditSink.Phase.BEARER_ISSUED);
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
            RequestContext context = new RequestContext(AuthenticatedPrincipalAttribute.requestId(exchange),
                    grant.workloadSubject(), PrincipalType.WORKLOAD, grant.workloadIssuer(), grant.tenantId(),
                    Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read"));
            EmbedProjectionResolution resolution = configuration.projections().read(context, registration);
            if (resolution instanceof EmbedProjectionResolution.Available available) {
                audit(exchange, registration, EmbedSecurityAuditSink.Phase.PROJECTION_READ);
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

    private void audit(HttpExchange exchange, EmbedRegistrationAggregate registration,
                       EmbedSecurityAuditSink.Phase phase) {
        var grant = Objects.requireNonNull(registration, "registration").sessionGrant();
        configuration.audit().record(new EmbedSecurityAuditSink.Event(configuration.clock().instant(),
                AuthenticatedPrincipalAttribute.requestId(exchange), grant.tenantId(), grant.workloadSubject(),
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
        String contentType = optionalSingleHeader(exchange, "Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            invalid(exchange); return null;
        }
        byte[] bytes;
        try (var input = exchange.getRequestBody()) { bytes = input.readNBytes(MAX_BODY_BYTES + 1); }
        if (bytes.length > MAX_BODY_BYTES) { error(exchange, 413, "EMBED_REQUEST_TOO_LARGE"); return null; }
        try { return EmbedRequestJson.parse(bytes, schema); }
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
