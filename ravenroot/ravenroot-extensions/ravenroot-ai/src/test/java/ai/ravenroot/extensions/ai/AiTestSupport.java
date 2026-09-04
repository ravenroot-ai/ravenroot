package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.node.service.ToolCallAuthorization;
import ai.ravenroot.api.node.service.ToolCallAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AiTestSupport {

    private AiTestSupport() {
    }

    static ai.ravenroot.api.node.service.AgentResourceService unlimitedTestResources() {
        return new ai.ravenroot.api.node.service.AgentResourceService() {
            private final ai.ravenroot.api.node.service.AgentResourceSession session =
                    new ai.ravenroot.api.node.service.AgentResourceSession() {
                @Override public ai.ravenroot.api.node.service.AgentModelReservation reserveModelTurn(long ordinal) {
                    return new ai.ravenroot.api.node.service.AgentModelReservation() {
                        @Override public long maximumOutputTokens() { return Long.MAX_VALUE; }
                        @Override public java.time.Duration maximumDuration() {
                            return java.time.Duration.ofDays(3650);
                        }
                        @Override public void dispatch() { }
                        @Override public void release() { }
                        @Override public void settle(java.util.Optional<Long> inputTokens,
                                                     java.util.Optional<Long> outputTokens) { }
                        @Override public void indeterminate() { }
                    };
                }
                @Override public ai.ravenroot.api.node.service.AgentResourceSession createChild(
                        ai.ravenroot.api.node.service.AgentChildResourceRequest request) { return this; }
                @Override public void complete() { }
                @Override public void cancel() { }
                @Override public void suspend() { }
            };
            @Override public ai.ravenroot.api.node.service.AgentResourceSession admit(
                    ai.ravenroot.api.execution.NodeMessage message,
                    ai.ravenroot.api.node.service.AgentResourceRequest request) { return session; }
            @Override public ai.ravenroot.api.node.service.AgentResourceSession resume(
                    ai.ravenroot.api.node.ToolCallContinuationInput continuation,
                    ai.ravenroot.api.node.service.AgentResourceRequest request) { return session; }
        };
    }

    static final class TrackingAgentResources implements ai.ravenroot.api.node.service.AgentResourceService {
        final AtomicInteger admissions = new AtomicInteger();
        final AtomicInteger completes = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger failedAttempts = new AtomicInteger();
        final AtomicInteger suspends = new AtomicInteger();
        final AtomicInteger modelDispatches = new AtomicInteger();
        final AtomicInteger modelReleases = new AtomicInteger();
        final AtomicInteger modelIndeterminate = new AtomicInteger();
        private final long maximumOutputTokens;
        private final java.time.Duration maximumDuration;
        private volatile RuntimeException settlementFailure;
        private volatile RuntimeException cancellationFailure;
        private volatile java.util.concurrent.CountDownLatch completionEntered;
        private volatile java.util.concurrent.CountDownLatch releaseCompletion;

        TrackingAgentResources() {
            this(Long.MAX_VALUE, java.time.Duration.ofDays(3650));
        }

        TrackingAgentResources(long maximumOutputTokens, java.time.Duration maximumDuration) {
            this.maximumOutputTokens = maximumOutputTokens;
            this.maximumDuration = maximumDuration;
        }

        TrackingAgentResources failingSettlement(RuntimeException failure) {
            settlementFailure = failure;
            return this;
        }

        TrackingAgentResources failingCancellation(RuntimeException failure) {
            cancellationFailure = failure;
            return this;
        }

        TrackingAgentResources blockingCompletion(java.util.concurrent.CountDownLatch entered,
                                                   java.util.concurrent.CountDownLatch release) {
            completionEntered = entered;
            releaseCompletion = release;
            return this;
        }

        @Override public ai.ravenroot.api.node.service.AgentResourceSession admit(
                ai.ravenroot.api.execution.NodeMessage message,
                ai.ravenroot.api.node.service.AgentResourceRequest request) {
            admissions.incrementAndGet();
            return session();
        }

        @Override public ai.ravenroot.api.node.service.AgentResourceSession resume(
                ai.ravenroot.api.node.ToolCallContinuationInput continuation,
                ai.ravenroot.api.node.service.AgentResourceRequest request) {
            admissions.incrementAndGet();
            return session();
        }

        private ai.ravenroot.api.node.service.AgentResourceSession session() {
            return new ai.ravenroot.api.node.service.AgentResourceSession() {
                @Override public ai.ravenroot.api.node.service.AgentModelReservation reserveModelTurn(long ordinal) {
                    return new ai.ravenroot.api.node.service.AgentModelReservation() {
                        private final java.util.concurrent.atomic.AtomicBoolean dispatched =
                                new java.util.concurrent.atomic.AtomicBoolean();
                        private final java.util.concurrent.atomic.AtomicBoolean terminal =
                                new java.util.concurrent.atomic.AtomicBoolean();
                        @Override public long maximumOutputTokens() { return maximumOutputTokens; }
                        @Override public java.time.Duration maximumDuration() { return maximumDuration; }
                        @Override public void dispatch() {
                            if (dispatched.compareAndSet(false, true)) modelDispatches.incrementAndGet();
                        }
                        @Override public void release() {
                            if (terminal.compareAndSet(false, true)) modelReleases.incrementAndGet();
                        }
                        @Override public void settle(java.util.Optional<Long> inputTokens,
                                                     java.util.Optional<Long> outputTokens) {
                            terminal.compareAndSet(false, true);
                            if (settlementFailure != null) throw settlementFailure;
                        }
                        @Override public void indeterminate() {
                            if (terminal.compareAndSet(false, true)) modelIndeterminate.incrementAndGet();
                        }
                    };
                }
                @Override public ai.ravenroot.api.node.service.AgentResourceSession createChild(
                        ai.ravenroot.api.node.service.AgentChildResourceRequest request) { return session(); }
                @Override public void complete() {
                    completes.incrementAndGet();
                    if (completionEntered != null) completionEntered.countDown();
                    if (releaseCompletion != null) {
                        try {
                            if (!releaseCompletion.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("resource completion was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("resource completion interrupted", interrupted);
                        }
                    }
                }
                @Override public void cancel() {
                    cancels.incrementAndGet();
                    if (cancellationFailure != null) throw cancellationFailure;
                }
                @Override public void failAttempt() { failedAttempts.incrementAndGet(); }
                @Override public void suspend() { suspends.incrementAndGet(); }
            };
        }
    }

    static NodeMessage message(Object payload) {
        return message("tenant-a", payload, Map.of());
    }

    static NodeMessage message(String tenant, Object payload, Map<String, Object> attributes) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(
                new SecurityContext("request", tenant, "subject", PrincipalType.WORKLOAD, "issuer"),
                id, UUID.randomUUID(), "ask", payload, attributes);
    }

    static NodeConfiguration configuration(Map<String, Object> properties) {
        return new NodeConfiguration("ask", LlmPromptNodeBehavior.BEHAVIOR, properties);
    }

    static NodeConfiguration agentConfiguration(Map<String, Object> properties) {
        return new NodeConfiguration("ask", AgentNodeBehavior.BEHAVIOR, properties);
    }

    static LlmProfile profile(String endpoint) {
        return new LlmProfile("local", URI.create(endpoint), "qwen38", Optional.empty(),
                5_000, 1024 * 1024, 2);
    }

    static LlmProfileResolver resolving(LlmProfile profile) {
        return name -> profile.name().equals(name) ? Optional.of(profile) : Optional.empty();
    }

    static ToolCallAuthorizationService allowingTools() {
        return (message, tool, arguments) -> new ToolCallAuthorization() {
            private final UUID id = UUID.randomUUID();
            private final byte[] canonical = arguments == null ? new byte[0] : arguments.clone();

            @Override public UUID callId() { return id; }
            @Override public Disposition disposition() { return Disposition.ALLOW; }
            @Override public String argumentsDigest() { return "test"; }
            @Override public byte[] canonicalArguments() { return canonical.clone(); }
            @Override public void complete(Outcome outcome) { java.util.Objects.requireNonNull(outcome); }
        };
    }

    /** The exact bytes an operator would put in {@code RAVENROOT_LLM_PROFILE_<hex(name)>}. */
    static String encodedProfile(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A services view that answers one canned response and records what it was asked for. */
    static final class HttpDouble implements NodePackageServices {
        final AtomicReference<OutboundHttpRequest> request = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException synchronousFailure;
        volatile ai.ravenroot.api.node.service.AgentResourceService resources = unlimitedTestResources();
        volatile CompletableFuture<OutboundHttpResponse> response = CompletableFuture.completedFuture(
                new OutboundHttpResponse(200, Map.of("content-type", java.util.List.of("application/json")),
                        ChatCompletionsDouble.completion("hello").getBytes(StandardCharsets.UTF_8)));

        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP,
                    NodePackageCapability.TOOL_AUTHORIZATION);
        }

        @Override public ToolCallAuthorizationService toolAuthorization() { return allowingTools(); }

        @Override public ai.ravenroot.api.node.service.AgentResourceService agentResources() {
            return resources;
        }

        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }

        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }

        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, sent) -> {
                calls.incrementAndGet();
                request.set(sent);
                if (synchronousFailure != null) {
                    throw synchronousFailure;
                }
                return new OutboundCall<>() {
                    @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() {
                        return response;
                    }

                    @Override public boolean cancel() {
                        return response.cancel(true);
                    }
                };
            };
        }
    }

    /**
     * A services view that answers a <em>script</em> of responses, one per call, and records every
     * request body it was handed.
     *
     * <p>{@link HttpDouble} answers the same thing forever, which is the right double for a node that
     * calls once. A loop needs a far end that changes its mind between turns -- ask for a tool, then
     * answer -- because otherwise no test can tell a loop that ran twice from one that ran once and
     * was asked twice.</p>
     */
    static final class ScriptedHttp implements NodePackageServices {
        /** A scripted step: either a response, or a transport failure below the application layer. */
        private record Step(OutboundHttpResponse response, RuntimeException failure) {
        }

        private final java.util.List<Step> script = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<byte[]> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<java.time.Duration> deadlines =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger next = new AtomicInteger();
        private volatile Step forever;
        private volatile ai.ravenroot.api.node.service.AgentResourceService resources = unlimitedTestResources();

        /** Adds one 200 response carrying {@code json}. */
        ScriptedHttp then(String json) {
            script.add(new Step(response(200, json), null));
            return this;
        }

        /** Adds one response carrying an explicit managed final-output authority. */
        ScriptedHttp thenWithOutputLimit(String json, long maximumOutputBytes) {
            script.add(new Step(new OutboundHttpResponse(200,
                    Map.of("content-type", java.util.List.of("application/json")),
                    json.getBytes(StandardCharsets.UTF_8), maximumOutputBytes), null));
            return this;
        }

        /** Adds one response with a chosen status. */
        ScriptedHttp thenStatus(int status, String json) {
            script.add(new Step(response(status, json), null));
            return this;
        }

        /**
         * Adds one call that fails below the application layer, the way a dropped connection does.
         *
         * <p>Distinct from {@link #thenStatus}: a 5xx is a rejection over a transport that worked,
         * and the two arrive at the node on different branches.</p>
         */
        ScriptedHttp thenTransportFailure() {
            script.add(new Step(null, new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.TRANSPORT_FAILED)));
            return this;
        }

        /** Answers {@code json} for every call past the end of the script. */
        ScriptedHttp thenForever(String json) {
            forever = new Step(response(200, json), null);
            return this;
        }

        ScriptedHttp resources(ai.ravenroot.api.node.service.AgentResourceService service) {
            resources = java.util.Objects.requireNonNull(service);
            return this;
        }

        int calls() {
            return next.get();
        }

        /** The request bodies, in the order they were sent. */
        java.util.List<byte[]> bodies() {
            return java.util.List.copyOf(bodies);
        }

        /** The deadline each request asked the managed channel for, in order. */
        java.util.List<java.time.Duration> deadlines() {
            return java.util.List.copyOf(deadlines);
        }

        private static OutboundHttpResponse response(int status, String json) {
            return new OutboundHttpResponse(status,
                    Map.of("content-type", java.util.List.of("application/json")),
                    json.getBytes(StandardCharsets.UTF_8));
        }

        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP,
                    NodePackageCapability.TOOL_AUTHORIZATION);
        }

        @Override public ToolCallAuthorizationService toolAuthorization() { return allowingTools(); }

        @Override public ai.ravenroot.api.node.service.AgentResourceService agentResources() {
            return resources;
        }

        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }

        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }

        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, sent) -> {
                int index = next.getAndIncrement();
                bodies.add(sent.body());
                deadlines.add(sent.deadline());
                Step step = index < script.size() ? script.get(index) : forever;
                if (step == null) {
                    throw new IllegalStateException("the script ran out at call " + (index + 1));
                }
                CompletableFuture<OutboundHttpResponse> completion = step.failure() != null
                        ? CompletableFuture.failedFuture(step.failure())
                        : CompletableFuture.completedFuture(step.response());
                return new OutboundCall<>() {
                    @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() {
                        return completion;
                    }

                    @Override public boolean cancel() {
                        return completion.cancel(true);
                    }
                };
            };
        }
    }

    static McpProfile mcpProfile(String name, String endpoint, String... allowedTools) {
        return new McpProfile(name, URI.create(endpoint), Optional.empty(), 5_000, 1024 * 1024, 2,
                Set.of(allowedTools));
    }

    static McpProfileResolver resolvingMcp(McpProfile... profiles) {
        var byName = new java.util.LinkedHashMap<String, McpProfile>();
        for (McpProfile profile : profiles) {
            byName.put(profile.name(), profile);
        }
        return name -> Optional.ofNullable(byName.get(name));
    }

    /** The exact bytes an operator would put in {@code RAVENROOT_MCP_SERVER_<hex(name)>}. */
    static String encodedMcpProfile(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A services view that routes by destination: a chat script at one URI, MCP doubles at others.
     *
     * <p>{@link ScriptedHttp} answers a script positionally, which stops working the moment a run has
     * two far ends: an MCP discovery exchange would consume the model's next scripted turn and every
     * assertion downstream would be about the wrong thing. Routing by destination is what keeps a
     * two-far-end test readable, and it is also what lets a test say <b>which</b> server received a
     * call — the claim the collision criterion actually needs.</p>
     */
    static final class RoutedHttp implements NodePackageServices {
        private final String chatEndpoint;
        private final java.util.List<String> chatScript = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<byte[]> chatBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Map<String, McpDouble> servers = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger chatCalls = new AtomicInteger();
        private volatile boolean stallChat;
        private volatile boolean uncancellable;
        private volatile boolean cancellationThrows;
        private volatile ToolCallAuthorizationService toolAuthorization = allowingTools();
        private volatile ai.ravenroot.api.node.service.AgentResourceService resources = unlimitedTestResources();
        private volatile long mcpMaximumOutputBytes = Long.MAX_VALUE;
        private final java.util.List<CompletableFuture<OutboundHttpResponse>> stalled =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Boolean> cancelled =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        RoutedHttp(String chatEndpoint) {
            this.chatEndpoint = chatEndpoint;
        }

        RoutedHttp chatting(String... turns) {
            chatScript.clear();
            chatScript.addAll(java.util.List.of(turns));
            return this;
        }

        /**
         * The model endpoint accepts the request and never answers.
         *
         * <p>The only way to hold a run in flight here: every other double in this file completes on
         * the calling thread, so a test that wants to observe a lease being <em>held</em> rather than
         * taken and released has nothing to observe. It is the model endpoint that stalls and not an
         * MCP server, because the leases are all taken before the first model turn — so a run stalled
         * there is holding every one of them.</p>
         */
        RoutedHttp chattingForever() {
            stallChat = true;
            return this;
        }

        /**
         * As {@link #chattingForever()}, but the call refuses to be cancelled.
         *
         * <p>The case that separates "the run was told to stop" from "the run actually stopped".
         * Cancelling the outbound call is the easy half and it is enough whenever the channel honours
         * it. It does not always: a response that has already arrived, or a callback already queued,
         * leaves {@code cancel()} nothing to cancel — it returns false and the answer is delivered
         * anyway. Without a check on the way into the next turn, that answer restarts a loop the
         * caller stopped, and the loop then makes a remote MCP call.</p>
         *
         * <p>A fixture whose call cancels cleanly cannot show this, which is why it is here: with the
         * cancellable variant alone, removing every guard from the loop leaves the suite green.</p>
         */
        RoutedHttp chattingForeverAndRefusingCancellation() {
            stallChat = true;
            uncancellable = true;
            return this;
        }

        /**
         * As {@link #chattingForever()}, but the call <em>throws</em> when asked to cancel.
         *
         * <p>Allowed by the contract: {@code OutboundCall.cancel()} is a runtime implementation and
         * nothing promises it returns quietly. What it costs is out of proportion to how exotic it
         * looks — the release of the admission runs after the cancel, so one throw there leaves a
         * reference-counted gate above zero, and that key is lost for the life of the process rather
         * than for the length of a run.</p>
         */
        RoutedHttp chattingForeverAndFailingCancellation() {
            stallChat = true;
            cancellationThrows = true;
            return this;
        }

        RoutedHttp serving(String endpoint, McpDouble server) {
            servers.put(endpoint, server);
            return this;
        }

        RoutedHttp mcpOutputLimit(long maximumOutputBytes) {
            mcpMaximumOutputBytes = maximumOutputBytes;
            return this;
        }

        RoutedHttp authorizing(ToolCallAuthorizationService authorization) {
            toolAuthorization = java.util.Objects.requireNonNull(authorization);
            return this;
        }

        RoutedHttp resources(ai.ravenroot.api.node.service.AgentResourceService service) {
            resources = java.util.Objects.requireNonNull(service);
            return this;
        }

        int chatCalls() {
            return chatCalls.get();
        }

        /**
         * Answers a stalled model call, the way a slow endpoint eventually would.
         *
         * <p>The point of a cancelled run is not that the endpoint never answers -- it usually does,
         * a moment later. It is that the answer must not restart a loop the caller stopped, and that
         * can only be observed by letting the answer arrive.</p>
         */
        void releaseChat(String turn) {
            stallChat = false;
            chatScript.clear();
            chatScript.add(turn);
            stalled.forEach(pending -> pending.complete(new OutboundHttpResponse(200,
                    McpDouble.jsonContentType(), turn.getBytes(StandardCharsets.UTF_8))));
        }

        /** Whether the managed channel was asked to cancel each call, in order. */
        java.util.List<Boolean> cancelled() {
            return java.util.List.copyOf(cancelled);
        }

        java.util.List<byte[]> chatBodies() {
            return java.util.List.copyOf(chatBodies);
        }

        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP,
                    NodePackageCapability.TOOL_AUTHORIZATION);
        }

        @Override public ToolCallAuthorizationService toolAuthorization() { return toolAuthorization; }

        @Override public ai.ravenroot.api.node.service.AgentResourceService agentResources() {
            return resources;
        }

        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }

        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }

        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, sent) -> {
                String destination = sent.destination().toString();
                if (destination.equals(chatEndpoint)) {
                    int index = chatCalls.getAndIncrement();
                    chatBodies.add(sent.body());
                    if (stallChat) {
                        var never = new CompletableFuture<OutboundHttpResponse>();
                        stalled.add(never);
                        int slot = cancelled.size();
                        cancelled.add(Boolean.FALSE);
                        return new OutboundCall<OutboundHttpResponse>() {
                            @Override
                            public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() {
                                return never;
                            }

                            @Override
                            public boolean cancel() {
                                cancelled.set(slot, Boolean.TRUE);
                                if (cancellationThrows) {
                                    throw new IllegalStateException("cancel refused");
                                }
                                if (uncancellable) {
                                    // Asked, and declined -- exactly what a channel answers for a
                                    // response that has already come back.
                                    return false;
                                }
                                return never.cancel(true);
                            }
                        };
                    }
                    if (chatScript.isEmpty()) {
                        throw new IllegalStateException("no chat script");
                    }
                    String turn = chatScript.get(Math.min(index, chatScript.size() - 1));
                    return OutboundCall.completed(new OutboundHttpResponse(200,
                            McpDouble.jsonContentType(), turn.getBytes(StandardCharsets.UTF_8)));
                }
                McpDouble server = servers.get(destination);
                if (server == null) {
                    return OutboundCall.failed(
                            new ai.ravenroot.api.node.service.NodePackageServiceException(
                                    ai.ravenroot.api.node.service.NodePackageServiceException
                                            .Reason.DESTINATION_FORBIDDEN));
                }
                McpDouble.Answer answer = server.respond(sent.body(), sent.headers());
                if (answer.status() < 0) {
                    return OutboundCall.failed(
                            new ai.ravenroot.api.node.service.NodePackageServiceException(
                                    ai.ravenroot.api.node.service.NodePackageServiceException
                                            .Reason.DEADLINE_EXCEEDED));
                }
                var responseHeaders = new java.util.LinkedHashMap<String, java.util.List<String>>();
                responseHeaders.put("content-type", java.util.List.of(answer.contentType()));
                responseHeaders.putAll(answer.headers());
                return OutboundCall.completed(new OutboundHttpResponse(answer.status(),
                        responseHeaders, answer.body(), mcpMaximumOutputBytes));
            };
        }
    }

    /** A well-formed turn in which the model answers. */
    static String answers(String text) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + text
                + "\"}}]}";
    }

    /** A well-formed turn in which the model asks for one tool, passing no arguments. */
    static String asksFor(String callId, String tool) {
        return asksFor(callId, tool, "{}");
    }

    /**
     * A well-formed turn in which the model asks for one tool with arguments.
     *
     * <p>{@code arguments} is a JSON <em>string</em> on the wire, exactly as an endpoint sends it, so
     * the document handed here is embedded escaped. Tests that pass a real argument need this: the
     * no-argument overload cannot tell a tool that reads its input from one that ignores it.</p>
     */
    static String asksFor(String callId, String tool, String argumentsJson) {
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"" + tool + "\",\"arguments\":\"" + escaped(argumentsJson)
                + "\"}}]}}]}";
    }

    /** Escapes a JSON document so it can be embedded as a JSON string member. */
    private static String escaped(String json) {
        return json.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A well-formed turn in which the model asks for one tool and reports token usage. */
    static String asksForUsing(String callId, String tool, String argumentsJson,
                               long promptTokens, long completionTokens) {
        String turn = asksFor(callId, tool, argumentsJson);
        return turn.substring(0, turn.length() - 1) + ",\"usage\":{\"prompt_tokens\":" + promptTokens
                + ",\"completion_tokens\":" + completionTokens + "}}";
    }

    /**
     * A turn asking for one tool with arguments the caller chooses.
     *
     * <p>{@link #asksFor} sends {@code {}}, which is right for a tool that takes none. A tool that
     * reads its argument needs a turn that carries one, and escaping a JSON document inside another
     * by hand at the call site is how a test ends up asserting against a malformed request.</p>
     */
    static String asksForWith(String callId, String tool, String argumentsJson) {
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}]}}]}";
    }

    /** A well-formed answered turn that also reports token usage. */
    static String answersUsing(String text, long promptTokens, long completionTokens) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + text
                + "\"}}],\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":"
                + completionTokens + "}}";
    }
}
