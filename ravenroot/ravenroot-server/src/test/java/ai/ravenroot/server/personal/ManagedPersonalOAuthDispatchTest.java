package ai.ravenroot.server.personal;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import ai.ravenroot.server.plugin.DeploymentGlobalTenantCredentials;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public final class ManagedPersonalOAuthDispatchTest {
    private static final URI DESTINATION = URI.create("https://api.openai.com/v1/responses");
    private static final String REFERENCE = "personal:openai";
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    enum Scenario {
        LOGOUT, DISCONNECT, EXECUTION_EXPIRY, UNLOCK_EXPIRY, SCOPE_NARROWED,
        CURRENT_TOKEN, IN_FLIGHT_LOGOUT, REFERENCE_NOT_ALLOWED
    }

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void managedResponsesChecksAuthorityAtTransportHandoff(Scenario scenario) throws Exception {
        // The JVM loads its hosts file once. Keep the provider name deterministic and isolated
        // from other tests; the injected client ensures no provider socket is ever opened.
        var hosts = Files.writeString(directory.resolve("hosts"), "93.184.216.34 api.openai.com\n");
        var transcript = directory.resolve("probe.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.net.hosts.file=" + hosts, "-cp", System.getProperty("java.class.path"),
                ManagedPersonalOAuthDispatchTest.class.getName(), scenario.name(), directory.toString())
                .redirectErrorStream(true).redirectOutput(transcript.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "managed HTTP probe timed out");
            assertEquals(0, child.exitValue(), () -> transcript(transcript));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "managed HTTP probe did not terminate");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Scenario scenario = Scenario.valueOf(args[0]);
        var fixture = new PersonalOAuthServiceTest();
        var time = new PersonalOAuthServiceTest.MutableClock();
        fixture.directory = Path.of(args[1]);
        fixture.clock = time;
        fixture.setup();
        if (scenario == Scenario.CURRENT_TOKEN || scenario == Scenario.SCOPE_NARROWED) fixture.provider.initialExpiry = 70;
        String browser = fixture.login("A", "oaiapp_A", null);
        if (scenario == Scenario.UNLOCK_EXPIRY || scenario == Scenario.CURRENT_TOKEN) {
            String passphrase = "a managed execution passphrase";
            assertEquals(200, fixture.call("/protect", browser, Map.of("passphrase", passphrase)).code);
            assertEquals(200, fixture.call("/unlock", browser, Map.of("passphrase", passphrase, "purpose", "execution")).code);
            if (scenario == Scenario.UNLOCK_EXPIRY) time.now = time.now.plusSeconds(60);
        }
        var context = fixture.personalContext(browser, "managed-responses");
        fixture.service.authorizeExecution(PersonalOAuthServiceTest.headers(browser), context, Set.of("openai"));
        var owner = SecurityContext.of(context);
        if (scenario == Scenario.IN_FLIGHT_LOGOUT) {
            inFlightLogout(fixture, browser, owner);
            return;
        }
        if (scenario == Scenario.REFERENCE_NOT_ALLOWED) {
            assertRefused(fixture, dispatch(fixture, owner, false, () -> fixture.provider).completion().toCompletableFuture());
            return;
        }

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var call = dispatch(fixture, owner, true, () -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS), "lazy HTTP client was not released"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return fixture.provider;
        });
        var result = call.completion().toCompletableFuture();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "lazy HTTP client was not reached");
            assertEquals(0, fixture.provider.inferences.get());
            switch (scenario) {
                case LOGOUT -> logout(fixture, browser);
                case DISCONNECT -> {
                    String id = PersonalOAuthServiceTest.connectionId(fixture.call("", browser, null).document());
                    var disconnected = fixture.call("/disconnect", browser, Map.of("connection", id));
                    assertEquals(200, disconnected.code);
                    assertEquals("disconnected", disconnected.document().get("state"));
                }
                case EXECUTION_EXPIRY -> time.now = time.now.plusSeconds(901);
                case UNLOCK_EXPIRY -> time.now = time.now.plusSeconds(841);
                case SCOPE_NARROWED -> {
                    fixture.provider.refreshScope = "chatgpt.tokens.use.direct";
                    time.now = time.now.plusSeconds(20);
                    var refused = assertThrows(PersonalConnectionException.class,
                            () -> fixture.service.requireExecutionAuthority(owner, Set.of("openai")));
                    assertEquals("plan-permission-required", refused.code());
                }
                case CURRENT_TOKEN -> {
                    fixture.provider.refreshedAccess = "access-rotated";
                    time.now = time.now.plusSeconds(20);
                    fixture.service.requireExecutionAuthority(owner, Set.of("openai"));
                }
                case REFERENCE_NOT_ALLOWED, IN_FLIGHT_LOGOUT -> throw new AssertionError("handled separately");
            }
        } finally { release.countDown(); }

        if (scenario == Scenario.CURRENT_TOKEN) {
            var response = result.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("response.completed"));
            assertEquals(1, fixture.provider.inferences.get());
            assertEquals("access-rotated", fixture.provider.inferenceBearer);
        } else assertRefused(fixture, result);
    }

    private static void assertRefused(PersonalOAuthServiceTest fixture, CompletableFuture<OutboundHttpResponse> result)
            throws Exception {
        var failure = result.handle((response, error) -> error).get(10, TimeUnit.SECONDS);
        assertEquals(0, fixture.provider.inferences.get(), "a refused operation must never reach the transport");
        if (failure instanceof CompletionException wrapped) failure = wrapped.getCause();
        assertEquals(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE,
                assertInstanceOf(NodePackageServiceException.class, failure).reason());
    }

    private static void inFlightLogout(PersonalOAuthServiceTest fixture, String browser, SecurityContext owner) throws Exception {
        var admitted = new CountDownLatch(1);
        fixture.provider.afterInferenceAdmission = admitted::countDown;
        fixture.provider.responseHeaders = new CompletableFuture<>();
        var result = dispatch(fixture, owner, true, () -> fixture.provider).completion().toCompletableFuture();
        try {
            assertTrue(admitted.await(10, TimeUnit.SECONDS));
            logout(fixture, browser);
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.provider.inferenceResponse.isCancelled());
            assertEquals(1, fixture.provider.inferences.get(), "already admitted inference remains in flight until cancelled");
        } finally { fixture.provider.responseHeaders.complete(null); }
    }

    private static void logout(PersonalOAuthServiceTest fixture, String browser) throws Exception {
        var logout = fixture.call("/sign-out", browser, Map.of());
        assertEquals(200, logout.code);
        assertEquals("signed-out", logout.document().get("state"));
    }

    private static OutboundCall<OutboundHttpResponse> dispatch(
            PersonalOAuthServiceTest fixture, SecurityContext owner, boolean referenceAllowed, Supplier<HttpClient> client) throws Exception {
        String host = DESTINATION.getHost();
        var origin = new NodePackageEgressPolicy.Origin("https", host, 443);
        var policy = NodePackageEgressPolicy.builder().allowOrigin("https", host, 443).allowHttpMethod("POST")
                .bindCredential("personal", origin, "Authorization", "Bearer ").build();
        var resolver = DeploymentGlobalTenantCredentials.restrictedTo(fixture.service,
                Set.of(referenceAllowed ? REFERENCE : "system-reference"));
        var builder = ManagedNodePackageServices.builder("ai.ravenroot.extensions.ai", policy, resolver)
                .grant(NodePackageCapability.OUTBOUND_HTTP);
        var factory = builder.getClass().getDeclaredMethod("clientFactory", Supplier.class);
        factory.setAccessible(true);
        factory.invoke(builder, client);
        UUID id = UUID.randomUUID();
        var message = new NodeMessage(owner, id, id, id, id, Set.of(), "node", Map.of(), Map.of());
        var body = ProtectedConnectionStore.json(Map.of("model", "account-model", "input", List.of(), "stream", true, "store", false));
        return builder.build().outboundHttp().execute(message, new OutboundHttpRequest(DESTINATION, "POST", Map.of(),
                body, DEADLINE, new OutboundCredentialBinding("personal", REFERENCE)));
    }

    private static String transcript(Path path) {
        try { return Files.readString(path); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
