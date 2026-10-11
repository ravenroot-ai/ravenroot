package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOneEvaluateNodeBehaviorTest {

    @Test
    void jevRizzoAndKevFixturesValidateAsOneMixedSharedStateCall() throws Exception {
        for (String fixture : List.of("jev-response.json", "rizzo-response.json", "kev-response.json")) {
            var services = new SystemOneTestSupport.HttpDouble();
            services.response = CompletableFuture.completedFuture(SystemOneTestSupport.HttpDouble.response(fixture));
            SystemOneProfile profile = SystemOneTestSupport.profile("portable-model");
            NodeAction action = action(profile, SystemOneTestSupport.SPECIFICATION, services);
            Map<String, Object> input = Map.of("subject", "Payment failed", "body", "Please fix today");

            NodeResult result = action.handle(SystemOneTestSupport.message(input)).toCompletableFuture().get();

            assertEquals("continue", result.outcome());
            Map<String, Object> envelope = map(result.payload());
            assertEquals(SystemOneSpecification.RESULT_CONTRACT, envelope.get("contract"));
            assertEquals(input, envelope.get("input"));
            Map<String, Object> answers = map(envelope.get("answers"));
            assertEquals(Set.of("category", "action_required", "importance"), answers.keySet());
            assertEquals(0.81, map(answers.get("action_required")).get("noul"));
            assertEquals(1.35, map(answers.get("importance")).get("score"));
            assertEquals(Map.of("0", "low", "1", "medium", "2", "high"),
                    map(map(answers.get("importance")).get("legend")));

            assertEquals(1, services.calls.get());
            Map<String, Object> request = map(PayloadJson.read(services.request.get().body(),
                    PayloadLimits.DEFAULTS).toJava());
            assertEquals(input, request.get("state"));
            assertEquals("portable-model", request.get("model"));
            assertEquals(Set.of("category", "action_required", "importance"),
                    map(request.get("questions")).keySet());
        }
    }

    @Test
    void choiceAllowsAnArgmaxTieWithoutInventingATieBreaker() throws Exception {
        var services = new SystemOneTestSupport.HttpDouble();
        String tied = fixture().replace(
                "\"notify\": 0.72, \"archive\": 0.18, \"review\": 0.10",
                "\"notify\": 0.45, \"archive\": 0.45, \"review\": 0.10");
        services.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(),
                tied.getBytes(StandardCharsets.UTF_8)));

        NodeResult result = action(SystemOneTestSupport.profile("m"),
                SystemOneTestSupport.SPECIFICATION, services)
                .handle(SystemOneTestSupport.message(Map.of("body", "hello")))
                .toCompletableFuture().get();

        assertEquals("notify", map(map(map(result.payload()).get("answers")).get("category")).get("choice"));
    }

    @Test
    void profileCapabilityLimitRejectsRizzoChoiceBeforeNetworkWork() {
        var options = new LinkedHashMap<String, Object>();
        for (int index = 0; index < 27; index++) options.put("o" + index, null);
        String specification = json(Map.of("contract", SystemOneSpecification.CONTRACT,
                "id", "rizzo-cap/v1", "questions", Map.of("category", Map.of(
                        "type", "choice", "instructions", "Choose.", "criteria", options))));
        var services = new SystemOneTestSupport.HttpDouble();
        NodeAction action = action(SystemOneTestSupport.profile("rizzo", 26, 2), specification, services);

        assertEquals(SystemOneException.Code.CAPABILITY_UNSUPPORTED, failureOf(action).code());
        assertEquals(0, services.calls.get());
    }

    @Test
    void responseQuestionTypeMembershipDistributionAndUsageAreValidated() {
        for (String body : List.of(
                "{\"model\":\"m\",\"answers\":{}}",
                fixture().replace("\"choice\": \"notify\"", "\"choice\": \"unknown\""),
                fixture().replace("\"notify\": 0.72, \"archive\": 0.18, \"review\": 0.10",
                        "\"notify\": 0.72, \"archive\": 0.18, \"review\": 0.30"),
                fixture().replace("\"choice\": \"notify\"", "\"choice\": \"archive\""),
                fixture().replace(
                        "\"notify\": 0.72, \"archive\": 0.18, \"review\": 0.10",
                        "\"notify\": 0.44975, \"archive\": 0.45025, \"review\": 0.10"),
                fixture().replace("\"choice\": \"notify\",", ""),
                fixture().replace("\"noul\": 0.81", "\"noul_extra\": 0.81"),
                fixture().replace("\"score\": 1.35", "\"score\": 9.0"),
                fixture().replace("\"input_tokens\": 296", "\"input_tokens\": -1"))) {
            var services = new SystemOneTestSupport.HttpDouble();
            services.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(),
                    body.getBytes(StandardCharsets.UTF_8)));
            assertEquals(SystemOneException.Code.RESPONSE_MISMATCH,
                    failureOf(action(SystemOneTestSupport.profile("m"),
                            SystemOneTestSupport.SPECIFICATION, services)).code());
        }
    }

    @Test
    void absentUsageIsExplicitlyAbsentRatherThanFabricated() throws Exception {
        var services = new SystemOneTestSupport.HttpDouble();
        String withoutUsage = fixture().replace(",\n  \"usage\": {\"input_tokens\": 296, \"output_tokens\": 44}", "");
        services.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(),
                withoutUsage.getBytes(StandardCharsets.UTF_8)));

        NodeResult result = action(SystemOneTestSupport.profile("m"),
                SystemOneTestSupport.SPECIFICATION, services)
                .handle(SystemOneTestSupport.message(Map.of("body", "hello")))
                .toCompletableFuture().get();

        assertFalse(map(result.payload()).containsKey("usage"));
    }

    @Test
    void unknownProfileRefusesWithoutCallingTheManagedChannel() {
        var services = new SystemOneTestSupport.HttpDouble();
        NodeAction action = new SystemOneEvaluateNodeBehavior(name -> Optional.empty())
                .create(SystemOneTestSupport.configuration(SystemOneTestSupport.SPECIFICATION), services);

        assertEquals(SystemOneException.Code.PROFILE_UNKNOWN, failureOf(action).code());
        assertEquals(0, services.calls.get());
    }

    @Test
    void stateAndRequestBoundsAreCheckedBeforeNetworkWork() {
        var services = new SystemOneTestSupport.HttpDouble();
        SystemOneProfile base = SystemOneTestSupport.profile("m");
        SystemOneProfile narrow = new SystemOneProfile(base.name(), base.endpoint(), base.model(),
                base.credentialBinding(), base.timeoutMs(), base.maxRequestBytes(), base.maxResponseBytes(),
                base.maxConcurrency(), 32, base.maxQuestions(), base.maxInstructionBytes(),
                base.maxChoiceOptions(), base.maxScoreLevels(), base.supportedTypes(),
                base.probabilityTolerance(), null);

        assertEquals(SystemOneException.Code.STATE_INVALID,
                failureOf(action(narrow, SystemOneTestSupport.SPECIFICATION, services),
                        Map.of("body", "x".repeat(100))).code());
        assertEquals(0, services.calls.get());

        SystemOneProfile requestBound = new SystemOneProfile(base.name(), base.endpoint(), base.model(),
                base.credentialBinding(), base.timeoutMs(), 1_024, base.maxResponseBytes(),
                base.maxConcurrency(), 900, base.maxQuestions(), 512,
                base.maxChoiceOptions(), base.maxScoreLevels(), base.supportedTypes(),
                base.probabilityTolerance(), null);
        assertEquals(SystemOneException.Code.REQUEST_TOO_LARGE,
                failureOf(action(requestBound, SystemOneTestSupport.SPECIFICATION, services),
                        Map.of("body", "x".repeat(700))).code());
        assertEquals(0, services.calls.get());
    }

    @Test
    void managedCredentialAndEgressBoundariesRemainRuntimeOwned() throws Exception {
        var services = new SystemOneTestSupport.HttpDouble();
        SystemOneProfile base = SystemOneTestSupport.profile("m");
        var credential = new OutboundCredentialBinding("system-one", "hosted-key");
        SystemOneProfile hosted = new SystemOneProfile(base.name(), base.endpoint(), base.model(),
                Optional.of(credential), base.timeoutMs(), base.maxRequestBytes(), base.maxResponseBytes(),
                base.maxConcurrency(), base.maxStateBytes(), base.maxQuestions(), base.maxInstructionBytes(),
                base.maxChoiceOptions(), base.maxScoreLevels(), base.supportedTypes(),
                base.probabilityTolerance(), null);
        NodeResult result = action(hosted, SystemOneTestSupport.SPECIFICATION, services)
                .handle(SystemOneTestSupport.message(Map.of("body", "hello")))
                .toCompletableFuture().get();

        assertEquals(credential, services.request.get().credential().orElseThrow());
        assertFalse(services.request.get().headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("authorization")));
        assertFalse(result.toString().contains("hosted-key"));

        services.synchronousFailure = new NodePackageServiceException(
                NodePackageServiceException.Reason.DESTINATION_FORBIDDEN);
        assertEquals(SystemOneException.Code.DESTINATION_REFUSED,
                failureOf(action(hosted, SystemOneTestSupport.SPECIFICATION, services)).code());
    }

    @Test
    void nodeDeadlineOnlyTightensTheProfileDeadline() throws Exception {
        var services = new SystemOneTestSupport.HttpDouble();
        var behavior = new SystemOneEvaluateNodeBehavior(
                SystemOneTestSupport.resolving(SystemOneTestSupport.profile("m")));
        behavior.create(new ai.ravenroot.api.node.NodeConfiguration("evaluate",
                        SystemOneEvaluateNodeBehavior.BEHAVIOR, Map.of("profile", "local",
                        "specification", SystemOneTestSupport.SPECIFICATION, "timeoutMs", "600000")), services)
                .handle(SystemOneTestSupport.message(Map.of("body", "hello"))).toCompletableFuture().get();
        assertEquals(java.time.Duration.ofMillis(5_000), services.request.get().deadline());

        behavior.create(new ai.ravenroot.api.node.NodeConfiguration("evaluate",
                        SystemOneEvaluateNodeBehavior.BEHAVIOR, Map.of("profile", "local",
                        "specification", SystemOneTestSupport.SPECIFICATION, "timeoutMs", "250")), services)
                .handle(SystemOneTestSupport.message(Map.of("body", "hello"))).toCompletableFuture().get();
        assertEquals(java.time.Duration.ofMillis(250), services.request.get().deadline());

        NodeAction malformed = behavior.create(new ai.ravenroot.api.node.NodeConfiguration("evaluate",
                SystemOneEvaluateNodeBehavior.BEHAVIOR, Map.of("profile", "local",
                "specification", SystemOneTestSupport.SPECIFICATION, "timeoutMs", "not-a-number")), services);
        assertEquals(SystemOneException.Code.CONFIGURATION_INVALID, failureOf(malformed).code());
    }

    @Test
    void duplicateQuestionAndOptionIdsAreRejectedByTheStrictParser() {
        var services = new SystemOneTestSupport.HttpDouble();
        for (String specification : List.of(
                "{\"contract\":\"ravenroot.system-one/specification/1\",\"id\":\"x/v1\","
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"x\"},"
                        + "\"q\":{\"type\":\"noul\",\"instructions\":\"y\"}}}",
                "{\"contract\":\"ravenroot.system-one/specification/1\",\"id\":\"x/v1\","
                        + "\"questions\":{\"q\":{\"type\":\"choice\",\"instructions\":\"x\","
                        + "\"criteria\":{\"a\":null,\"a\":\"duplicate\"}}}}")) {
            assertEquals(SystemOneException.Code.SPECIFICATION_INVALID,
                    failureOf(action(SystemOneTestSupport.profile("m"), specification, services)).code());
        }
        assertEquals(0, services.calls.get());
    }

    @Test
    void cancellationAndCapacityFailuresReleaseAdmission() throws Exception {
        var services = new SystemOneTestSupport.HttpDouble();
        services.response = new CompletableFuture<>();
        var behavior = new SystemOneEvaluateNodeBehavior(
                SystemOneTestSupport.resolving(SystemOneTestSupport.profile("m", 255, 1)));
        NodeAction action = behavior.create(SystemOneTestSupport.configuration(
                SystemOneTestSupport.SPECIFICATION), services);
        var signal = new TestCancellation();
        CompletableFuture<NodeResult> first = action.handle(
                SystemOneTestSupport.message(Map.of("body", "hello")), signal).toCompletableFuture();

        assertEquals(SystemOneException.Code.CAPACITY_UNAVAILABLE, failureOf(action).code());
        signal.cancel();
        CompletionException cancelled = assertThrows(CompletionException.class, first::join);
        assertEquals(SystemOneException.Code.DEADLINE_EXCEEDED,
                assertInstanceOf(SystemOneException.class, cancelled.getCause()).code());
        for (int attempt = 0; attempt < 100 && behavior.admissionEntries() != 0; attempt++) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    void typedEvidenceDoesNotForgeOrMintGenerativeProvenance() throws Exception {
        Object input = Map.of("body", "hello");
        NodeTypeDescriptor generative = new NodeTypeDescriptor("fixture-generative", "fixture", "test",
                "", "actor", false, List.of(), Set.of("ai"));
        Map<String, Object> marker = SyntheticProvenance.mint("source", generative, input).orElseThrow();
        Map<String, Object> inbound = Map.of(SyntheticProvenance.ATTRIBUTE, marker, "audit.correlation", "c-1");
        var services = new SystemOneTestSupport.HttpDouble();
        var behavior = new SystemOneEvaluateNodeBehavior(
                SystemOneTestSupport.resolving(SystemOneTestSupport.profile("m")));

        NodeResult result = behavior.create(SystemOneTestSupport.configuration(
                        SystemOneTestSupport.SPECIFICATION), services)
                .handle(SystemOneTestSupport.message(input, inbound)).toCompletableFuture().get();

        assertFalse(SyntheticProvenance.isGenerative(behavior.descriptor()));
        assertTrue(SyntheticProvenance.mint("evaluate", behavior.descriptor(), result.payload()).isEmpty());
        assertEquals(marker, result.attributes().get(SyntheticProvenance.ATTRIBUTE));
        assertEquals("c-1", result.attributes().get("audit.correlation"));
        assertFalse(SyntheticProvenance.describes(marker, result.payload()),
                "the runtime can now invalidate the preserved inbound marker for the changed payload");
        assertEquals(Set.of(SyntheticProvenance.ATTRIBUTE), result.attributes().keySet().stream()
                .filter(SyntheticProvenance::isProvenanceKey).collect(java.util.stream.Collectors.toSet()));
    }

    private static NodeAction action(SystemOneProfile profile, String specification,
                                     SystemOneTestSupport.HttpDouble services) {
        return new SystemOneEvaluateNodeBehavior(SystemOneTestSupport.resolving(profile))
                .create(SystemOneTestSupport.configuration(specification), services);
    }

    private static SystemOneException failureOf(NodeAction action) {
        return failureOf(action, Map.of("body", "hello"));
    }

    private static SystemOneException failureOf(NodeAction action, Object payload) {
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> action.handle(SystemOneTestSupport.message(payload)).toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return assertInstanceOf(SystemOneException.class, cause);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static String json(Object value) {
        return new String(PayloadJson.writeJava(value, new PayloadLimits(1024 * 1024, 32,
                10_000, 100_000, 1024 * 1024, 4_096)), StandardCharsets.UTF_8);
    }

    private static String fixture() {
        return new String(SystemOneTestSupport.fixture("jev-response.json"), StandardCharsets.UTF_8);
    }

    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new ArrayList<>();
        private boolean cancelled;
        @Override public synchronized boolean cancelled() { return cancelled; }
        @Override public synchronized void onCancel(Runnable listener) {
            if (cancelled) listener.run(); else listeners.add(listener);
        }
        synchronized void cancel() {
            cancelled = true;
            List.copyOf(listeners).forEach(Runnable::run);
            listeners.clear();
        }
    }
}
