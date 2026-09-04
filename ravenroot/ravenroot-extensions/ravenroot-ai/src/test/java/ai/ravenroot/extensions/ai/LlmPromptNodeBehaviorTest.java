package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPromptNodeBehaviorTest {

    private static final String ENDPOINT = "https://model.example.test/v1/chat/completions";

    @Test
    @DisplayName("the descriptor declares the capability that makes the runtime mark this node's output")
    void theDescriptorDeclaresTheGenerativeCapability() {
        var descriptor = new LlmPromptNodeBehavior().descriptor();

        assertTrue(descriptor.capabilities().contains("ai"));
        // The runtime's own predicate, not a restatement of it: this is the call GraphRunner makes.
        assertTrue(SyntheticProvenance.isGenerative(descriptor));
        assertEquals(java.util.List.of("ai"), SyntheticProvenance.generativeCapabilities(descriptor));
        assertTrue(SyntheticProvenance.mint("ask", descriptor, "an answer").isPresent());
        // agentic() describes control style, not provenance, and the departed core node declared it
        // false while being the most generative node the product ever shipped.
        assertFalse(descriptor.agentic());
    }

    @Test
    @DisplayName("the behavior requires the managed HTTP grant and never credential resolution")
    void theBehaviorRequiresOnlyOutboundHttp() {
        assertEquals(java.util.Set.of(NodePackageCapability.OUTBOUND_HTTP),
                new LlmPromptNodeBehavior().requiredServices());
    }

    @Test
    @DisplayName("a blank provider refuses when reached, and does so as a failed future")
    void aBlankProviderRefusesWhenReached() {
        NodeAction action = new LlmPromptNodeBehavior(name -> java.util.Optional.empty())
                .create(AiTestSupport.configuration(Map.of("provider", "", "prompt", "Say hi")),
                        NodePackageServices.unavailable());

        // Not a throw from create(): a node whose adapter is merely unconfigured must still let the
        // graph construct, so the failure is attributable to the node rather than to the submission.
        LlmPromptException refusal = failureOf(action);
        assertEquals(LlmPromptException.Code.PROFILE_UNKNOWN, refusal.code());
    }

    @Test
    @DisplayName("an unknown profile refuses with the profile name and no NodeResult")
    void anUnknownProfileRefuses() {
        NodeAction action = new LlmPromptNodeBehavior(name -> java.util.Optional.empty())
                .create(AiTestSupport.configuration(Map.of("provider", "missing", "prompt", "Say hi")),
                        NodePackageServices.unavailable());

        LlmPromptException refusal = failureOf(action);
        assertEquals(LlmPromptException.Code.PROFILE_UNKNOWN, refusal.code());
        assertEquals("missing", refusal.hint());
    }

    @Test
    @DisplayName("a deny-only services view refuses at invocation rather than answering")
    void aDenyOnlyServicesViewRefuses() {
        // Not the operator-facing case for THIS bundle: requiredServices() declares outbound-http, so
        // a deployment with no grant is refused at plugin activation and never starts. This covers the
        // views that still reach a node -- an SDK /1 construction path, or a composition that granted
        // some other capability -- where the refusal has to arrive as a failed future at invocation.
        var profile = AiTestSupport.profile(ENDPOINT);
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(profile))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Say hi")),
                        NodePackageServices.unavailable());

        assertEquals(LlmPromptException.Code.CAPACITY_UNAVAILABLE, failureOf(action).code());
    }

    @Test
    @DisplayName("the rendered prompt travels as a single user turn, with stream stated false")
    void theRenderedPromptTravelsAsOneUserTurn() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of(
                        "provider", "local", "prompt", "Summarise {{payload}}", "maxTokens", "64")), services);

        NodeResult result = action.handle(AiTestSupport.message("the product")).toCompletableFuture()
                .get();

        assertEquals("continue", result.outcome());
        assertEquals("hello", result.payload());
        var sent = assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(services.request.get().body(), PayloadLimits.DEFAULTS));
        assertEquals(PayloadValue.of("qwen38"), sent.entries().get("model"));
        assertEquals(PayloadValue.of(false), sent.entries().get("stream"));
        assertEquals(PayloadValue.of(64L), sent.entries().get("max_tokens"));
        var turns = assertInstanceOf(PayloadValue.ListValue.class, sent.entries().get("messages"));
        assertEquals(1, turns.values().size());
        var turn = assertInstanceOf(PayloadValue.MapValue.class, turns.values().get(0));
        assertEquals(PayloadValue.of("user"), turn.entries().get("role"));
        assertEquals(PayloadValue.of("Summarise the product"), turn.entries().get("content"));
        assertEquals("POST", services.request.get().method());
        assertEquals(java.util.Optional.empty(), services.request.get().credential());
        assertEquals(AiTestSupport.profile(ENDPOINT).maxResponseBytes(),
                services.request.get().limits().maximumEncodedResponseBytes());
        assertEquals(Set.of("application/json"), services.request.get().limits().acceptedMediaTypes());
        assertEquals(Set.of("identity", "gzip"),
                services.request.get().limits().acceptedContentEncodings());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> provenance = (List<Map<String, Object>>)
                result.attributes().get(ModelInputProvenance.PROMPT_ATTRIBUTE);
        assertEquals(List.of("RENDERED_PROMPT", "INBOUND_PAYLOAD", "INBOUND_ATTRIBUTES", "MODEL_OUTPUT"),
                provenance.stream().map(entry -> entry.get("kind")).toList());
        assertTrue(provenance.stream().allMatch(entry -> String.valueOf(entry.get("digest"))
                .startsWith("sha256:")));
        assertFalse(provenance.toString().contains("the product"));
        assertFalse(provenance.toString().contains("hello"));
    }

    @Test
    @DisplayName("a profile carrying an operator preamble does not give this node a system turn")
    void aProfilePreambleDoesNotReachTheSingleCallNode() throws Exception {
        // systemPreamble belongs to LlmProfile's agent-node contract. This node's whole
        // safety argument is that graph content never reaches a system turn and that no second call
        // is expressible from here, and neither may be weakened by a field it does not read. Without
        // this test a later change that started reading it would break nothing in the suite.
        var services = new AiTestSupport.HttpDouble();
        var profile = new LlmProfile("local", java.net.URI.create(ENDPOINT), "qwen38",
                java.util.Optional.empty(), 5_000, 1024 * 1024, 2, "OPERATOR PREAMBLE");

        NodeResult result = new LlmPromptNodeBehavior(AiTestSupport.resolving(profile))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Say hi")),
                        services)
                .handle(AiTestSupport.message("the product")).toCompletableFuture().get();

        assertEquals("hello", result.payload());
        assertEquals(1, services.calls.get());
        var sent = assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(services.request.get().body(), PayloadLimits.DEFAULTS));
        var turns = assertInstanceOf(PayloadValue.ListValue.class, sent.entries().get("messages"));
        assertEquals(1, turns.values().size());
        var turn = assertInstanceOf(PayloadValue.MapValue.class, turns.values().get(0));
        assertEquals(PayloadValue.of("user"), turn.entries().get("role"));
        assertFalse(new String(services.request.get().body(), StandardCharsets.UTF_8)
                .contains("OPERATOR PREAMBLE"));
    }

    @Test
    @DisplayName("a malformed tuning property is dropped, never thrown on")
    void aMalformedTuningPropertyIsDropped() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi",
                        "temperature", "not-a-number", "seed", "NaN", "maxTokens", "-1")), services);

        action.handle(AiTestSupport.message("x")).toCompletableFuture().get();

        var sent = assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(services.request.get().body(), PayloadLimits.DEFAULTS));
        assertFalse(sent.entries().containsKey("temperature"));
        assertFalse(sent.entries().containsKey("seed"));
        assertFalse(sent.entries().containsKey("max_tokens"));
    }

    @Test
    @DisplayName("the node deadline may tighten the profile's, never widen it")
    void theNodeDeadlineMayOnlyTighten() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        var behavior = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        behavior.create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi",
                "timeoutMs", "999999")), services).handle(AiTestSupport.message("x"))
                .toCompletableFuture().get();
        assertEquals(java.time.Duration.ofMillis(5_000), services.request.get().deadline());

        behavior.create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi",
                "timeoutMs", "250")), services).handle(AiTestSupport.message("x"))
                .toCompletableFuture().get();
        assertEquals(java.time.Duration.ofMillis(250), services.request.get().deadline());
    }

    @Test
    @DisplayName("a non-2xx status fails without carrying the endpoint's own document out")
    void aNonSuccessStatusFailsWithoutTheBody() {
        var services = new AiTestSupport.HttpDouble();
        services.response = CompletableFuture.completedFuture(
                new ai.ravenroot.api.node.service.OutboundHttpResponse(500, Map.of(),
                        "{\"error\":\"secret-looking detail and the prompt echoed back\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);

        LlmPromptException failure = failureOf(action);
        assertEquals(LlmPromptException.Code.ENDPOINT_REJECTED, failure.code());
        assertFalse(failure.getMessage().contains("secret-looking"));
        assertFalse(failure.getMessage().contains("echoed"));
    }

    @Test
    @DisplayName("the managed operator output ceiling survives through final node projection")
    void managedOperatorOutputCeilingCannotBeWidenedByTheProfile() {
        var services = new AiTestSupport.HttpDouble();
        services.response = CompletableFuture.completedFuture(
                new ai.ravenroot.api.node.service.OutboundHttpResponse(200,
                        Map.of("content-type", List.of("application/json")),
                        ChatCompletionsDouble.completion("hello").getBytes(StandardCharsets.UTF_8), 32));
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);

        LlmPromptException failure = failureOf(action);

        assertEquals(LlmPromptException.Code.RESPONSE_TOO_LARGE, failure.code());
        assertTrue(services.request.get().limits().maximumOutputBytes() > 32,
                "the profile request was wider than the managed operator response authority");
    }

    @Test
    @DisplayName("a managed-channel refusal is mapped onto this bundle's own closed vocabulary")
    void aManagedChannelRefusalIsMapped() {
        var services = new AiTestSupport.HttpDouble();
        services.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.DESTINATION_FORBIDDEN));
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);

        assertEquals(LlmPromptException.Code.DESTINATION_REFUSED, failureOf(action).code());
    }

    @Test
    @DisplayName("an unrenderable prompt token fails the node and names the token, not the payload")
    void anUnrenderablePromptTokenFailsTheNode() {
        var services = new AiTestSupport.HttpDouble();
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(AiTestSupport.configuration(Map.of("provider", "local",
                        "prompt", "Summarise {{payload.missing}}")), services);

        LlmPromptException failure = failureOf(action, Map.of("present", "confidential-value"));
        assertEquals(LlmPromptException.Code.PROMPT_UNRENDERABLE, failure.code());
        assertEquals("{{payload.missing}}", failure.hint());
        assertFalse(failure.getMessage().contains("confidential-value"));
        assertEquals(0, services.calls.get());
    }

    @Test
    @DisplayName("a released permit is reusable, and no admission entry outlives the calls")
    void admissionDoesNotAccumulate() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        var behavior = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));
        NodeAction action = behavior.create(
                AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);

        // The profile admits two at a time; six sequential calls across two tenants all succeed,
        // which is only true if every lease released.
        for (int repetition = 0; repetition < 5; repetition++) {
            action.handle(AiTestSupport.message("tenant-a", "x", Map.of())).toCompletableFuture().get();
        }
        action.handle(AiTestSupport.message("tenant-b", "x", Map.of())).toCompletableFuture().get();
        assertEquals(6, services.calls.get());

        // And nothing is retained afterwards. A map keyed by tenant that only ever grows is a slow
        // leak in precisely the deployments that have many tenants, so "zero when idle" is the
        // property worth pinning rather than "one entry per tenant seen".
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a full profile refuses the extra caller with CAPACITY_UNAVAILABLE, and recovers")
    void afullProfileRefusesAndRecovers() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        var pending = new CompletableFuture<ai.ravenroot.api.node.service.OutboundHttpResponse>();
        services.response = pending;
        var behavior = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));
        NodeAction action = behavior.create(
                AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);

        // maxConcurrency is 2 on the test profile: two calls hold their leases while `pending` is
        // unresolved, so the third is refused rather than queued.
        var first = action.handle(AiTestSupport.message("x")).toCompletableFuture();
        var second = action.handle(AiTestSupport.message("x")).toCompletableFuture();
        assertEquals(LlmPromptException.Code.CAPACITY_UNAVAILABLE, failureOf(action).code());
        assertEquals(1, behavior.admissionEntries());

        pending.complete(new ai.ravenroot.api.node.service.OutboundHttpResponse(200, Map.of(),
                ChatCompletionsDouble.completion("hello").getBytes(StandardCharsets.UTF_8)));
        first.get();
        second.get();
        assertEquals(0, behavior.admissionEntries());
        // The permit really came back: a fourth call now succeeds.
        assertEquals("hello", action.handle(AiTestSupport.message("x")).toCompletableFuture().get().payload());
    }

    @Test
    @DisplayName("the credential travels as a binding the runtime resolves, never as a value here")
    void theCredentialTravelsAsABinding() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        var profile = new LlmProfile("hosted", java.net.URI.create(ENDPOINT), "qwen38",
                java.util.Optional.of(new ai.ravenroot.api.node.service.OutboundCredentialBinding(
                        "llm", "llm-key")), 5_000, 1024 * 1024, 2);
        NodeAction action = new LlmPromptNodeBehavior(AiTestSupport.resolving(profile))
                .create(AiTestSupport.configuration(Map.of("provider", "hosted", "prompt", "Hi")), services);

        NodeResult result = action.handle(AiTestSupport.message("x")).toCompletableFuture().get();

        // The request names the binding; the runtime is what turns it into a header. Nothing in this
        // process ever held the value, and there is no path by which it could: the behavior does not
        // require CREDENTIAL_RESOLUTION, and the services view here refuses that call outright.
        var binding = services.request.get().credential().orElseThrow();
        assertEquals("llm", binding.bindingId());
        assertEquals("llm-key", binding.reference());
        assertFalse(services.request.get().headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("authorization")));
        // Not even the opaque reference is echoed into what the execution record keeps.
        assertFalse(result.attributes().toString().contains("llm-key"));
        assertFalse(new String(services.request.get().body(), StandardCharsets.UTF_8).contains("llm-key"));
    }

    @Test
    void engineCancellationCancelsTheActiveModelCallAndReturnsTheAdmissionPermit() throws Exception {
        var services = new AiTestSupport.HttpDouble();
        services.response = new CompletableFuture<>();
        var behavior = new LlmPromptNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));
        NodeAction action = behavior.create(
                AiTestSupport.configuration(Map.of("provider", "local", "prompt", "Hi")), services);
        var cancellation = new TestCancellation();

        var result = action.handle(AiTestSupport.message("x"), cancellation).toCompletableFuture();
        cancellation.cancel();

        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertEquals(LlmPromptException.Code.DEADLINE_EXCEEDED,
                assertInstanceOf(LlmPromptException.class, failure.getCause()).code());
        for (int attempt = 0; attempt < 100 && behavior.admissionEntries() != 0; attempt++) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(0, behavior.admissionEntries());
    }

    private static LlmPromptException failureOf(NodeAction action) {
        return failureOf(action, "payload");
    }

    private static LlmPromptException failureOf(NodeAction action, Object payload) {
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> action.handle(AiTestSupport.message(payload)).toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return assertInstanceOf(LlmPromptException.class, cause);
    }

    private static final class TestCancellation implements CancellationSignal {
        private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) {
            if (cancelled) listener.run(); else listeners.add(listener);
        }
        void cancel() {
            cancelled = true;
            listeners.forEach(Runnable::run);
        }
    }
}
