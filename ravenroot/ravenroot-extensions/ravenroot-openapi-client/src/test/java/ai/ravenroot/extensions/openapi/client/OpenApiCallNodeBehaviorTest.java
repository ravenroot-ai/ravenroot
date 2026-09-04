package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiCallNodeBehaviorTest {
    @Test void validatesAndEncodesPathQueryHeaderAndProjectsValidatedResponse() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var profile = OpenApiClientTestSupport.profile(Set.of("getPet"), 2);
        var action = behavior(profile, "getPet", transport);
        Map<String, Object> input = Map.of("version", "openapi.call.v1", "path", Map.of("petId", "milo 1"),
                "query", Map.of("verbose", true), "headers", Map.of("X-Trace", "trace-1"));
        var result = action.handle(OpenApiClientTestSupport.message(input)).toCompletableFuture().join();
        assertEquals("https://api.example.test/pets/milo%201?verbose=true", transport.request.get().destination().toString());
        assertEquals("GET", transport.request.get().method());
        assertEquals(List.of("trace-1"), transport.request.get().headers().get("x-trace"));
        assertTrue(transport.request.get().credential().isEmpty());
        assertTrue(transport.request.get().limits().maximumOutputBytes() > profile.maxResponseBytes());
        assertEquals(Set.of("application/json"), transport.request.get().limits().acceptedMediaTypes());
        assertTrue(transport.request.get().representationPolicy().validates(200));
        assertFalse(transport.request.get().representationPolicy().validates(429));
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("openapi.call.result.v1", output.get("version"));
        assertEquals(200L, output.get("status"));
        assertEquals(Map.of("content-type", List.of("application/json")), output.get("headers"));
        assertEquals(Map.of("id", 1L, "name", "Milo"), output.get("body"));
    }

    @Test void authenticatedPostUsesOneManagedBindingAndCanonicalValidatedJson() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(201, Map.of(),
                "{\"name\":\"Milo\",\"id\":2}".getBytes(StandardCharsets.UTF_8)));
        var action = behavior(OpenApiClientTestSupport.profile(Set.of("createPet"), 2), "createPet", transport);
        var result = action.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")))).toCompletableFuture().join();
        assertEquals("https://api.example.test/pets", transport.request.get().destination().toString());
        assertEquals("POST", transport.request.get().method());
        assertEquals("{\"name\":\"Milo\"}", new String(transport.request.get().body(), StandardCharsets.UTF_8));
        assertEquals("bearer", transport.request.get().credential().orElseThrow().bindingId());
        assertEquals("pets-token", transport.request.get().credential().orElseThrow().reference());
        assertEquals(201L, ((Map<?, ?>) result.payload()).get("status"));
    }

    @Test void managedOperatorOutputCeilingCannotBeWidenedByOpenApiProjection() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200,
                Map.of("content-type", List.of("application/json")),
                "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8), 32));
        var action = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 2), "getPet", transport);

        assertFailure(action.handle(OpenApiClientTestSupport.message(input("milo"))),
                OpenApiClientException.Code.RESPONSE_TOO_LARGE);
        assertTrue(transport.request.get().limits().maximumOutputBytes() > 32,
                "the profile request was wider than the managed operator response authority");
    }

    @Test void rejectsUnknownParametersSlashAndSchemaFailuresBeforeTransport() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var action = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 2), "getPet", transport);
        assertFailure(action.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "path", Map.of("petId", "../admin")))), OpenApiClientException.Code.INVALID_INPUT);
        assertFailure(action.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "path", Map.of("petId", "milo"), "query", Map.of("attacker", true)))),
                OpenApiClientException.Code.INVALID_INPUT);
        assertEquals(0, transport.calls.get());

        var post = behavior(OpenApiClientTestSupport.profile(Set.of("createPet"), 2), "createPet", transport);
        assertFailure(post.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "", "extra", "value")))), OpenApiClientException.Code.INVALID_INPUT);
        assertEquals(0, transport.calls.get());
    }

    @Test void mapsRedirectInvalidResponseAndServiceFailuresWithoutRemoteOrSecretText() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var get = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 2), "getPet", transport);
        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(302,
                Map.of("location", List.of("https://attacker.test/secret")), new byte[0]));
        assertFailure(get.handle(OpenApiClientTestSupport.message(input("milo"))), OpenApiClientException.Code.REDIRECT_REFUSED);
        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(),
                "{\"id\":0,\"name\":\"bad\",\"secret\":\"remote-secret\"}".getBytes(StandardCharsets.UTF_8)));
        assertFailure(get.handle(OpenApiClientTestSupport.message(input("milo"))), OpenApiClientException.Code.RESPONSE_INVALID);

        transport.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TLS_REFUSED));
        Throwable tls = failure(get.handle(OpenApiClientTestSupport.message(input("milo"))));
        assertEquals(OpenApiClientException.Code.TLS_REFUSED, ((OpenApiClientException) tls).code());
        assertFalse(tls.getMessage().contains("remote-secret")); assertFalse(tls.getMessage().contains("pets-token"));
    }

    @Test void nonIdempotentTransportAndDeadlineAreAmbiguousButIdempotentFailureIsNotRetried() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        transport.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        var post = behavior(OpenApiClientTestSupport.profile(Set.of("createPet"), 2), "createPet", transport);
        assertFailure(post.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")))), OpenApiClientException.Code.AMBIGUOUS);
        assertEquals(1, transport.calls.get(), "no post-execute retry is allowed for non-idempotent operations");

        transport.calls.set(0);
        var get = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 2), "getPet", transport);
        assertFailure(get.handle(OpenApiClientTestSupport.message(input("milo"))),
                OpenApiClientException.Code.TRANSPORT_UNAVAILABLE);
        assertEquals(1, transport.calls.get(), "v1 conservatively retries only with an exposed pre-send signal");
    }

    @Test void mapsDnsDeadlineAndBodyLimitReasonsToStableCodes() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var get = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 2), "getPet", transport);
        for (var expected : Map.of(
                NodePackageServiceException.Reason.RESOLUTION_REFUSED, OpenApiClientException.Code.DESTINATION_REFUSED,
                NodePackageServiceException.Reason.REQUEST_TOO_LARGE, OpenApiClientException.Code.REQUEST_TOO_LARGE,
                NodePackageServiceException.Reason.RESPONSE_TOO_LARGE, OpenApiClientException.Code.RESPONSE_TOO_LARGE,
                NodePackageServiceException.Reason.DEADLINE_EXCEEDED, OpenApiClientException.Code.DEADLINE_EXCEEDED).entrySet()) {
            transport.response = CompletableFuture.failedFuture(new NodePackageServiceException(expected.getKey()));
            assertFailure(get.handle(OpenApiClientTestSupport.message(input("milo"))), expected.getValue());
        }
        var post = behavior(OpenApiClientTestSupport.profile(Set.of("createPet"), 2), "createPet", transport);
        transport.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.DEADLINE_EXCEEDED));
        assertFailure(post.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")))), OpenApiClientException.Code.AMBIGUOUS);
    }

    @Test void concurrencyPermitRemainsHeldUntilManagedTransportSettles() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var pending = new CompletableFuture<OutboundHttpResponse>();
        transport.response = pending;
        var action = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 1), "getPet", transport);
        var first = action.handle(OpenApiClientTestSupport.message(input("one")));
        assertFailure(action.handle(OpenApiClientTestSupport.message(input("two"))),
                OpenApiClientException.Code.CAPACITY_UNAVAILABLE);
        transport.response.complete(new OutboundHttpResponse(200, Map.of(),
                "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));
        first.toCompletableFuture().join();
        action.handle(OpenApiClientTestSupport.message(input("three"))).toCompletableFuture().join();
        assertEquals(2, transport.calls.get());
    }

    @Test void profileConcurrencyIsSharedAcrossOperationsForOneTenant() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var pending = new CompletableFuture<OutboundHttpResponse>();
        transport.response = pending;
        var profile = OpenApiClientTestSupport.profile(Set.of("getPet", "createPet"), 1);
        var behavior = new OpenApiCallNodeBehavior(name -> java.util.Optional.of(profile));
        var get = action(behavior, "getPet", transport, Map.of());
        var post = action(behavior, "createPet", transport, Map.of());

        var first = get.handle(OpenApiClientTestSupport.message(input("one")));
        assertFailure(post.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")))), OpenApiClientException.Code.CAPACITY_UNAVAILABLE);
        assertEquals(1, transport.calls.get());

        pending.complete(new OutboundHttpResponse(200, Map.of(),
                "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));
        first.toCompletableFuture().join();
        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(201, Map.of(),
                "{\"id\":2,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));
        post.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")))).toCompletableFuture().join();
        assertEquals(2, transport.calls.get());
    }

    @Test void graphActionConcurrencyTighteningDoesNotDependOnInitializationOrder() {
        assertActionCeilings(false);
        assertActionCeilings(true);
    }

    @Test void callerCancellationIsContractualBeforeAndAfterManagedHandoff() {
        assertCancellationContract("getPet", input("one"), OpenApiClientException.Code.DEADLINE_EXCEEDED);
        assertCancellationContract("createPet", Map.of("version", "openapi.call.v1",
                "body", Map.of("name", "Milo")), OpenApiClientException.Code.AMBIGUOUS);

        var transport = new OpenApiClientTestSupport.HttpDouble();
        var get = behavior(OpenApiClientTestSupport.profile(Set.of("getPet"), 1), "getPet", transport);
        var rejected = get.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "path", Map.of("petId", "../admin"))));
        assertFalse(rejected.toCompletableFuture().cancel(true));
        assertFailure(rejected, OpenApiClientException.Code.INVALID_INPUT);
        assertEquals(0, transport.calls.get(), "pre-handoff refusal must start no managed call");
    }

    @Test void idleProfileAndActionRegistriesReturnToBaselineAcrossLargeTenantProfileChurn() {
        var behavior = new OpenApiCallNodeBehavior(name ->
                java.util.Optional.of(OpenApiClientTestSupport.profile(name, Set.of("getPet"), 1)));

        for (int index = 0; index < 256; index++) {
            String profileName = "pets-" + index;
            var transport = new OpenApiClientTestSupport.HttpDouble();
            var action = (OpenApiCallNodeBehavior.OpenApiAction) behavior.create(new NodeConfiguration(
                    "call-" + index, OpenApiCallNodeBehavior.BEHAVIOR,
                    Map.of("apiProfile", profileName, "operationId", "getPet")), transport);

            action.handle(OpenApiClientTestSupport.message("tenant-" + index, input("pet-" + index)))
                    .toCompletableFuture().join();

            assertEquals(0, action.tenantAdmissionEntries());
            assertEquals(0, behavior.profileAdmissionEntries());
        }
    }

    @Test void everyPreAndPostHandoffTerminalPathReleasesBothRegistryReferences() {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var behavior = new OpenApiCallNodeBehavior(name -> java.util.Optional.of(
                OpenApiClientTestSupport.profile(Set.of("getPet"), 1)));
        var action = (OpenApiCallNodeBehavior.OpenApiAction) action(behavior, "getPet", transport, Map.of());

        action.handle(OpenApiClientTestSupport.message(input("success"))).toCompletableFuture().join();
        assertAdmissionEmpty(behavior, action);

        assertFailure(action.handle(OpenApiClientTestSupport.message(Map.of("version", "openapi.call.v1",
                "path", Map.of("petId", "../invalid")))), OpenApiClientException.Code.INVALID_INPUT);
        assertAdmissionEmpty(behavior, action);

        transport.synchronousFailure = new NodePackageServiceException(
                NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        assertFailure(action.handle(OpenApiClientTestSupport.message(input("sync-refusal"))),
                OpenApiClientException.Code.CAPACITY_UNAVAILABLE);
        transport.synchronousFailure = null;
        assertAdmissionEmpty(behavior, action);

        transport.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(),
                "{\"id\":0,\"name\":\"invalid\"}".getBytes(StandardCharsets.UTF_8)));
        assertFailure(action.handle(OpenApiClientTestSupport.message(input("validation"))),
                OpenApiClientException.Code.RESPONSE_INVALID);
        assertAdmissionEmpty(behavior, action);

        transport.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertFailure(action.handle(OpenApiClientTestSupport.message(input("exceptional"))),
                OpenApiClientException.Code.TRANSPORT_UNAVAILABLE);
        assertAdmissionEmpty(behavior, action);

        var pending = new CompletableFuture<OutboundHttpResponse>();
        transport.response = pending;
        transport.cancelCompletes = false;
        var cancelled = action.handle(OpenApiClientTestSupport.message(input("cancelled")));
        assertTrue(cancelled.toCompletableFuture().cancel(true));
        assertFailure(cancelled, OpenApiClientException.Code.DEADLINE_EXCEEDED);
        assertEquals(1, behavior.profileAdmissionEntries(), "direct cancel must retain admission until settlement");
        assertEquals(1, action.tenantAdmissionEntries());
        pending.completeExceptionally(new NodePackageServiceException(NodePackageServiceException.Reason.CANCELLED));
        assertAdmissionEmpty(behavior, action);
    }

    @Test void lastReleaseAndSameKeyReacquisitionCannotSplitOrWidenTheGate() throws Exception {
        CountDownLatch releasePaused = new CountDownLatch(1);
        CountDownLatch continueRelease = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        var registry = new OpenApiCallNodeBehavior.AdmissionRegistry<String>(() -> {
            if (!pauseOnce.compareAndSet(true, false)) return;
            releasePaused.countDown();
            boolean waiting = true;
            while (waiting) try {
                continueRelease.await();
                waiting = false;
            } catch (InterruptedException ignored) { }
        });
        var first = registry.tryAcquire("tenant\0profile", 1);
        assertNotNull(first);
        Thread closing = Thread.ofVirtual().start(first::close);
        assertTrue(releasePaused.await(1, TimeUnit.SECONDS));

        var replacement = registry.tryAcquire("tenant\0profile", 1);
        assertNotNull(replacement, "reacquisition must retain the gate whose permit was just released");
        assertNull(registry.tryAcquire("tenant\0profile", 1),
                "a second semaphore must never widen the configured maximum");
        var mismatch = assertThrows(OpenApiClientException.class,
                () -> registry.tryAcquire("tenant\0profile", 2), "active configuration mismatch must fail closed");
        assertEquals(OpenApiClientException.Code.CONFIGURATION, mismatch.code());

        continueRelease.countDown();
        closing.join();
        assertEquals(1, registry.size());
        replacement.close();
        assertEquals(0, registry.size());

        var reconfiguredAfterIdle = registry.tryAcquire("tenant\0profile", 2);
        assertNotNull(reconfiguredAfterIdle);
        reconfiguredAfterIdle.close();
        assertEquals(0, registry.size());
    }

    private static void assertAdmissionEmpty(OpenApiCallNodeBehavior behavior,
                                             OpenApiCallNodeBehavior.OpenApiAction action) {
        assertEquals(0, behavior.profileAdmissionEntries());
        assertEquals(0, action.tenantAdmissionEntries());
    }

    private static void assertActionCeilings(boolean relaxedFirst) {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var pending = new CompletableFuture<OutboundHttpResponse>();
        transport.response = pending;
        var profile = OpenApiClientTestSupport.profile(Set.of("getPet"), 4);
        var behavior = new OpenApiCallNodeBehavior(name -> java.util.Optional.of(profile));
        ai.ravenroot.api.node.NodeAction relaxed;
        ai.ravenroot.api.node.NodeAction tightened;
        if (relaxedFirst) {
            relaxed = action(behavior, "getPet", transport, Map.of("maxConcurrency", "2"));
            tightened = action(behavior, "getPet", transport, Map.of("maxConcurrency", "1"));
        } else {
            tightened = action(behavior, "getPet", transport, Map.of("maxConcurrency", "1"));
            relaxed = action(behavior, "getPet", transport, Map.of("maxConcurrency", "2"));
        }

        var tight = tightened.handle(OpenApiClientTestSupport.message(input("tight")));
        assertFailure(tightened.handle(OpenApiClientTestSupport.message(input("refused"))),
                OpenApiClientException.Code.CAPACITY_UNAVAILABLE);
        var firstRelaxed = relaxed.handle(OpenApiClientTestSupport.message(input("relaxed-one")));
        var secondRelaxed = relaxed.handle(OpenApiClientTestSupport.message(input("relaxed-two")));
        assertEquals(3, transport.calls.get());
        pending.complete(new OutboundHttpResponse(200, Map.of(),
                "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));
        tight.toCompletableFuture().join();
        firstRelaxed.toCompletableFuture().join();
        secondRelaxed.toCompletableFuture().join();
    }

    private static void assertCancellationContract(String operation, Map<String, Object> input,
                                                   OpenApiClientException.Code expected) {
        var transport = new OpenApiClientTestSupport.HttpDouble();
        var pending = new CompletableFuture<OutboundHttpResponse>();
        transport.response = pending;
        transport.cancelCompletes = false;
        var profile = OpenApiClientTestSupport.profile(Set.of(operation), 1);
        var action = behavior(profile, operation, transport);
        var stage = action.handle(OpenApiClientTestSupport.message(input));

        assertTrue(stage.toCompletableFuture().cancel(true));
        assertFalse(stage.toCompletableFuture().isCancelled(), "raw CancellationException must not escape");
        assertFailure(stage, expected);
        assertFalse(stage.toCompletableFuture().cancel(true), "cancellation is idempotently terminal");
        assertEquals(1, transport.cancellations.get());
        assertFailure(action.handle(OpenApiClientTestSupport.message(input)),
                OpenApiClientException.Code.CAPACITY_UNAVAILABLE);

        pending.completeExceptionally(new NodePackageServiceException(NodePackageServiceException.Reason.CANCELLED));
        transport.cancelCompletes = true;
        transport.response = CompletableFuture.completedFuture(operation.equals("createPet")
                ? new OutboundHttpResponse(201, Map.of(),
                        "{\"id\":2,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8))
                : new OutboundHttpResponse(200, Map.of(),
                        "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));
        action.handle(OpenApiClientTestSupport.message(input)).toCompletableFuture().join();
        assertEquals(2, transport.calls.get(), "cancellation settlement must release both admission permits");
    }

    private static ai.ravenroot.api.node.NodeAction behavior(OpenApiClientProfile profile, String operation,
                                                              OpenApiClientTestSupport.HttpDouble transport) {
        return action(new OpenApiCallNodeBehavior(name -> java.util.Optional.of(profile)), operation, transport,
                Map.of());
    }

    private static ai.ravenroot.api.node.NodeAction action(OpenApiCallNodeBehavior behavior, String operation,
                                                            OpenApiClientTestSupport.HttpDouble transport,
                                                            Map<String, String> overrides) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("apiProfile", "pets");
        properties.put("operationId", operation);
        properties.putAll(overrides);
        return behavior.create(new NodeConfiguration("call", OpenApiCallNodeBehavior.BEHAVIOR, properties), transport);
    }

    private static Map<String, Object> input(String id) {
        return Map.of("version", "openapi.call.v1", "path", Map.of("petId", id));
    }

    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage, OpenApiClientException.Code code) {
        Throwable failure = failure(stage);
        assertInstanceOf(OpenApiClientException.class, failure);
        assertEquals(code, ((OpenApiClientException) failure).code());
    }

    private static Throwable failure(java.util.concurrent.CompletionStage<?> stage) {
        CompletionException caught = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        return caught.getCause();
    }
}
