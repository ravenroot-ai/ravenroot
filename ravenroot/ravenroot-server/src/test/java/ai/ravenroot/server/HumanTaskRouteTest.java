package ai.ravenroot.server;

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
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskCommentRequirement;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanTaskRouteTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTENT_TYPE = "application/vnd.acme.release-decision+json";

    @TempDir
    Path directory;

    @Test
    void tenantInboxAndGenerationFencedResolutionExposeNoResponseContent() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("human-task-route.db"), clock);
             var engine = new PekkoExecutionEngine("human-task-route-test")) {
            Fixture fixture = request(store, clock);
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    new TenantApproverAuthenticator())) {
                var sweeps = new java.util.concurrent.atomic.AtomicInteger();
                server.installHumanTasks(fixture.service(), ignored -> sweeps.incrementAndGet());
                server.start();

                HttpResponse<String> otherInbox = get(server, "other");
                assertEquals(200, otherInbox.statusCode(), otherInbox.body());
                assertTrue(otherInbox.body().contains("\"items\":[]"), otherInbox.body());

                HttpResponse<String> inbox = get(server, "tenant-a");
                assertEquals(200, inbox.statusCode(), inbox.body());
                assertTrue(inbox.body().contains(fixture.taskId().toString()), inbox.body());
                assertTrue(inbox.body().contains("Approve release"), inbox.body());
                assertTrue(inbox.body().contains("\"responseContentType\":\"" + CONTENT_TYPE + "\""),
                        inbox.body());
                assertFalse(inbox.body().contains("not copied"), inbox.body());
                assertTrue(get(server, "tenant-a", "?status=RESOLVED&includeTerminal=true")
                        .body().contains("\"items\":[]"));
                assertEquals(400, get(server, "tenant-a", "?status=not-a-status").statusCode());

                assertEquals(400, post(server, fixture, "tenant-a", null, CONTENT_TYPE).statusCode(),
                        "the generation fence is mandatory");
                assertEquals(404, post(server, fixture, "other", "1", CONTENT_TYPE).statusCode(),
                        "cross-tenant identifiers reveal no task existence");
                assertEquals(400, post(server, fixture, "tenant-a", "1", "application/json").statusCode(),
                        "the response media type is matched exactly");

                HttpResponse<String> resolved = post(server, fixture, "tenant-a", "1", CONTENT_TYPE);
                assertEquals(200, resolved.statusCode(), resolved.body());
                assertTrue(resolved.body().contains("\"outcome\":\"resolved\""), resolved.body());
                assertTrue(resolved.body().contains("\"generation\":2"), resolved.body());
                assertFalse(resolved.body().contains("approved"), resolved.body());
                assertFalse(resolved.body().contains("response"), resolved.body());
                assertEquals(1, sweeps.get());
                assertTrue(get(server, "tenant-a", "?status=resolved&includeTerminal=true")
                        .body().contains(fixture.taskId().toString()));

                HttpResponse<String> retry = post(server, fixture, "tenant-a", "1", CONTENT_TYPE);
                assertEquals(200, retry.statusCode(), retry.body());
                assertTrue(retry.body().contains("already_applied"), retry.body());
                assertEquals(2, sweeps.get(), "an idempotent retry may wake the same durable continuation");
            }
        }
    }

    @Test
    void aResponseAboveTheGenericStoreLimitSettlesAndReopensUnderPinnedStricterAndLooserPolicies() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Path database = directory.resolve("human-task-pinned-policy.db");
        HumanTaskPolicy policyA = policy(1_500_000, 1_700_000, 77, 5, 1_200_000);
        HumanTaskPolicy stricter = policy(500_000, 600_000, 10, 1, 100_000);
        HumanTaskPolicy looser = policy(3_000_000, 3_200_000, 120, 9, 2_000_000);
        UUID taskId;
        try (var store = new SqliteExecutionStore(database, clock, policyA)) {
            taskId = request(store, clock, policyA, 1_500_000).taskId();
        }
        RequestContext approver = new RequestContext("request", "approver", PrincipalType.USER,
                "urn:ravenroot:test", "tenant-a", Set.of(Role.APPROVER), Set.of());
        RequestContext unauthorized = new RequestContext("request", "viewer", PrincipalType.USER,
                "urn:ravenroot:test", "tenant-a", Set.of(), Set.of());
        byte[] largeEnvelope = PayloadEnvelope.of("release.decision", "1",
                PayloadValue.map(Map.of("decision", PayloadValue.of("x".repeat(1_100_000)))))
                .toJson().getBytes(StandardCharsets.UTF_8);
        assertTrue(largeEnvelope.length > 1_048_576, "fixture must cross the generic store ceiling");

        try (var reopened = new SqliteExecutionStore(database, clock, stricter)) {
            var service = new HumanTaskService(reopened, clock, stricter);
            var oldTask = reopened.loadHumanTask("tenant-a", taskId).toCompletableFuture().join()
                    .orElseThrow();
            assertEquals(1_700_000, service.authorizedResponseBodyLimit(approver, taskId).orElseThrow());
            assertTrue(service.authorizedResponseBodyLimit(unauthorized, taskId).isEmpty());
            assertTrue(service.authorizedResponseBodyLimit(new RequestContext("request", "approver",
                    PrincipalType.USER, "urn:ravenroot:test", "other", Set.of(Role.APPROVER),
                    Set.of()), taskId).isEmpty());
            assertEquals(77, oldTask.request().executionLimits().responsePayload().maxDepth());
            assertEquals(5, oldTask.request().executionLimits().writeAttempts());
            assertEquals(1_500_000, oldTask.request().responseSchema().maxBytes());

            assertEquals(HumanTaskResult.Code.RESOLVED,
                    service.resolve(approver, taskId, 1,
                            OpaquePayload.of(largeEnvelope, CONTENT_TYPE)).code());
            DurableHandler stored = reopened.loadHandler(oldTask.key(), taskId)
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(largeEnvelope.length, stored.outcomePayload().size());

            Fixture newTask = request(reopened, clock, stricter, 400_000);
            assertEquals(400_000, reopened.loadHumanTask("tenant-a", newTask.taskId())
                    .toCompletableFuture().join().orElseThrow().request()
                    .executionLimits().responsePayload().maxEncodedBytes());
        }

        try (var reopened = new SqliteExecutionStore(database, clock, looser)) {
            var task = reopened.loadHumanTask("tenant-a", taskId).toCompletableFuture().join()
                    .orElseThrow();
            assertEquals(1_500_000,
                    task.request().executionLimits().responsePayload().maxEncodedBytes(),
                    "a looser restart must not widen the old task");
            DurableHandler stored = reopened.loadHandler(task.key(), taskId)
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(largeEnvelope.length, stored.outcomePayload().size(),
                    "the complete response must survive durable handler reads");
            PendingWork.HandlerTrigger trigger = assertInstanceOf(PendingWork.HandlerTrigger.class,
                    reopened.claimPendingWork("tenant-a", "recovery", 20, Duration.ofSeconds(30))
                            .toCompletableFuture().join().stream()
                            .filter(item -> item.workItemId().equals(taskId)).findFirst().orElseThrow());
            assertEquals(largeEnvelope.length, trigger.payload().size());
            assertEquals("x".repeat(1_100_000),
                    ((Map<?, ?>) ai.ravenroot.api.payload.PayloadJson.readEnvelope(
                            trigger.payload().bytes(), task.request().executionLimits().responsePayload())
                            .toJava()).get("decision"),
                    "recovery must decode the response with the task's pinned parser budget");
        }
    }

    @Test
    void embeddedAttentionAndStructuredDecisionsAreSafeAuthorizedAndExactlyReplayable() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var store = new SqliteExecutionStore(directory.resolve("human-task-confirmation-route.db"), clock);
             var engine = new PekkoExecutionEngine("human-task-confirmation-route-test")) {
            Fixture resolve = requestEmbedded(store, clock, HumanTaskCommentRequirement.REQUIRED);
            Fixture deny = requestEmbedded(store, clock, HumanTaskCommentRequirement.OPTIONAL);
            Fixture cancel = requestEmbedded(store, clock, HumanTaskCommentRequirement.OPTIONAL);
            Fixture required = requestEmbedded(store, clock, HumanTaskCommentRequirement.REQUIRED);
            Fixture resolveOnly = requestEmbedded(store, clock, HumanTaskCommentRequirement.OPTIONAL,
                    HumanTaskPolicy.DEFAULTS, List.of(HumanTaskConfirmationAction.RESOLVE));
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    new TenantApproverAuthenticator())) {
                server.installHumanTasks(resolve.service(), ignored -> { }, HumanTaskPolicy.DEFAULTS);
                server.start();

                HttpResponse<String> configuration = rawGet(server, "tenant-a", "/v1/configuration");
                assertEquals(200, configuration.statusCode(), configuration.body());
                assertTrue(configuration.body().contains("\"confirmationPresentationVersions\":[1]"));
                assertTrue(configuration.body().contains("\"attentionPageSize\":20"));

                String context = "/v1/human-tasks/attention?graphVersion=graph-v1&processInstanceId="
                        + resolve.processInstanceId() + "&limit=10";
                HttpResponse<String> attention = rawGet(server, "tenant-a", context);
                assertEquals(200, attention.statusCode(), attention.body());
                assertTrue(attention.body().contains(resolve.taskId().toString()), attention.body());
                assertTrue(attention.body().contains("\"actions\":[\"CANCEL\",\"RESOLVE\",\"DENY\"]"),
                        attention.body());
                assertFalse(attention.body().contains("private-input"), attention.body());
                assertFalse(attention.body().contains("decisionComment"), attention.body());
                assertFalse(attention.body().contains("authorizedRoles"), attention.body());

                HttpResponse<String> exact = rawGet(server, "tenant-a",
                        "/v1/human-tasks/attention?taskId=" + resolve.taskId() + "&generation=1&limit=20");
                assertEquals(200, exact.statusCode(), exact.body());
                assertTrue(exact.body().contains(resolve.taskId().toString()), exact.body());
                assertTrue(exact.body().contains("\"nodeCounts\":[]"), exact.body());
                HttpResponse<String> withheld = rawGet(server, "other",
                        "/v1/human-tasks/attention?taskId=" + resolve.taskId() + "&generation=1&limit=20");
                assertEquals(200, withheld.statusCode(), withheld.body());
                assertTrue(withheld.body().contains("\"items\":[]"), withheld.body());

                String comment = "  Reviewed \\\"π\\\"\\nnext  ";
                HttpResponse<String> applied = confirmation(server, resolve, "tenant-a", "approver",
                        true, "resolve", 1, "{\"schemaVersion\":1,\"comment\":\"" + comment + "\"}");
                assertEquals(200, applied.statusCode(), applied.body());
                assertTrue(applied.body().contains("\"outcome\":\"APPLIED\""), applied.body());
                assertTrue(applied.body().contains("\"status\":\"RESOLVED\""), applied.body());
                assertTrue(applied.body().contains("\"availableActions\":[]"), applied.body());
                for (String forbidden : List.of("Reviewed", "approver", "responseSchema", "continuation",
                        "resumeTraversalId")) {
                    assertFalse(applied.body().contains(forbidden), applied.body());
                }
                var storedResolve = store.loadHumanTask("tenant-a", resolve.taskId())
                        .toCompletableFuture().join().orElseThrow();
                assertEquals("Reviewed \"π\"\nnext", storedResolve.decisionComment());
                assertEquals(HumanTaskService.confirmationResponse().size(),
                        store.loadHandler(storedResolve.key(), resolve.taskId()).toCompletableFuture().join()
                                .orElseThrow().outcomePayload().size());

                HttpResponse<String> replay = confirmation(server, resolve, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\"" + comment + "\"}");
                assertEquals(200, replay.statusCode(), replay.body());
                assertTrue(replay.body().contains("\"outcome\":\"ALREADY_APPLIED\""), replay.body());
                assertEquals(409, confirmation(server, resolve, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\"changed\"}").statusCode());
                assertEquals(409, confirmation(server, resolve, "tenant-a", "approver", true,
                        "deny", 1, "{\"schemaVersion\":1,\"comment\":\"Reviewed \\\"π\\\"\\nnext\"}")
                        .statusCode(), "a different action is not an exact replay");

                assertEquals(200, confirmation(server, deny, "tenant-a", "approver", true,
                        "deny", 1, "{\"schemaVersion\":1,\"comment\":\"No\"}").statusCode());
                assertEquals(200, confirmation(server, cancel, "tenant-a", "requester", false,
                        "cancel", 1, "{\"schemaVersion\":1,\"comment\":\"Later\"}").statusCode());
                assertEquals(400, confirmation(server, required, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\"\"}").statusCode());
                assertEquals(400, confirmation(server, required, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\""
                                + "x".repeat(HumanTaskPolicy.DEFAULTS.confirmation()
                                .maxCommentUtf8Bytes() + 1) + "\"}").statusCode());
                assertEquals(409, confirmation(server, required, "tenant-a", "approver", true,
                        "resolve", 2, "{\"schemaVersion\":1,\"comment\":\"yes\"}").statusCode());
                assertEquals(400, confirmation(server, required, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\"yes\",\"extra\":true}")
                        .statusCode());
                assertEquals(400, confirmation(server, resolveOnly, "tenant-a", "approver", true,
                        "deny", 1, "{\"schemaVersion\":1,\"comment\":\"No\"}").statusCode(),
                        "an authorized caller receives a rule refusal without task internals");
                assertEquals(404, confirmation(server, required, "tenant-a", "viewer", false,
                        "resolve", 1, "not-json").statusCode(),
                        "authorization must precede task-dependent body parsing");
                assertEquals(404, confirmation(server, new Fixture(resolve.service(), UUID.randomUUID(),
                                resolve.processInstanceId()), "tenant-a", "approver", true,
                        "resolve", 1, "not-json").statusCode());
            }
        }
    }

    @Test
    void anOldPinnedCommentLimitRemainsUsableAfterATighterPolicyRestart() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Path database = directory.resolve("human-task-confirmation-policy-drift.db");
        HumanTaskPolicy originalPolicy = confirmationPolicy(8_192);
        UUID taskId;
        UUID processInstanceId;
        try (var store = new SqliteExecutionStore(database, clock, originalPolicy)) {
            Fixture fixture = requestEmbedded(store, clock, HumanTaskCommentRequirement.REQUIRED,
                    originalPolicy);
            taskId = fixture.taskId();
            processInstanceId = fixture.processInstanceId();
        }

        HumanTaskPolicy tighterPolicy = confirmationPolicy(4_096, 1);
        try (var store = new SqliteExecutionStore(database, clock, tighterPolicy);
             var engine = new PekkoExecutionEngine("human-task-confirmation-policy-drift")) {
            var service = new HumanTaskService(store, clock, tighterPolicy);
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    new TenantApproverAuthenticator())) {
                server.installHumanTasks(service, ignored -> { }, tighterPolicy);
                server.start();
                HttpResponse<String> configuration = rawGet(server, "tenant-a", "/v1/configuration");
                assertTrue(configuration.body().contains("\"humanTasks\""), configuration.body());
                assertTrue(configuration.body().contains("\"commentMaxUtf8Bytes\":4096"),
                        configuration.body());
                assertTrue(service.supportsConfirmations());
                assertFalse(service.supportsConfirmationAdmission());
                HttpResponse<String> restored = rawGet(server, "tenant-a",
                        "/v1/human-tasks/attention?taskId=" + taskId + "&generation=1&limit=20");
                assertEquals(200, restored.statusCode(), restored.body());
                assertTrue(restored.body().contains("\"commentMaxUtf8Bytes\":8192"), restored.body());
                assertTrue(restored.body().contains("\"promptMaxUtf8Bytes\":8192"), restored.body());

                String oldPinValidComment = "x".repeat(6_000);
                var fixture = new Fixture(service, taskId, processInstanceId);
                HttpResponse<String> applied = confirmation(server, fixture, "tenant-a", "approver", true,
                        "resolve", 1, "{\"schemaVersion\":1,\"comment\":\""
                                + oldPinValidComment + "\"}");
                assertEquals(200, applied.statusCode(), applied.body());
                assertEquals(oldPinValidComment, store.loadHumanTask("tenant-a", taskId)
                        .toCompletableFuture().join().orElseThrow().decisionComment());
            }
        }
    }

    private static Fixture request(ExecutionStore store, Clock clock) {
        return request(store, clock, HumanTaskPolicy.DEFAULTS, 4096);
    }

    private static Fixture request(ExecutionStore store, Clock clock, HumanTaskPolicy policy,
                                   int responseMaxBytes) {
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
        var service = new HumanTaskService(store, clock, policy);
        var requester = SecurityContext.of(new RequestContext("requester-request", "requester",
                PrincipalType.USER, "urn:ravenroot:test", key.tenantId(), Set.of(), Set.of()));
        var message = new NodeMessage(requester, key.processInstanceId(), traversalId, invocationId,
                attemptId, "review", Map.of("private", "not copied"), Map.of());
        var definition = new HumanTaskDefinition(new HumanTaskMetadata("Approve release", "Bounded facts only."),
                new HumanTaskResponseSchema(CONTENT_TYPE, "release.decision", "1", PayloadKind.MAP,
                        responseMaxBytes),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), Optional.empty(), Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                policy.executionLimits(responseMaxBytes));
        HumanTaskResult result;
        try (var recorder = ExecutionRecorder.open(store, key, "route-fixture", Duration.ofSeconds(30),
                revision); var ignored = service.bindLive(key, recorder)) {
            result = service.suspend(message, definition);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        assertEquals(HumanTaskResult.Code.CREATED, result.code());
        return new Fixture(service, result.task().request().taskId(), key.processInstanceId());
    }

    private static Fixture requestEmbedded(ExecutionStore store, Clock clock,
                                           HumanTaskCommentRequirement commentRequirement) {
        return requestEmbedded(store, clock, commentRequirement, HumanTaskPolicy.DEFAULTS);
    }

    private static Fixture requestEmbedded(ExecutionStore store, Clock clock,
                                           HumanTaskCommentRequirement commentRequirement,
                                           HumanTaskPolicy policy) {
        return requestEmbedded(store, clock, commentRequirement, policy,
                List.of(HumanTaskConfirmationAction.CANCEL,
                        HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY));
    }

    private static Fixture requestEmbedded(ExecutionStore store, Clock clock,
                                           HumanTaskCommentRequirement commentRequirement,
                                           HumanTaskPolicy policy,
                                           List<HumanTaskConfirmationAction> actions) {
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
        var service = new HumanTaskService(store, clock, policy);
        var requester = SecurityContext.of(new RequestContext("requester-request", "requester",
                PrincipalType.USER, "urn:ravenroot:test", key.tenantId(), Set.of(), Set.of()));
        var message = new NodeMessage(requester, key.processInstanceId(), traversalId, invocationId,
                attemptId, "review", Map.of("secret", "private-input"), Map.of());
        var presentation = new HumanTaskConfirmationPresentation(1, "Ship this release?",
                commentRequirement, actions,
                "Ship", "Reject", "Later");
        var definition = new HumanTaskDefinition(new HumanTaskMetadata("Approve release", "Bounded facts only."),
                new HumanTaskResponseSchema(HumanTaskService.CONFIRMATION_CONTENT_TYPE,
                        HumanTaskService.CONFIRMATION_SCHEMA, HumanTaskService.CONFIRMATION_SCHEMA_VERSION,
                        PayloadKind.SCALAR, policy.defaultResponseBytes()),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), Optional.empty(), Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                policy.executionLimits(policy.defaultResponseBytes()),
                presentation);
        HumanTaskResult result;
        try (var recorder = ExecutionRecorder.open(store, key, "embedded-route-fixture",
                Duration.ofSeconds(30), revision); var ignored = service.bindLive(key, recorder)) {
            result = service.suspend(message, definition);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        assertEquals(HumanTaskResult.Code.CREATED, result.code());
        return new Fixture(service, result.task().request().taskId(), key.processInstanceId());
    }

    private static HttpResponse<String> get(RavenrootServer server, String tenant) throws Exception {
        return get(server, tenant, "");
    }

    private static HttpResponse<String> get(RavenrootServer server, String tenant, String query)
            throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/human-tasks" + query))
                        .header("X-Test-Tenant", tenant).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(RavenrootServer server, Fixture fixture, String tenant,
                                              String generation, String contentType) throws Exception {
        String query = generation == null ? "" : "?generation=" + generation;
        String body = PayloadEnvelope.of("release.decision", "1",
                PayloadValue.map(Map.of("decision", PayloadValue.of("approved")))).toJson();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.port() + "/v1/human-tasks/" + fixture.taskId() + "/resolve" + query))
                        .header("X-Test-Tenant", tenant)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> rawGet(RavenrootServer server, String tenant, String path)
            throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("X-Test-Tenant", tenant).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> confirmation(
            RavenrootServer server, Fixture fixture, String tenant, String subject, boolean approver,
            String action, long generation, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.port() + "/v1/human-tasks/" + fixture.taskId()
                        + "/confirmation/" + action + "?generation=" + generation))
                        .header("X-Test-Tenant", tenant).header("X-Test-Subject", subject)
                        .header("X-Test-Approver", Boolean.toString(approver))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Fixture(HumanTaskService service, UUID taskId, UUID processInstanceId) { }

    private static HumanTaskPolicy policy(int maxResponse, int decisionBody, int depth,
                                          int writeAttempts) {
        return policy(maxResponse, decisionBody, depth, writeAttempts,
                HumanTaskPolicy.DEFAULTS.responseMaxTextLength());
    }

    private static HumanTaskPolicy policy(int maxResponse, int decisionBody, int depth,
                                          int writeAttempts, int maxTextLength) {
        HumanTaskPolicy d = HumanTaskPolicy.DEFAULTS;
        return new HumanTaskPolicy(Math.min(d.defaultResponseBytes(), maxResponse), maxResponse,
                d.defaultEscalationSeconds(), d.maxEscalationSeconds(), d.defaultExpirySeconds(),
                d.maxExpirySeconds(), d.maxTitleUtf8Bytes(), d.maxDescriptionUtf8Bytes(),
                d.maxResponseSchemaUtf8Bytes(), d.maxAuthorizationTokens(),
                d.maxAuthorizationTokenUtf8Bytes(), decisionBody, d.inboxDefaultPageSize(),
                Math.max(250, d.inboxMaxPageSize()), depth, d.responseMaxCollectionSize(),
                d.responseMaxValueCount(), maxTextLength, d.responseMaxKeyLength(),
                writeAttempts);
    }

    private static HumanTaskPolicy confirmationPolicy(int textLimit) {
        return confirmationPolicy(textLimit, HumanTaskPolicy.DEFAULTS.defaultResponseBytes());
    }

    private static HumanTaskPolicy confirmationPolicy(int textLimit, int defaultResponseBytes) {
        HumanTaskPolicy d = HumanTaskPolicy.DEFAULTS;
        var confirmation = new HumanTaskPolicy.Confirmation(textLimit,
                d.confirmation().maxActionLabelUtf8Bytes(), textLimit,
                d.confirmation().pollAfterMillis(), d.confirmation().pollBackoffMaxMillis(),
                d.confirmation().attentionDefaultPageSize(), d.confirmation().attentionMaxPageSize());
        return new HumanTaskPolicy(defaultResponseBytes, d.maxResponseBytes(),
                d.defaultEscalationSeconds(), d.maxEscalationSeconds(), d.defaultExpirySeconds(),
                d.maxExpirySeconds(), d.maxTitleUtf8Bytes(), d.maxDescriptionUtf8Bytes(),
                d.maxResponseSchemaUtf8Bytes(), d.maxAuthorizationTokens(),
                d.maxAuthorizationTokenUtf8Bytes(), d.decisionBodyMaxBytes(),
                d.inboxDefaultPageSize(), d.inboxMaxPageSize(), d.responseMaxDepth(),
                d.responseMaxCollectionSize(), d.responseMaxValueCount(), d.responseMaxTextLength(),
                d.responseMaxKeyLength(), d.writeAttempts(), confirmation);
    }

    private static final class TenantApproverAuthenticator implements RequestAuthenticator {
        @Override public AuthenticatedPrincipal authenticate(Headers headers) {
            String subject = Optional.ofNullable(headers.getFirst("X-Test-Subject")).orElse("approver");
            boolean approver = !"false".equals(headers.getFirst("X-Test-Approver"));
            return new AuthenticatedPrincipal(subject, AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", headers.getFirst("X-Test-Tenant"),
                    approver ? Set.of(Role.APPROVER) : Set.of(),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
