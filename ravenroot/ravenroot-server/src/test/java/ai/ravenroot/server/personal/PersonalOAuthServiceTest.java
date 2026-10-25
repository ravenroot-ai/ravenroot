package ai.ravenroot.server.personal;

import ai.ravenroot.api.security.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.server.security.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

public class PersonalOAuthServiceTest {
    @TempDir Path directory;
    final FakeProvider provider = new FakeProvider();
    final AuthenticatedPrincipal perimeter = new DisabledLoopbackAuthenticator().authenticate(new Headers());
    PersonalOAuthService service;
    PersonalOAuthConfiguration config;
    @BeforeEach void setup() {
        config = new PersonalOAuthConfiguration("loopback", URI.create("http://127.0.0.1:8080/auth/callback"), "", "",
                directory.resolve("vault"), directory.resolve("key"), Set.of("auth.openai.com", "api.openai.com"), "account-model");
        service = create();
    }
    PersonalOAuthService create() {
        return new PersonalOAuthService(config, (pkg, tenant, ref) -> Optional.of(new SecretValue("system-key".toCharArray())), provider,
                (token, client, nonce) -> Map.of("sub", token, "email", token + "@example.invalid"), Clock.systemUTC());
    }
    @Test void browserOAuthIsOneTimeBoundToBrowserAndSupportsDeniedConsent() throws Exception {
        var begin = call("/sign-in", null, Map.of());
        var url = URI.create((String) begin.document().get("authorizationUrl"));
        var parameters = query(url);
        assertEquals("dynamic_agent_client", parameters.get("client_id"));
        assertEquals("S256", parameters.get("code_challenge_method"));
        assertEquals("openid profile email offline_access", URLDecoder.decode(parameters.get("scope"), StandardCharsets.UTF_8));
        var plan = call("/sign-in", null, Map.of("enablePlanUse", true));
        var planParameters = query(URI.create((String) plan.document().get("authorizationUrl")));
        assertTrue(URLDecoder.decode(planParameters.get("scope"), StandardCharsets.UTF_8).contains("chatgpt.tokens.use.direct"));
        assertEquals("consent", planParameters.get("prompt"));
        assertNotNull(parameters.get("nonce"));
        String callback = "/auth/callback?code=A&client_id=oaiapp_A&state=" + parameters.get("state");
        var copied = exchange(callback, "wrong-cookie", null); service.callback(copied); assertEquals(400, copied.code);
        var replay = exchange(callback, begin.cookie(), null); service.callback(replay); assertEquals(400, replay.code);
        assertEquals(0, provider.tokens.get());
        var denied = call("/sign-in", null, Map.of());
        String state = query(URI.create((String) denied.document().get("authorizationUrl"))).get("state");
        var refusal = exchange("/auth/callback?error=access_denied&state=" + state, denied.cookie(), null);
        service.callback(refusal); assertEquals("consent-declined", refusal.document().get("state"));
        assertEquals(0, provider.tokens.get());
    }
    @Test void connectionsAreOwnerBoundAndEmailDoesNotMergeAccounts() throws Exception {
        String a = login("A", "oaiapp_A", null); String b = login("B", "oaiapp_B", null);
        var statusA = call("", a, null).document(); var statusB = call("", b, null).document();
        assertNotEquals(statusA.get("identity"), statusB.get("identity"));
        String connectionA = connectionId(statusA);
        assertEquals("connection-unavailable", call("/select", b, Map.of("connection", connectionA)).document().get("state"));
        assertEquals(1, ((List<?>) statusB.get("connections")).size());
        String added = login("C", "oaiapp_C", a);
        assertEquals(statusA.get("identity"), call("", added, null).document().get("identity"));
        assertEquals(2, ((List<?>) call("", added, null).document().get("connections")).size());
        assertFalse(call("", added, null).body.toString(StandardCharsets.UTF_8).contains("access-A"));
    }
    @Test void assistantNeedsAuthorizationForEachBrowserAndCannotFallbackToSystem() throws Exception {
        String a = login("A", "oaiapp_A", null);
        String second = login("A", "oaiapp_A", null);
        var context = personalContext(a, "request-A");
        assertThrows(PersonalConnectionException.class, () -> service.assistant(headers(a), context, "hello", "account-model"));
        call("/authorize", a, Map.of());
        assertEquals("completed-answer", service.assistant(headers(a), context, "hello", "account-model"));
        assertThrows(PersonalConnectionException.class, () -> service.assistant(headers(second), personalContext(second, "other"), "hello", "account-model"));
        assertEquals("access-A", provider.inferenceBearer);
        assertEquals(4096L, provider.lastInference.get("max_output_tokens"));
        call("/sign-out", a, Map.of());
        assertThrows(PersonalConnectionException.class, () -> service.assistant(headers(a), context, "hello", "account-model"));
    }
    @Test void identityOnlyConsentNeverAuthorizesInference() throws Exception {
        provider.scope = "openid profile email";
        String browser = login("A", "oaiapp_A", null);
        assertEquals("plan-permission-required", call("/authorize", browser, Map.of()).document().get("state"));
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(browser), personalContext(browser, "run"), Set.of("openai")));
        assertEquals(0, provider.inferences.get());
    }
    @Test void executionOwnerAndTenantArePinnedAcrossRestartAndRevocation() throws Exception {
        String a = login("A", "oaiapp_A", null), b = login("B", "oaiapp_B", null);
        RequestContext owner = personalContext(a, "server-generated-request");
        service.authorizeExecution(headers(a), owner, Set.of("openai"));
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(a), owner, Set.of("openai")), "request identity cannot be reused to replace a grant");
        var forgedTenant = new RequestContext("forged-tenant", owner.subject(), PrincipalType.USER, owner.issuer(), "personal-other", owner.roles(), owner.scopes());
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(a), forgedTenant, Set.of("openai")));
        SecurityContext execution = SecurityContext.of(owner);
        assertTrue(service.resolveHttp("ai.ravenroot.extensions.ai", execution, URI.create("https://api.openai.com/v1/responses"), "personal:openai").isPresent());
        assertTrue(service.resolve("ai.ravenroot.extensions.ai", owner.tenantId(), "personal:openai").isEmpty());
        assertTrue(service.resolveHttp("another.package", execution, URI.create("https://api.openai.com/v1/responses"), "personal:openai").isEmpty());
        assertTrue(service.resolveHttp("ai.ravenroot.extensions.ai", SecurityContext.of(personalContext(b, owner.requestId())), URI.create("https://api.openai.com/v1/responses"), "personal:openai").isEmpty());
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(b), owner, Set.of("openai")));
        var worker = create();
        worker.requireExecutionAuthority(execution, Set.of("openai"));
        assertEquals("anonymous-loopback", worker.principal(headers(a), perimeter).subject());
        call("/disconnect", a, Map.of("connection", connectionId(call("", a, null).document())));
        assertThrows(PersonalConnectionException.class, () -> worker.requireExecutionAuthority(execution, Set.of("openai")));
    }
    @Test void refreshRotationIsSerializedAcrossWorkers() throws Exception {
        provider.initialExpiry = 1;
        String a = login("A", "oaiapp_A", null);
        RequestContext owner = personalContext(a, "run");
        var other = create();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            service.authorizeExecution(headers(a), owner, Set.of("openai"));
            for (int i = 0; i < 8; i++) { var worker = i % 2 == 0 ? service : other; work.add(pool.submit(() -> worker.requireExecutionAuthority(SecurityContext.of(owner), Set.of("openai")))); }
            for (var result : work) result.get();
        }
        assertEquals(1, provider.refreshes.get());
    }
    @Test void ownerPresenceIsOneExecutionOnlyAndDoesNotSurviveWorkerRestart() throws Exception {
        String a = login("A", "oaiapp_A", null);
        assertEquals("locked", call("/protect", a, Map.of("passphrase", "a long testing passphrase")).document().get("state"));
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(a), personalContext(a, "run-1"), Set.of("openai")));
        assertEquals("unlock-required", call("/unlock", a, Map.of("passphrase", "wrong long passphrase", "purpose", "execution")).document().get("state"));
        assertEquals("unlocked", call("/unlock", a, Map.of("passphrase", "a long testing passphrase", "purpose", "execution")).document().get("state"));
        var execution = personalContext(a, "run-1"); service.authorizeExecution(headers(a), execution, Set.of("openai"));
        service.requireExecutionAuthority(SecurityContext.of(execution), Set.of("openai"));
        assertThrows(PersonalConnectionException.class, () -> service.authorizeExecution(headers(a), personalContext(a, "run-2"), Set.of("openai")));
        assertThrows(PersonalConnectionException.class, () -> create().requireExecutionAuthority(SecurityContext.of(execution), Set.of("openai")));
    }
    @Test void narrowedRefreshConsentAndWrongRefreshedOwnerStopUseWithoutLosingRotationState() throws Exception {
        provider.initialExpiry = 1;
        String a = login("A", "oaiapp_A", null);
        provider.refreshScope = "openid profile email";
        var context = personalContext(a, "narrowed");
        assertEquals("plan-permission-required", assertThrows(PersonalConnectionException.class,
                () -> service.authorizeExecution(headers(a), context, Set.of("openai"))).code());
        assertEquals(1, provider.refreshes.get());
        assertEquals("plan-permission-required", assertThrows(PersonalConnectionException.class,
                () -> service.authorizeExecution(headers(a), context, Set.of("openai"))).code());
        assertEquals(1, provider.refreshes.get(), "rotated token was committed even though consent narrowed");
        provider.refreshScope = null;
        String b = login("B", "oaiapp_B", null);
        provider.refreshedSubject = "wrong-owner";
        assertEquals("reconnect-required", assertThrows(PersonalConnectionException.class,
                () -> service.authorizeExecution(headers(b), personalContext(b, "wrong-owner"), Set.of("openai"))).code());
    }
    @Test void terminalRefreshCommitsCredentialRemovalAndDoesNotRetryTheInvalidatedToken() throws Exception {
        provider.initialExpiry = 1; String a = login("A", "oaiapp_A", null); provider.refreshError = "invalid_grant";
        for (int i = 0; i < 2; i++) {
            var context = personalContext(a, "terminal-" + i);
            assertEquals("reconnect-required", assertThrows(PersonalConnectionException.class,
                    () -> service.authorizeExecution(headers(a), context, Set.of("openai"))).code());
        }
        assertEquals(1, provider.refreshes.get());
        assertTrue(((List<?>) call("", a, null).document().get("connections")).getFirst().toString().contains("reconnect-required"));
    }
    @Test void executionGrantAndAssistantUnlockExpireWithoutChangingTheSystemCredential() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        service = new PersonalOAuthService(config, (pkg, tenant, ref) -> Optional.of(new SecretValue("system-key".toCharArray())), provider,
                (token, client, nonce) -> Map.of("sub", token, "email", token + "@example.invalid"), moving);
        String a = login("A", "oaiapp_A", null); var context = personalContext(a, "expiring-run");
        service.authorizeExecution(headers(a), context, Set.of("openai"));
        call("/authorize", a, Map.of()); call("/registration", a, Map.of());
        time.set(time.get().plusSeconds(901));
        assertEquals("execution-authorization-expired", assertThrows(PersonalConnectionException.class,
                () -> service.requireExecutionAuthority(SecurityContext.of(context), Set.of("openai"))).code());
        var restarted = new PersonalOAuthService(config, (pkg, tenant, ref) -> Optional.of(new SecretValue("system-key".toCharArray())), provider,
                (token, client, nonce) -> Map.of("sub", token), moving);
        var persisted = new ProtectedConnectionStore(config.directory(), config.keyFile());
        assertEquals(0, persisted.<Integer>transaction(state -> ((Map<?, ?>) state.get("executions")).size()).intValue());
        assertEquals(0, persisted.<Integer>transaction(state -> ((Map<?, ?>) state.get("assistant")).size()).intValue());
        assertEquals(1, persisted.<Integer>transaction(state -> ((Map<?, ?>) state.get("registrations")).size()).intValue(), "only the unexpired returning-cookie hint survives");
        assertEquals(1, persisted.<Integer>transaction(state -> ((Map<?, ?>) state.get("connections")).size()).intValue());
        var active = personalContext(a, "new-active-run"); service.authorizeExecution(headers(a), active, Set.of("openai"));
        restarted.requireExecutionAuthority(SecurityContext.of(active), Set.of("openai"));
        assertEquals(1, persisted.<Integer>transaction(state -> ((Map<?, ?>) state.get("executions")).size()).intValue());
        call("/protect", a, Map.of("passphrase", "a bounded assistant passphrase"));
        call("/unlock", a, Map.of("passphrase", "a bounded assistant passphrase", "purpose", "assistant"));
        time.set(time.get().plusSeconds(840)); assertEquals("authorized", call("/authorize", a, Map.of()).document().get("state"));
        time.set(time.get().plusSeconds(120));
        assertEquals("unlock-required", assertThrows(PersonalConnectionException.class,
                () -> service.assistant(headers(a), personalContext(a, "expired-unlock"), "hello", "account-model")).code());
        assertEquals("system-key", new String(service.resolve("package", "local", "system-key").orElseThrow().copy()));
    }
    @Test void lockedOwnerCanDisconnectWithoutDecryptingAndMustReconnectBeforeAnyFurtherUse() throws Exception {
        String a = login("A", "oaiapp_A", null); String id = connectionId(call("", a, null).document());
        call("/protect", a, Map.of("passphrase", "a disconnect testing passphrase"));
        call("/unlock", a, Map.of("passphrase", "a disconnect testing passphrase", "purpose", "execution"));
        var run = personalContext(a, "locked-inflight"); service.authorizeExecution(headers(a), run, Set.of("openai"));
        // The one-use unlock moved to the execution; this browser is locked again.
        var disconnected = call("/disconnect", a, Map.of("connection", id));
        assertEquals(200, disconnected.code); assertEquals("disconnected-revocation-unconfirmed", disconnected.document().get("state"));
        assertThrows(PersonalConnectionException.class, () -> service.requireExecutionAuthority(SecurityContext.of(run), Set.of("openai")));
        assertEquals("reconnect-required", assertThrows(PersonalConnectionException.class,
                () -> service.authorizeExecution(headers(a), personalContext(a, "after-disconnect"), Set.of("openai"))).code());
        var metadata = (Map<?, ?>) ((List<?>) call("", a, null).document().get("connections")).getFirst();
        assertEquals(false, metadata.get("ownerPresence")); assertEquals(0, provider.revocations.get());
    }
    @Test void returningRegistrationSurvivesRestartAndRoutesASecondBrowserWithoutGrantingAuthority() throws Exception {
        var first = call("/sign-in", null, Map.of());
        var initial = query(URI.create((String) first.document().get("authorizationUrl")));
        var callback = exchange("/auth/callback?code=A&client_id=oaiapp_A&state=" + initial.get("state"), first.cookie(), null);
        service.callback(callback); assertEquals(303, callback.code);
        String a = callback.cookie(); Object originalOwner = call("", a, null).document().get("identity");
        String remembered = callback.response.get("Set-Cookie").stream().filter(value -> value.startsWith("RavenrootRegistration="))
                .findFirst().orElseThrow().split(";", 2)[0];
        String code = (String) call("/registration", a, Map.of()).document().get("registration");
        call("/protect", a, Map.of("passphrase", "a returning account passphrase"));
        call("/sign-out", a, Map.of()); service = create();
        var rememberedBegin = call("/sign-in", remembered, Map.of());
        var routed = query(URI.create((String) rememberedBegin.document().get("authorizationUrl")));
        assertEquals("oaiapp_A", routed.get("client_id"));
        assertEquals(initial.get("ext_agent_host_id"), routed.get("ext_agent_host_id"));
        assertFalse(rememberedBegin.document().toString().contains("id_token"));
        assertEquals("anonymous-loopback", service.principal(headers(rememberedBegin.cookie()), perimeter).subject());
        assertEquals("login-required", call("/authorize", rememberedBegin.cookie(), Map.of()).document().get("state"));
        var wrongAccount = exchange("/auth/callback?code=B&client_id=oaiapp_A&state=" + routed.get("state"), rememberedBegin.cookie(), null);
        service.callback(wrongAccount); assertEquals(400, wrongAccount.code);
        var secondBegin = call("/sign-in", null, Map.of("registration", code));
        var secondParameters = query(URI.create((String) secondBegin.document().get("authorizationUrl")));
        assertEquals("oaiapp_A", secondParameters.get("client_id"));
        var verified = exchange("/auth/callback?code=A&client_id=oaiapp_A&state=" + secondParameters.get("state"), secondBegin.cookie(), null);
        service.callback(verified); assertEquals(303, verified.code); String second = verified.cookie();
        assertEquals(originalOwner, call("", second, null).document().get("identity"));
        assertThrows(PersonalConnectionException.class, () -> service.assistant(headers(second), personalContext(second, "browser2"), "hello", "account-model"));
        assertEquals("unlock-required", call("/authorize", second, Map.of()).document().get("state"));
        assertEquals("unlocked", call("/unlock", second, Map.of("passphrase", "a returning account passphrase", "purpose", "assistant")).document().get("state"));
        assertEquals("authorized", call("/authorize", second, Map.of()).document().get("state"));
        assertEquals("completed-answer", service.assistant(headers(second), personalContext(second, "browser2-authorized"), "hello", "account-model"));
        assertEquals("dynamic_agent_client", query(URI.create((String) call("/sign-in", null, Map.of()).document().get("authorizationUrl"))).get("client_id"));
        assertEquals("dynamic_agent_client", query(URI.create((String) call("/sign-in", remembered, Map.of("newConnection", true)).document().get("authorizationUrl"))).get("client_id"));
        assertEquals("registration-unavailable", call("/sign-in", null, Map.of("registration", "forged-code")).document().get("state"));
    }
    @Test void exactCallbackAuthorityPathAndDuplicateParametersAreRequired() throws Exception {
        for (String suffix : List.of("", "/extra", "%2fextra")) {
            var begin = call("/sign-in", null, Map.of());
            String state = query(URI.create((String) begin.document().get("authorizationUrl"))).get("state");
            var callback = exchange("/auth/callback" + suffix + "?code=A&client_id=oaiapp_A&state=" + state, begin.cookie(), null);
            if (suffix.isEmpty()) callback.request.set("Host", "127.0.0.1:9999");
            service.callback(callback); assertEquals(400, callback.code);
        }
        var begin = call("/sign-in", null, Map.of());
        String state = query(URI.create((String) begin.document().get("authorizationUrl"))).get("state");
        var callback = exchange("/auth/callback?code=A&client_id=oaiapp_A&state=" + state + "&state=" + state, begin.cookie(), null);
        service.callback(callback); assertEquals(400, callback.code); assertEquals(0, provider.tokens.get());
    }

    String login(String subject, String client, String browser) throws Exception {
        var begin = call("/sign-in", browser, Map.of());
        String state = query(URI.create((String) begin.document().get("authorizationUrl"))).get("state");
        var callback = exchange("/auth/callback?code=" + subject + "&client_id=" + client + "&state=" + state, begin.cookie() == null ? browser : begin.cookie(), null);
        service.callback(callback); assertEquals(303, callback.code, callback.body.toString(StandardCharsets.UTF_8)); return callback.cookie();
    }
    RequestContext personalContext(String browser, String request) { var p = service.principal(headers(browser), perimeter); return new RequestContext(request, p.subject(), PrincipalType.USER, p.issuer(), p.tenantId(), p.roles(), p.scopes()); }
    FakeExchange call(String action, String cookie, Object body) throws Exception {
        var exchange = exchange("/v1/personal" + action, cookie, body);
        service.handle(exchange, perimeter, new RequestContext("request", perimeter.subject(), PrincipalType.USER, perimeter.issuer(), perimeter.tenantId(), perimeter.roles(), perimeter.scopes())); return exchange;
    }
    FakeExchange exchange(String path, String cookie, Object body) { return new FakeExchange(path, cookie, body); }
    static Headers headers(String cookie) { var headers = new Headers(); if (cookie != null) headers.set("Cookie", cookie); return headers; }
    static String connectionId(Map<String, Object> doc) { return (String) ((Map<?, ?>) ((List<?>) doc.get("connections")).getFirst()).get("id"); }
    static Map<String, String> query(URI uri) { Map<String, String> result = new HashMap<>(); for (String part : uri.getQuery().split("&")) { String[] pair = part.split("=", 2); result.put(pair[0], pair[1]); } return result; }

    static final class FakeExchange extends HttpExchange {
        final URI uri; final Headers request = new Headers(), response = new Headers(); final byte[] input;
        final ByteArrayOutputStream body = new ByteArrayOutputStream(); int code;
        FakeExchange(String path, String cookie, Object data) { uri = URI.create(path); request.set("Host", "127.0.0.1:8080"); if (cookie != null) request.set("Cookie", cookie); input = data == null ? null : ProtectedConnectionStore.json(data); }
        @SuppressWarnings("unchecked") Map<String, Object> document() { return (Map<String, Object>) PayloadJson.read(body.toByteArray(), ProtectedConnectionStore.LIMITS).toJava(); }
        String cookie() { String value = response.getFirst("Set-Cookie"); return value == null ? null : value.split(";", 2)[0]; }
        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return input == null ? "GET" : "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(input == null ? new byte[0] : input); }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int code, long length) { this.code = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 9000); }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    public static final class FakeProvider extends HttpClient {
        public FakeProvider() { }
        public final java.util.List<String> inferenceBearers = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger tokens = new AtomicInteger(), refreshes = new AtomicInteger(), inferences = new AtomicInteger(), revocations = new AtomicInteger();
        String scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
        int initialExpiry = 3600; String inferenceBearer;
        String refreshScope; String refreshedSubject; String refreshError;
        public String responseText = "completed-answer";
        Map<String, Object> lastInference = Map.of();
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            byte[] result;
            String path = request.uri().getPath();
            if (path.endsWith("/oauth/token")) {
                tokens.incrementAndGet(); String body = requestBody(request); boolean refresh = body.contains("grant_type=refresh_token");
                if (refresh) refreshes.incrementAndGet();
                String subject = body.contains("code=B") ? "B" : body.contains("code=C") ? "C" : "A";
                result = ProtectedConnectionStore.json(Map.of("access_token", "access-" + subject, "refresh_token", "refresh-" + tokens.get(), "id_token", refresh && refreshedSubject != null ? refreshedSubject : subject,
                        "scope", refresh && refreshScope != null ? refreshScope : scope, "token_type", "Bearer", "expires_in", refresh ? 3600 : initialExpiry));
            } else if (path.equals("/v1/models")) result = ProtectedConnectionStore.json(Map.of("models", List.of(Map.of("slug", "account-model", "display_name", "Account model", "visibility", "list"))));
            else if (path.equals("/v1/responses")) {
                inferences.incrementAndGet(); inferenceBearer = request.headers().firstValue("Authorization").orElse("").replace("Bearer ", "");
                inferenceBearers.add(inferenceBearer);
                Map<String, Object> body = cast(PayloadJson.read(requestBody(request).getBytes(StandardCharsets.UTF_8), ProtectedConnectionStore.LIMITS).toJava());
                lastInference = body;
                assertEquals(false, body.get("store")); assertEquals(true, body.get("stream"));
                var output = Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", responseText)));
                var completed = Map.of("status", "completed", "output", List.of(output), "usage", Map.of("input_tokens", 2, "output_tokens", 2));
                result = ("data: " + new String(ProtectedConnectionStore.json(Map.of("type", "response.completed", "response", completed)), StandardCharsets.UTF_8) + "\n\n").getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/chat/completions")) {
                inferenceBearers.add(request.headers().firstValue("Authorization").orElse("").replace("Bearer ", ""));
                result = ProtectedConnectionStore.json(Map.of("choices", List.of(Map.of("message", Map.of("content", "system-answer"), "finish_reason", "stop")), "usage", Map.of("prompt_tokens", 2, "completion_tokens", 2)));
            } else if (path.endsWith("openid-configuration")) result = ProtectedConnectionStore.json(Map.of("revocation_endpoint", "https://auth.openai.com/revoke"));
            else { if (path.equals("/revoke")) revocations.incrementAndGet(); result = new byte[0]; }
            int status = path.endsWith("/oauth/token") && requestBody(request).contains("grant_type=refresh_token") && refreshError != null ? 400 : 200;
            if (status != 200) result = ProtectedConnectionStore.json(Map.of("error", refreshError));
            InputStream input = new ByteArrayInputStream(result);
            return new HttpResponse<>() {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                @Override public T body() { return (T) input; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        static String requestBody(HttpRequest request) {
            var bytes = new ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer buffer) { byte[] next = new byte[buffer.remaining()]; buffer.get(next); bytes.writeBytes(next); }
                @Override public void onError(Throwable failure) { throw new AssertionError(failure); }
                @Override public void onComplete() { }
            });
            return bytes.toString(StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked") static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new AssertionError(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { return CompletableFuture.completedFuture(send(r, h)); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r, h); }
    }
}
