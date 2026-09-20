package ai.ravenroot.server.humantaskinteraction;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskCommentRequirement;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskPresentation;
import ai.ravenroot.api.persistence.HumanTaskPresentationKind;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskSettlement;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskReviewDefinition;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskInteractionBrokerTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final String RESPONSE_TYPE = "application/vnd.ravenroot.payload+json";
    private static final byte[] CAPABILITY_SECRET = "capability-secret-32-bytes-long!!"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROVIDER_SECRET = "provider-signing-secret-32-bytes!"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void customCapabilityIsGenerationBoundReplaySafeDurablyRevocableAndExpiring() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("custom.db"), clock)) {
            var service = new HumanTaskService(store, clock, HumanTaskPolicy.DEFAULTS);
            UUID taskId = request(store, service, HumanTaskPresentationKind.CUSTOM, "custom");
            var configuration = configuration(HumanTaskInteractionConfiguration.Kind.CUSTOM,
                    "custom", "https://custom.example/host", "https://custom.example", new byte[0]);
            var broker = new HumanTaskInteractionBroker(configuration, service, clock);
            RequestContext responder = responder();

            var launch = broker.issue(responder, taskId, 1, "https://workbench.example");
            assertEquals(List.of(HumanTaskConfirmationAction.RESOLVE,
                    HumanTaskConfirmationAction.DENY), launch.task().availableActions());
            assertFailure(HumanTaskInteractionBroker.Code.UNAVAILABLE,
                    () -> broker.issue(responder, taskId, 2, "https://workbench.example"));
            assertFailure(HumanTaskInteractionBroker.Code.UNAVAILABLE,
                    () -> broker.complete(launch.capability(), "https://wrong.example", new byte[0],
                            null, HumanTaskSettlement.resolve(response("approved"), "checked")));

            HumanTaskSettlement settlement = HumanTaskSettlement.resolve(response("approved"), "checked");
            assertEquals(HumanTaskResult.Code.RESOLVED,
                    service.settle(responder, taskId, 1, settlement).code(),
                    "custom completion uses current authenticated responder authority");
            assertEquals(HumanTaskResult.Code.ALREADY_APPLIED,
                    service.settle(responder, taskId, 1, settlement).code());

            assertFailure(HumanTaskInteractionBroker.Code.ORIGIN_REFUSED,
                    () -> broker.issue(responder, request(store, service,
                                    HumanTaskPresentationKind.CUSTOM, "custom"), 1,
                            "https://custom.example"));

            UUID revokedTask = request(store, service, HumanTaskPresentationKind.CUSTOM, "custom");
            var revoked = broker.issue(responder, revokedTask, 1, "https://workbench.example");
            broker.revoke(responder, revoked.capability(), revokedTask, 1);
            var restarted = new HumanTaskInteractionBroker(configuration, service, clock);
            assertFailure(HumanTaskInteractionBroker.Code.REVOKED,
                    () -> restarted.complete(revoked.capability(), "https://workbench.example",
                            new byte[0], null, HumanTaskSettlement.deny("recalled")));

            UUID expiringTask = request(store, service, HumanTaskPresentationKind.CUSTOM, "custom");
            var expiring = broker.issue(responder, expiringTask, 1, "https://workbench.example");
            var later = new HumanTaskInteractionBroker(configuration, service,
                    Clock.offset(clock, Duration.ofMinutes(6)));
            assertFailure(HumanTaskInteractionBroker.Code.EXPIRED,
                    () -> later.complete(expiring.capability(), "https://workbench.example",
                            new byte[0], null, HumanTaskSettlement.deny("late")));
        }
    }

    @Test
    void externalCapabilityRequiresExactConfiguredOriginAndBodySignature() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("external.db"), clock)) {
            var service = new HumanTaskService(store, clock, HumanTaskPolicy.DEFAULTS);
            UUID taskId = request(store, service, HumanTaskPresentationKind.EXTERNAL, "external");
            var configuration = configuration(HumanTaskInteractionConfiguration.Kind.EXTERNAL,
                    "external", "https://provider.example/task", "https://provider.example",
                    PROVIDER_SECRET);
            var broker = new HumanTaskInteractionBroker(configuration, service, clock);
            assertFailure(HumanTaskInteractionBroker.Code.ORIGIN_REFUSED,
                    () -> broker.issue(responder(), taskId, 1, "https://provider.example"));
            var launch = broker.issue(responder(), taskId, 1, "https://workbench.example");
            byte[] exactBody = "{\"providerDecision\":\"deny\"}".getBytes(StandardCharsets.UTF_8);

            assertFailure(HumanTaskInteractionBroker.Code.ORIGIN_REFUSED,
                    () -> broker.complete(launch.capability(), "https://workbench.example", exactBody,
                            signature(PROVIDER_SECRET, exactBody), HumanTaskSettlement.deny("provider")));
            assertFailure(HumanTaskInteractionBroker.Code.SIGNATURE_REFUSED,
                    () -> broker.complete(launch.capability(), "https://provider.example", exactBody,
                            "sha256=" + "0".repeat(64), HumanTaskSettlement.deny("provider")));
            assertEquals(HumanTaskResult.Code.DENIED,
                    broker.complete(launch.capability(), "https://provider.example", exactBody,
                            signature(PROVIDER_SECRET, exactBody),
                            HumanTaskSettlement.deny("provider")).code());
            assertEquals(HumanTaskResult.Code.ALREADY_APPLIED,
                    broker.complete(launch.capability(), "https://provider.example", exactBody,
                            signature(PROVIDER_SECRET, exactBody),
                            HumanTaskSettlement.deny("provider")).code());
        }
    }

    private static HumanTaskInteractionConfiguration configuration(
            HumanTaskInteractionConfiguration.Kind kind, String id, String launch, String origin,
            byte[] completionSecret) {
        var key = new HumanTaskInteractionConfiguration.ProfileKey(id, 1);
        var profile = new HumanTaskInteractionConfiguration.Profile(key, kind, URI.create(launch),
                URI.create(origin), completionSecret);
        return new HumanTaskInteractionConfiguration(Duration.ofMinutes(5), 64 * 1024,
                CAPABILITY_SECRET, Map.of(key, profile));
    }

    private static UUID request(ExecutionStore store, HumanTaskService service,
                                HumanTaskPresentationKind kind, String profileId) {
        var key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "review", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        var traversal = new Traversal(traversalId, "review", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join().revision();
        var requester = SecurityContext.of(new RequestContext("requester-request", "requester",
                PrincipalType.USER, "urn:ravenroot:test", key.tenantId(), Set.of(), Set.of()));
        var message = new NodeMessage(requester, key.processInstanceId(), traversalId, invocationId,
                attemptId, "review", Map.of(), Map.of());
        var confirmation = new HumanTaskConfirmationPresentation(1, "Review the decision",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY),
                "Approve", "Reject", "");
        var responseSchema = new HumanTaskResponseSchema(RESPONSE_TYPE, "test.decision", "1",
                PayloadKind.MAP, 4_096);
        var definition = new HumanTaskDefinition(new HumanTaskMetadata("Review", "Safe facts"),
                responseSchema, HandlerAuthorization.none(), Optional.empty(), Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                HumanTaskPolicy.DEFAULTS.executionLimits(responseSchema.maxBytes()), confirmation,
                HumanTaskReviewDefinition.none(), HumanTaskPresentation.registered(kind, profileId, 1));
        HumanTaskResult result;
        try (var recorder = ExecutionRecorder.open(store, key, "interaction-test",
                Duration.ofSeconds(30), revision); var ignored = service.bindLive(key, recorder)) {
            result = service.suspend(message, definition);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        assertEquals(HumanTaskResult.Code.CREATED, result.code());
        return result.task().request().taskId();
    }

    private static RequestContext responder() {
        return new RequestContext("request", "responder", PrincipalType.USER,
                "urn:ravenroot:test", "tenant-a", Set.of(), Set.of());
    }

    private static OpaquePayload response(String decision) {
        return OpaquePayload.of(PayloadEnvelope.of("test.decision", "1",
                PayloadValue.map(Map.of("decision", PayloadValue.of(decision)))).toJson()
                .getBytes(StandardCharsets.UTF_8), RESPONSE_TYPE);
    }

    private static String signature(byte[] secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static void assertFailure(HumanTaskInteractionBroker.Code code,
                                      org.junit.jupiter.api.function.Executable executable) {
        assertEquals(code, assertThrows(HumanTaskInteractionBroker.CapabilityFailure.class,
                executable).code());
    }
}
