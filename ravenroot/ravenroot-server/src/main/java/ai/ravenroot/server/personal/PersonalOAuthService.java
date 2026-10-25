package ai.ravenroot.server.personal;

import ai.ravenroot.api.security.*;
import ai.ravenroot.core.security.nodepackage.TenantCredentialResolver;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Browser-bound optional identity, connection lifecycle, and finite execution authority. */
public final class PersonalOAuthService implements TenantCredentialResolver {
    public static final String ISSUER = "urn:ravenroot:personal:v1";
    private static final String AUTH = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String IDENTITY_SCOPES = "openid profile email offline_access";
    private static final String PLAN_SCOPES = IDENTITY_SCOPES + " resource.invoke chatgpt.tokens.use.direct";
    private static final String COOKIE = "RavenrootPersonal";
    private static final String REGISTRATION_COOKIE = "RavenrootRegistration";
    private static final Set<String> TERMINAL_REFRESH = Set.of("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused");
    private static final java.util.concurrent.ScheduledExecutorService BODY_DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "personal-provider-deadlines"); thread.setDaemon(true); return thread;
            });
    private final PersonalOAuthConfiguration configuration;
    private final ProtectedConnectionStore store;
    private final HttpClient http;
    @FunctionalInterface public interface IdentityVerifier { Map<String, Object> verify(String token, String client, String nonce); }
    private final IdentityVerifier verifier;
    private final Clock clock;
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Unlocked> unlocked = new ConcurrentHashMap<>();
    private final TenantCredentialResolver system;

    private record BrowserSession(String owner, String connection, Instant expires, String perimeter) { }
    private record Pending(String browser, String owner, String connection, String client, String verifier,
                           String nonce, Instant expires, String perimeter, boolean planUse) { }
    private record Unlocked(String owner, String connection, byte[] key, Instant expires, String purpose) { }

    public PersonalOAuthService(PersonalOAuthConfiguration configuration, TenantCredentialResolver system) {
        this(configuration, system, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new OpenAiIdentityVerifier(), Clock.systemUTC());
    }
    public PersonalOAuthService(PersonalOAuthConfiguration configuration, TenantCredentialResolver system,
            HttpClient http, IdentityVerifier verifier, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration); this.system = Objects.requireNonNull(system);
        this.http = http; this.verifier = verifier; this.clock = clock;
        this.store = new ProtectedConnectionStore(configuration.directory(), configuration.keyFile());
        store.transaction(state -> { pruneExpiredGrants(state); state.computeIfAbsent("host", ignored -> "urn:uuid:" + UUID.randomUUID()); return null; });
    }

    /** Retains perimeter roles/scopes. A personal login cannot bypass the configured authenticator. */
    public AuthenticatedPrincipal principal(Headers headers, AuthenticatedPrincipal perimeter) {
        var session = current(headers, perimeterKey(perimeter));
        if (session == null || session.owner() == null) return perimeter;
        var roles = java.util.EnumSet.noneOf(Role.class); roles.addAll(perimeter.roles());
        if (roles.remove(Role.PLATFORM_ADMIN)) roles.add(Role.TENANT_ADMIN);
        return new AuthenticatedPrincipal(session.owner(), AuthenticatedPrincipal.Type.USER, ISSUER,
                "personal-" + session.owner(), roles, perimeter.scopes(), session.expires());
    }
    private static String perimeterKey(AuthenticatedPrincipal principal) {
        return principal.issuer() + "|" + principal.tenantId() + "|" + principal.type() + "|" + principal.subject();
    }
    private BrowserSession current(Headers headers, String perimeter) {
        String id = cookie(headers);
        var session = id == null ? null : sessions.get(id);
        if (session == null || !session.expires().isAfter(clock.instant()) || !session.perimeter().equals(perimeter)) return null;
        return session;
    }
    public boolean ownsSession(Headers headers, RequestContext context) {
        var s = sessions.get(Objects.toString(cookie(headers), ""));
        return s != null && s.owner() != null && s.owner().equals(context.subject()) && ISSUER.equals(context.issuer())
                && context.principalType() == PrincipalType.USER && context.tenantId().equals("personal-" + s.owner())
                && s.expires().isAfter(clock.instant());
    }

    public void callback(HttpExchange exchange) throws IOException {
        try {
            URI incoming = exchange.getRequestURI();
            if (!exchange.getRequestMethod().equals("GET") || !incoming.getRawPath().equals(configuration.callback().getRawPath())
                    || !configuration.callback().getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))
                    || incoming.isAbsolute() && (!incoming.getScheme().equals(configuration.callback().getScheme())
                        || !incoming.getRawAuthority().equals(configuration.callback().getRawAuthority()))) throw refused("sign-in-unverified");
            Map<String, String> query = query(incoming.getRawQuery());
            String state = query.getOrDefault("state", "");
            Pending attempt = pending.remove(state); // single consumption on success and every failure
            String browser = cookie(exchange.getRequestHeaders());
            if (attempt == null || !Objects.equals(browser, attempt.browser()) || !attempt.expires().isAfter(clock.instant())
                    || !sessions.containsKey(attempt.browser())) throw refused("sign-in-unverified");
            if (query.containsKey("error")) throw refused("consent-declined");
            String client = attempt.client();
            if (client.equals("dynamic_agent_client")) {
                client = query.get("client_id");
                if (client == null || !client.startsWith("oaiapp_") || client.length() > 256) throw refused("registration-incomplete");
            } else if (query.containsKey("client_id") && !client.equals(query.get("client_id"))) throw refused("sign-in-unverified");
            String code = query.get("code");
            if (code == null || code.isBlank() || code.length() > 8192) throw refused("sign-in-unverified");
            Map<String, Object> tokens = tokenRequest(Map.of("grant_type", "authorization_code", "client_id", client,
                    "code", code, "code_verifier", attempt.verifier(), "redirect_uri", configuration.callback().toString(), "resource", RESOURCE));
            var identity = verifier.verify(text(tokens, "id_token"), client, attempt.nonce());
            String issued = client;
            String subject = text(identity, "sub");
            String identityKey = digest("https://auth.openai.com|" + client + "|" + subject);
            String connectionId = attempt.connection() == null ? UUID.randomUUID().toString() : attempt.connection();
            Map<String, Object> linked = store.transaction(data -> {
                Map<String, Object> identities = map(data, "identities");
                Map<String, Object> connections = map(data, "connections");
                String existing = (String) identities.get(identityKey);
                String owner = attempt.owner() == null ? existing == null ? UUID.randomUUID().toString() : existing : attempt.owner();
                if (existing != null && !existing.equals(owner)) throw refused("account-already-linked");
                if (attempt.connection() != null) {
                    Map<String, Object> old = connection(data, owner, connectionId);
                    if (!subject.equals(old.get("subject")) || !issued.equals(old.get("client"))) throw refused("account-mismatch");
                    if (old.containsKey("salt") && attempt.planUse()) tokenMap(old, attempt.browser());
                }
                identities.put(identityKey, owner);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", connectionId); entry.put("owner", owner); entry.put("provider", "openai"); entry.put("subject", subject);
                entry.put("client", issued); entry.put("label", identity.getOrDefault("email", "ChatGPT account"));
                if (attempt.connection() != null) {
                    var old = connection(data, owner, connectionId);
                    if (old.containsKey("salt")) entry.put("salt", old.get("salt"));
                }
                if (attempt.connection() != null && !attempt.planUse()) {
                    // Fresh identity proof does not replace or decrypt an existing connection grant.
                    // In particular a protected returning account can sign in before owner unlock.
                    var old = connection(data, owner, connectionId);
                    if (old.containsKey("tokens")) entry.put("tokens", old.get("tokens"));
                    if (old.containsKey("lockedTokens")) entry.put("lockedTokens", old.get("lockedTokens"));
                } else saveTokens(entry, normalizeTokens(tokens, null), attempt.browser());
                connections.put(connectionId, entry);
                return Map.of("owner", owner, "connection", connectionId);
            });
            // Rotate the first-party cookie after authenticating. No provider credential crosses this boundary.
            cancelAuthority(browser);
            sessions.remove(browser); unlocked.remove(browser);
            String next = ProtectedConnectionStore.randomId();
            sessions.put(next, new BrowserSession(text(linked, "owner"), text(linked, "connection"), clock.instant().plus(Duration.ofHours(8)), attempt.perimeter()));
            setCookie(exchange, next, 28_800);
            String registration = ProtectedConnectionStore.randomId();
            store.transaction(data -> {
                var hints = map(data, "registrations");
                String previous = cookie(exchange.getRequestHeaders(), REGISTRATION_COOKIE);
                if (previous != null) hints.remove(digest(previous));
                hints.put(digest(registration), Map.of("connection", text(linked, "connection"),
                        "perimeter", attempt.perimeter(), "expires", clock.instant().plus(Duration.ofDays(30)).getEpochSecond()));
                return null;
            });
            setCookie(exchange, REGISTRATION_COOKIE, registration, 30 * 86400);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(303, -1); exchange.close();
        } catch (PersonalConnectionException failure) { reply(exchange, 400, Map.of("state", failure.code())); }
        catch (RuntimeException failure) { reply(exchange, 400, Map.of("state", "sign-in-unverified")); }
    }

    public void handle(HttpExchange exchange, AuthenticatedPrincipal perimeter, RequestContext context) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String action = path.substring("/v1/personal".length());
            if (exchange.getRequestMethod().equals("GET") && action.isEmpty()) { status(exchange, perimeter); return; }
            if (exchange.getRequestMethod().equals("GET") && action.equals("/models")) {
                var session = current(exchange.getRequestHeaders(), perimeterKey(perimeter));
                if (session == null || session.owner() == null) throw refused("login-required");
                String token = requireTokens(session.owner(), session.connection(), cookie(exchange.getRequestHeaders()), true);
                reply(exchange, 200, Map.of("models", modelCatalog(token))); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { reply(exchange, 405, Map.of("state", "method-not-allowed")); return; }
            requireSameOrigin(exchange);
            Map<String, Object> body = readBody(exchange);
            if (action.equals("/sign-in")) { begin(exchange, perimeter, body); return; }
            var session = current(exchange.getRequestHeaders(), perimeterKey(perimeter));
            if (session == null || session.owner() == null) throw refused("login-required");
            String browser = cookie(exchange.getRequestHeaders());
            switch (action) {
                case "/sign-out" -> {
                    sessions.remove(browser); unlocked.remove(browser); cancelAuthority(browser);
                    pending.entrySet().removeIf(entry -> entry.getValue().browser().equals(browser));
                    setCookie(exchange, "", 0); reply(exchange, 200, Map.of("state", "signed-out"));
                }
                case "/registration" -> {
                    String code = ProtectedConnectionStore.randomId();
                    store.transaction(data -> {
                        pruneExpiredGrants(data);
                        connection(data, session.owner(), session.connection());
                        map(data, "registrations").put(digest(code), Map.of("connection", session.connection(),
                                "perimeter", session.perimeter(), "expires", clock.instant().plusSeconds(600).getEpochSecond()));
                        return null;
                    });
                    reply(exchange, 200, Map.of("registration", code, "expiresIn", 600));
                }
                case "/select" -> {
                    String id = text(body, "connection");
                    store.transaction(data -> { connection(data, session.owner(), id); return null; });
                    sessions.put(browser, new BrowserSession(session.owner(), id, session.expires(), session.perimeter()));
                    unlocked.remove(browser); reply(exchange, 200, Map.of("state", "selected"));
                }
                case "/disconnect" -> disconnect(exchange, session, browser, text(body, "connection"));
                case "/protect" -> protect(exchange, session, browser, text(body, "passphrase").toCharArray());
                case "/unlock" -> unlock(exchange, session, browser, text(body, "passphrase").toCharArray(), text(body, "purpose"));
                case "/lock" -> { unlocked.remove(browser); cancelAuthority(browser); reply(exchange, 200, Map.of("state", "locked")); }
                case "/authorize" -> {
                    var unlock = unlocked.get(browser);
                    if (unlock != null && !unlock.purpose().equals("assistant")) throw refused("unlock-assistant-required");
                    requireTokens(session.owner(), session.connection(), browser, true);
                    sessions.put(browser, session); // authorization belongs to this already verified browser only
                    store.transaction(data -> { pruneExpiredGrants(data); map(data, "assistant").put(digest(browser), Map.of("owner", session.owner(), "connection", session.connection(), "expires", clock.instant().plusSeconds(900).getEpochSecond())); return null; });
                    reply(exchange, 200, Map.of("state", "authorized", "expiresIn", 900));
                }
                default -> reply(exchange, 404, Map.of("state", "not-found"));
            }
        } catch (PersonalConnectionException failure) { reply(exchange, 409, Map.of("state", failure.code())); }
        catch (RuntimeException failure) { reply(exchange, 400, Map.of("state", "invalid-request")); }
    }

    private void begin(HttpExchange exchange, AuthenticatedPrincipal perimeter, Map<String, Object> body) throws IOException {
        if (!configuration.networkReady()) throw refused("provider-egress-not-granted");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.equals(configuration.callback().getRawAuthority())) throw refused("unsupported-deployment");
        purgeExpired();
        if (pending.size() >= 256 || sessions.size() >= 4096) throw refused("capacity-unavailable");
        String browser = cookie(exchange.getRequestHeaders());
        var session = current(exchange.getRequestHeaders(), perimeterKey(perimeter));
        if (browser == null || session == null) {
            browser = ProtectedConnectionStore.randomId();
            session = new BrowserSession(null, null, clock.instant().plusSeconds(600), perimeterKey(perimeter));
            sessions.put(browser, session);
        }
        String selected = body.get("connection") instanceof String s && !s.isBlank() ? s : null;
        String owner = session.owner();
        Map<String, Object> saved;
        if (selected != null) {
            String explicit = selected;
            saved = store.transaction(data -> connection(data, owner, explicit));
        } else if (owner == null && !Boolean.TRUE.equals(body.get("newConnection"))) {
            boolean explicit = body.containsKey("registration");
            String registration = explicit ? text(body, "registration") : cookie(exchange.getRequestHeaders(), REGISTRATION_COOKIE);
            if (explicit && !registration.matches("[A-Za-z0-9_-]{43}")) throw refused("registration-unavailable");
            saved = registration == null ? Map.of() : store.transaction(data -> {
                var hints = map(data, "registrations");
                hints.entrySet().removeIf(entry -> number(cast(entry.getValue()), "expires") <= clock.instant().getEpochSecond());
                var hint = cast(hints.get(digest(registration)));
                if (!perimeterKey(perimeter).equals(hint.get("perimeter"))) return Map.of();
                return cast(map(data, "connections").get(text(hint, "connection")));
            });
            if (!saved.isEmpty()) selected = text(saved, "id");
            else if (explicit) throw refused("registration-unavailable");
        } else saved = Map.of();
        boolean planUse = Boolean.TRUE.equals(body.get("enablePlanUse"));
        if (saved.containsKey("salt") && planUse && !isUnlocked(browser, owner, selected)) throw refused("unlock-before-reconnect");
        String client = saved.isEmpty() ? configuration.loopback() ? "dynamic_agent_client" : configuration.clientId() : text(saved, "client");
        String state = ProtectedConnectionStore.randomId();
        String nonce = ProtectedConnectionStore.randomId();
        String pkce = ProtectedConnectionStore.randomId();
        pending.put(state, new Pending(browser, owner, selected, client, pkce, nonce, clock.instant().plusSeconds(600), perimeterKey(perimeter), planUse));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", client); parameters.put("response_type", "code");
        parameters.put("redirect_uri", configuration.callback().toString());
        parameters.put("scope", Boolean.TRUE.equals(body.get("enablePlanUse")) ? PLAN_SCOPES : IDENTITY_SCOPES);
        parameters.put("state", state); parameters.put("nonce", nonce); parameters.put("code_challenge_method", "S256");
        parameters.put("code_challenge", digest(pkce)); parameters.put("resource", RESOURCE);
        parameters.put("ext_agent_host_id", store.transaction(data -> text(data, "host")));
        if (client.equals("dynamic_agent_client")) parameters.put("agent_name_hint", "Ravenroot");
        if (Boolean.TRUE.equals(body.get("enablePlanUse"))) parameters.put("prompt", "consent");
        setCookie(exchange, browser, owner == null ? 600 : 28_800);
        reply(exchange, 200, Map.of("authorizationUrl", AUTH + "?" + form(parameters)));
    }

    private void status(HttpExchange exchange, AuthenticatedPrincipal perimeter) throws IOException {
        var session = current(exchange.getRequestHeaders(), perimeterKey(perimeter));
        if (session == null || session.owner() == null) { reply(exchange, 200, Map.of("state", configuration.networkReady() ? "signed-out" : "provider-egress-not-granted", "connections", List.of())); return; }
        String browser = cookie(exchange.getRequestHeaders());
        List<Object> visible = store.transaction(data -> {
            List<Object> result = new ArrayList<>();
            for (var entry : map(data, "connections").entrySet()) {
                var c = cast(entry.getValue()); if (!session.owner().equals(c.get("owner"))) continue;
                boolean locked = c.containsKey("salt") && !isUnlocked(browser, session.owner(), entry.getKey());
                String health = locked ? "unlock-required" : "connected";
                boolean usage = false;
                if (!locked) {
                    Map<String, Object> tokens = tokenMap(c, browser);
                    usage = scopes(tokens).contains("chatgpt.tokens.use.direct");
                    if (text(tokens, "access_token").isBlank()) health = "reconnect-required";
                    else if (!usage) health = "plan-permission-required";
                    else if (number(tokens, "expires") <= clock.instant().getEpochSecond()) health = "refresh-required";
                }
                result.add(Map.of("id", entry.getKey(), "provider", c.get("provider"), "label", c.get("label") + " · " + entry.getKey().substring(0, 8),
                        "selected", entry.getKey().equals(session.connection()), "health", health, "planUse", usage, "ownerPresence", c.containsKey("salt")));
            }
            return result;
        });
        reply(exchange, 200, Map.of("state", "signed-in", "identity", session.owner(), "connections", visible,
                "assistantAuthorized", assistantAuthorized(browser, session), "usageUrl", "https://chatgpt.com/settings/usage"));
    }

    /** Binds every logical requirement once to the executor before any graph effect is started. */
    public void authorizeExecution(Headers headers, RequestContext context, Set<String> requirements) {
        authorizeExecution(headers, context, requirements, Map.of("tenant", context.tenantId(), "issuer", context.issuer(), "subject", context.subject()));
    }
    /** Trusted adapter provenance is durable alongside the distinct verified executor. */
    public void authorizeExecution(Headers headers, RequestContext context, Set<String> requirements, Map<String, String> source) {
        if (requirements.isEmpty()) return;
        if (!ownsSession(headers, context)) throw refused("login-required");
        String browser = cookie(headers);
        BrowserSession session = sessions.get(browser);
        var unlock = unlocked.get(browser);
        if (unlock != null && !unlock.purpose().equals("execution")) throw refused("unlock-execution-required");
        if (requireTokens(session.owner(), session.connection(), browser, true).isBlank()) throw refused("reconnect-required");
        // One connection per provider alias for now; aliases never select someone else's account.
        if (!requirements.equals(Set.of("openai"))) throw refused("provider-unavailable");
        store.transaction(data -> {
            pruneExpiredGrants(data);
            var executions = map(data, "executions");
            if (executions.containsKey(context.requestId())) throw refused("execution-identity-reused");
            executions.put(context.requestId(), Map.of("owner", session.owner(), "tenant", context.tenantId(),
                    "connection", session.connection(), "browser", digest(browser), "source", Map.copyOf(source),
                    "expires", clock.instant().plusSeconds(900).getEpochSecond()));
            return null;
        });
        var key = unlocked.remove(browser);
        if (key != null) unlocked.put(context.requestId(), key);
    }

    /** Rechecked at root start and durable re-entry; a workload cannot substitute its own identity. */
    public void requireExecutionAuthority(SecurityContext executor, Set<String> providers) {
        if (!providers.equals(Set.of("openai")) || !ISSUER.equals(executor.issuer())
                || executor.principalType() != PrincipalType.USER || !executor.tenantId().equals("personal-" + executor.subject())) throw refused("login-required");
        Map<String, Object> grant = store.transaction(data -> {
            var value = cast(map(data, "executions").get(executor.requestId()));
            if (!executor.subject().equals(value.get("owner")) || !executor.tenantId().equals(value.get("tenant"))
                    || number(value, "expires") <= clock.instant().getEpochSecond()) throw refused("execution-authorization-expired");
            return value;
        });
        if (requireTokens(executor.subject(), text(grant, "connection"), executor.requestId(), true).isBlank()) throw refused("reconnect-required");
    }

    /** Resolves approved definitions under the independently authenticated source, never caller payload. */
    public String definitionTenant(SecurityContext executor) {
        if (!ISSUER.equals(executor.issuer())) return executor.tenantId();
        requireExecutionAuthority(executor, Set.of("openai"));
        return store.transaction(data -> {
            var grant = cast(map(data, "executions").get(executor.requestId()));
            String sourceTenant = text(cast(grant.get("source")), "tenant");
            if (sourceTenant.isBlank()) throw refused("source-definition-unavailable");
            return sourceTenant;
        });
    }

    @Override public Optional<SecretValue> resolve(String packageId, String tenant, String reference) {
        if (reference != null && reference.startsWith("personal:")) return Optional.empty();
        return system.resolve(packageId, tenant, reference);
    }
    @Override public Optional<SecretValue> resolveHttp(String packageId, SecurityContext executor, URI destination, String reference) {
        if (reference == null || !reference.startsWith("personal:")) return system.resolveHttp(packageId, executor, destination, reference);
        if (!"personal:openai".equals(reference) || !"ai.ravenroot.extensions.ai".equals(packageId)
                || !destination.equals(URI.create(RESOURCE + "/responses")) || !ISSUER.equals(executor.issuer())
                || executor.principalType() != PrincipalType.USER) return Optional.empty();
        try {
            Map<String, Object> grant = store.transaction(data -> {
                var value = cast(map(data, "executions").get(executor.requestId()));
                if (!executor.subject().equals(value.get("owner")) || !executor.tenantId().equals(value.get("tenant"))
                        || number(value, "expires") <= clock.instant().getEpochSecond()) throw refused("execution-authorization-expired");
                return value;
            });
            String token = requireTokens(executor.subject(), text(grant, "connection"), executor.requestId(), true);
            return token.isBlank() ? Optional.empty() : Optional.of(new SecretValue(token.toCharArray()));
        } catch (PersonalConnectionException failure) { return Optional.empty(); }
    }

    @Override public void requireHttpActive(String packageId, SecurityContext executor, URI destination, String reference) {
        if (reference == null || !reference.startsWith("personal:")) {
            system.requireHttpActive(packageId, executor, destination, reference); return;
        }
        if (!reference.equals("personal:openai") || !packageId.equals("ai.ravenroot.extensions.ai")
                || !destination.equals(URI.create(RESOURCE + "/responses")) || !ISSUER.equals(executor.issuer())) throw refused("connection-unavailable");
        store.transaction(data -> {
            var grant = cast(map(data, "executions").get(executor.requestId()));
            if (!executor.subject().equals(grant.get("owner")) || !executor.tenantId().equals(grant.get("tenant"))
                    || number(grant, "expires") <= clock.instant().getEpochSecond()) throw refused("execution-authorization-expired");
            var c = connection(data, executor.subject(), text(grant, "connection"));
            var tokens = tokenMap(c, executor.requestId());
            if (text(tokens, "access_token").isBlank() || !scopes(tokens).contains("chatgpt.tokens.use.direct")) throw refused("reconnect-required");
            return null;
        });
    }

    public String assistant(Headers headers, RequestContext context, String prompt, String requestedModel) {
        if (!ownsSession(headers, context)) throw refused("login-required");
        String browser = cookie(headers); BrowserSession session = sessions.get(browser);
        if (!assistantAuthorized(browser, session)) throw refused("assistant-authorization-required");
        String token = requireTokens(session.owner(), session.connection(), browser, true);
        String model = requestedModel == null || requestedModel.isBlank() ? configuration.assistantModel() : requestedModel;
        List<String> available = models(token);
        if (model.isBlank() && !available.isEmpty()) model = available.getFirst();
        if (!available.contains(model)) throw refused("model-unavailable");
        var response = request(URI.create(RESOURCE + "/responses"), "POST", Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"),
                ProtectedConnectionStore.json(Map.of("model", model, "input", List.of(Map.of("role", "user", "content", prompt)), "stream", true, "store", false)), 4 * 1024 * 1024,
                () -> {
                    if (!ownsSession(headers, context) || !assistantAuthorized(browser, session)) throw refused("assistant-authorization-expired");
                    store.transaction(data -> {
                        var tokens = tokenMap(connection(data, session.owner(), session.connection()), browser);
                        if (text(tokens, "access_token").isBlank() || !scopes(tokens).containsAll(Set.of("chatgpt.tokens.use.direct", "resource.invoke")))
                            throw refused("reconnect-required");
                        return null;
                    });
                });
        try { return ai.ravenroot.api.ai.OpenAiResponses.text(response); }
        catch (IllegalArgumentException failure) { throw refused(failure.getMessage()); }
    }
    public List<String> models(String token) {
        return modelCatalog(token).stream().map(m -> text(m, "slug")).toList();
    }
    private List<Map<String, Object>> modelCatalog(String token) {
        if (token.isBlank()) throw refused("reconnect-required");
        var doc = read(request(URI.create(RESOURCE + "/models"), "GET", Map.of("Authorization", "Bearer " + token), new byte[0], 1024 * 1024, () -> { }));
        List<Map<String, Object>> result = new ArrayList<>();
        if (doc.get("models") instanceof List<?> models) for (Object value : models) {
            var model = cast(value); if ("list".equals(model.get("visibility")) && model.get("slug") instanceof String slug)
                result.add(Map.of("slug", slug, "display_name", model.getOrDefault("display_name", slug)));
        }
        return List.copyOf(result);
    }

    private boolean assistantAuthorized(String browser, BrowserSession session) {
        return store.transaction(data -> { var grant = cast(map(data, "assistant").get(digest(browser)));
            return session.owner().equals(grant.get("owner")) && session.connection().equals(grant.get("connection"))
                    && number(grant, "expires") > clock.instant().getEpochSecond(); });
    }
    private void cancelAuthority(String browser) {
        store.transaction(data -> {
            map(data, "assistant").remove(digest(browser));
            map(data, "executions").entrySet().removeIf(e -> digest(browser).equals(cast(e.getValue()).get("browser")));
            return null;
        });
    }

    private record TokenResolution(String token, String refusal) { }
    private String requireTokens(String owner, String id, String authority, boolean planUse) {
        TokenResolution resolution = store.transaction(data -> {
            var c = connection(data, owner, id);
            var tokens = tokenMap(c, authority);
            if (text(tokens, "access_token").isBlank()) throw refused("reconnect-required");
            if (planUse && !scopes(tokens).containsAll(Set.of("chatgpt.tokens.use.direct", "resource.invoke"))) throw refused("plan-permission-required");
            if (number(tokens, "expires") < clock.instant().plusSeconds(60).getEpochSecond()) {
                String refresh = text(tokens, "refresh_token");
                if (refresh.isBlank()) throw refused("reconnect-required");
                try {
                    var replacement = tokenRequest(Map.of("grant_type", "refresh_token", "client_id", text(c, "client"), "refresh_token", refresh, "resource", RESOURCE));
                    if (replacement.get("id_token") instanceof String identityToken) {
                        Map<String, Object> identity;
                        try { identity = verifier.verify(identityToken, text(c, "client"), null); }
                        catch (RuntimeException invalidIdentity) {
                            saveTokens(c, Map.of("scope", "", "expires", 0), authority);
                            return new TokenResolution("", "reconnect-required");
                        }
                        if (!text(c, "subject").equals(identity.get("sub"))) {
                            saveTokens(c, Map.of("scope", "", "expires", 0), authority);
                            return new TokenResolution("", "reconnect-required");
                        }
                    }
                    tokens = normalizeTokens(replacement, tokens);
                    saveTokens(c, tokens, authority); // commit rotation even if consent was narrowed
                } catch (PersonalConnectionException failure) {
                    if (TERMINAL_REFRESH.contains(failure.code())) {
                        saveTokens(c, Map.of("scope", "", "expires", 0), authority);
                        return new TokenResolution("", "reconnect-required");
                    }
                    throw failure;
                }
            }
            if (planUse && !scopes(tokens).containsAll(Set.of("chatgpt.tokens.use.direct", "resource.invoke")))
                return new TokenResolution("", "plan-permission-required");
            String access = text(tokens, "access_token");
            return new TokenResolution(access, access.isBlank() ? "reconnect-required" : null);
        });
        if (resolution.refusal() != null) throw refused(resolution.refusal());
        return resolution.token();
    }

    private void disconnect(HttpExchange exchange, BrowserSession session, String browser, String id) throws IOException {
        boolean confirmed = store.transaction(data -> {
            var c = connection(data, session.owner(), id);
            boolean revoked = false;
            try {
                // Owner presence protects use, never the verified owner's ability to discard a grant.
                var tokens = !c.containsKey("salt") || isUnlocked(browser, session.owner(), id)
                        ? tokenMap(c, browser) : Map.<String, Object>of();
                if (text(tokens, "refresh_token").isBlank()) throw refused("revocation-unconfirmed");
                var discovery = read(request(URI.create("https://auth.openai.com/.well-known/openid-configuration"), "GET", Map.of(), new byte[0], 64 * 1024, () -> { }));
                URI endpoint = URI.create(text(discovery, "revocation_endpoint"));
                if (!endpoint.getScheme().equals("https") || !endpoint.getHost().equals("auth.openai.com")) throw refused("revocation-unconfirmed");
                request(endpoint, "POST", Map.of("Content-Type", "application/x-www-form-urlencoded"), form(Map.of("token", text(tokens, "refresh_token"), "token_type_hint", "refresh_token", "client_id", text(c, "client"))).getBytes(StandardCharsets.UTF_8), 65536, () -> { });
                revoked = true;
            } catch (RuntimeException failure) { /* local disconnection is immediate even when remote is down */ }
            c.remove("lockedTokens"); c.remove("salt");
            c.put("tokens", Map.of("scope", "", "expires", 0));
            map(data, "assistant").entrySet().removeIf(e -> id.equals(cast(e.getValue()).get("connection")));
            map(data, "executions").entrySet().removeIf(e -> id.equals(cast(e.getValue()).get("connection")));
            return revoked;
        });
        unlocked.entrySet().removeIf(e -> e.getValue().owner().equals(session.owner()) && e.getValue().connection().equals(id));
        reply(exchange, 200, Map.of("state", confirmed ? "disconnected" : "disconnected-revocation-unconfirmed"));
    }
    private void protect(HttpExchange exchange, BrowserSession session, String browser, char[] password) throws IOException {
        byte[] salt = ProtectedConnectionStore.random(16); byte[] key = ProtectedConnectionStore.passwordKey(password, salt);
        try {
            store.transaction(data -> { var c = connection(data, session.owner(), session.connection());
                if (c.containsKey("salt")) throw refused("already-protected");
                byte[] plain = ProtectedConnectionStore.json(c.remove("tokens"));
                try { c.put("lockedTokens", b64(ProtectedConnectionStore.encrypt(key, plain))); }
                finally { Arrays.fill(plain, (byte) 0); }
                c.put("salt", b64(salt)); return null; });
        } finally { Arrays.fill(key, (byte) 0); }
        cancelAuthority(browser); unlocked.remove(browser); reply(exchange, 200, Map.of("state", "locked"));
    }
    private void unlock(HttpExchange exchange, BrowserSession session, String browser, char[] password, String purpose) throws IOException {
        if (!Set.of("execution", "assistant").contains(purpose)) { Arrays.fill(password, '\0'); throw refused("unlock-purpose-required"); }
        var c = store.transaction(data -> connection(data, session.owner(), session.connection()));
        if (!c.containsKey("salt")) { Arrays.fill(password, '\0'); throw refused("not-protected"); }
        byte[] key = ProtectedConnectionStore.passwordKey(password, Base64.getDecoder().decode(text(c, "salt")));
        byte[] plain;
        try { plain = ProtectedConnectionStore.decrypt(key, Base64.getDecoder().decode(text(c, "lockedTokens"))); }
        catch (RuntimeException failure) { Arrays.fill(key, (byte) 0); throw failure; }
        Arrays.fill(plain, (byte) 0);
        unlocked.put(browser, new Unlocked(session.owner(), session.connection(), key, clock.instant().plusSeconds(900), purpose));
        reply(exchange, 200, Map.of("state", "unlocked", "expiresIn", 900));
    }
    private boolean isUnlocked(String authority, String owner, String connection) {
        var u = unlocked.get(authority);
        return u != null && u.owner().equals(owner) && u.connection().equals(connection) && u.expires().isAfter(clock.instant());
    }
    private Map<String, Object> tokenMap(Map<String, Object> c, String authority) {
        if (!c.containsKey("salt")) return cast(c.get("tokens"));
        var key = unlocked.get(authority);
        if (!isUnlocked(authority, text(c, "owner"), text(c, "id"))) throw refused("unlock-required");
        byte[] plaintext = ProtectedConnectionStore.decrypt(key.key(), Base64.getDecoder().decode(text(c, "lockedTokens")));
        try { return read(plaintext); } finally { Arrays.fill(plaintext, (byte) 0); }
    }
    private void saveTokens(Map<String, Object> connection, Map<String, Object> tokens, String authority) {
        if (!connection.containsKey("salt")) { connection.put("tokens", tokens); return; }
        byte[] plain = ProtectedConnectionStore.json(tokens);
        try { connection.put("lockedTokens", b64(ProtectedConnectionStore.encrypt(unlocked.get(authority).key(), plain))); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private Map<String, Object> normalizeTokens(Map<String, Object> incoming, Map<String, Object> old) {
        // A successful exchange must provide a fresh usable access token and expiry. Never extend
        // an old access token's lifetime when a malformed rotation omitted its replacement.
        if (text(incoming, "access_token").isBlank() || text(incoming, "access_token").length() > 32768
                || !"Bearer".equalsIgnoreCase(text(incoming, "token_type"))
                || !(incoming.get("expires_in") instanceof Number expiry)
                || expiry.longValue() < 1 || expiry.longValue() > 86_400
                || expiry.doubleValue() != expiry.longValue()) throw refused("provider-token-invalid");
        var tokens = new LinkedHashMap<String, Object>(); if (old != null) tokens.putAll(old);
        for (String field : List.of("access_token", "refresh_token", "id_token", "scope", "token_type")) if (incoming.get(field) instanceof String value) tokens.put(field, value);
        if (!"Bearer".equalsIgnoreCase(text(tokens, "token_type")) && incoming.containsKey("access_token")) throw refused("provider-token-invalid");
        tokens.put("expires", clock.instant().getEpochSecond() + Math.max(0, Math.min(86_400, number(incoming, "expires_in"))));
        return tokens;
    }
    private Map<String, Object> tokenRequest(Map<String, String> parameters) {
        var headers = new LinkedHashMap<String, String>(); headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (!configuration.clientSecret().isBlank()) headers.put("Authorization", "Basic " + b64((encode(configuration.clientId()) + ":" + encode(configuration.clientSecret())).getBytes(StandardCharsets.UTF_8)));
        return read(request(URI.create(TOKEN), "POST", headers, form(parameters).getBytes(StandardCharsets.UTF_8), 65536, () -> { }));
    }
    private byte[] request(URI endpoint, String method, Map<String, String> headers, byte[] body, int maximum, Runnable currency) {
        if (!configuration.allowedHosts().contains(endpoint.getHost()) || !endpoint.getScheme().equals("https") || endpoint.getUserInfo() != null || endpoint.getFragment() != null) throw refused("provider-egress-not-granted");
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90)); headers.forEach(builder::header);
            var response = http.send(builder.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var input = response.body(); var output = new ByteArrayOutputStream()) {
                var expired = new java.util.concurrent.atomic.AtomicBoolean();
                var timeout = BODY_DEADLINES.schedule(() -> {
                    expired.set(true); try { input.close(); } catch (IOException ignored) { }
                }, Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                try {
                    byte[] chunk = new byte[4096]; int size;
                    while ((size = input.read(chunk)) != -1) {
                        currency.run(); if (expired.get()) throw refused("provider-unavailable");
                        if (output.size() + size > maximum) throw refused("provider-response-too-large"); output.write(chunk, 0, size);
                    }
                    if (expired.get()) throw refused("provider-unavailable");
                    bytes = output.toByteArray();
                } finally { timeout.cancel(false); }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = ""; try { var error = read(bytes); code = error.get("error") instanceof String s ? s : text(cast(error.get("error")), "code"); } catch (RuntimeException ignored) { }
                if (TERMINAL_REFRESH.contains(code)) throw refused(code);
                throw refused(switch (response.statusCode()) { case 401 -> "reconnect-required"; case 403 -> "provider-permission-denied"; case 429 -> "provider-quota-exceeded"; default -> "provider-unavailable"; });
            }
            currency.run(); return bytes;
        } catch (PersonalConnectionException failure) { throw failure; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw refused("cancelled"); }
        catch (Exception failure) { throw refused("provider-unavailable"); }
    }
    private void requireSameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        URI callback = configuration.callback();
        String expected = callback.getScheme() + "://" + callback.getRawAuthority();
        if (origin != null && !origin.equals(expected)) throw refused("origin-refused");
        String fetch = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equals(fetch)) throw refused("origin-refused");
    }
    private void setCookie(HttpExchange exchange, String value, int seconds) { setCookie(exchange, COOKIE, value, seconds); }
    private void setCookie(HttpExchange exchange, String name, String value, int seconds) {
        exchange.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + seconds + (configuration.loopback() ? "" : "; Secure"));
    }
    private void pruneExpiredGrants(Map<String, Object> data) {
        long now = clock.instant().getEpochSecond();
        for (String family : List.of("assistant", "executions", "registrations"))
            if (data.containsKey(family)) map(data, family).entrySet()
                    .removeIf(entry -> number(cast(entry.getValue()), "expires") <= now);
    }
    private void purgeExpired() {
        store.transaction(data -> { pruneExpiredGrants(data); return null; });
        Instant now = clock.instant(); pending.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        sessions.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        unlocked.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
    }
    public static String cookie(Headers headers) { return cookie(headers, COOKIE); }
    private static String cookie(Headers headers, String name) {
        String cookie = headers.getFirst("Cookie"); if (cookie == null || cookie.length() > 8192) return null;
        String found = null; for (String pair : cookie.split(";")) { String[] parts = pair.strip().split("=", 2); if (parts.length == 2 && parts[0].equals(name)) { if (found != null) return null; found = parts[1]; } }
        return found != null && found.matches("[A-Za-z0-9_-]{43}") ? found : null;
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>(); if (raw == null || raw.length() > 32768) return values;
        for (String parameter : raw.split("&")) { String[] parts = parameter.split("=", 2); String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8); if (values.putIfAbsent(key, parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "") != null) throw refused("sign-in-unverified"); }
        return values;
    }
    private static String form(Map<String, String> parameters) { return parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String digest(String value) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException(); } }
    private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static String text(Map<String, Object> map, String name) { return map.get(name) instanceof String value ? value : ""; }
    private static long number(Map<String, Object> map, String name) { return map.get(name) instanceof Number value ? value.longValue() : 0; }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private static Map<String, Object> map(Map<String, Object> parent, String key) { if (!parent.containsKey(key)) parent.put(key, new LinkedHashMap<String, Object>()); return cast(parent.get(key)); }
    private static Map<String, Object> connection(Map<String, Object> data, String owner, String id) { var c = cast(map(data, "connections").get(Objects.toString(id, ""))); if (owner == null || !owner.equals(c.get("owner"))) throw refused("connection-unavailable"); return c; }
    private static Set<String> scopes(Map<String, Object> tokens) { return new HashSet<>(Arrays.asList(text(tokens, "scope").split(" +"))); }
    private static PersonalConnectionException refused(String code) { return new PersonalConnectionException(code); }
    private static Map<String, Object> read(byte[] bytes) { return cast(ai.ravenroot.api.payload.PayloadJson.read(bytes, ProtectedConnectionStore.LIMITS).toJava()); }
    private static Map<String, Object> readBody(HttpExchange exchange) throws IOException { try (var input = exchange.getRequestBody()) { byte[] bytes = input.readNBytes(65537); if (bytes.length > 65536) throw refused("invalid-request"); return bytes.length == 0 ? new LinkedHashMap<>() : read(bytes); } }
    private static void reply(HttpExchange exchange, int status, Object document) throws IOException { byte[] bytes = ProtectedConnectionStore.json(document); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); } }
}
