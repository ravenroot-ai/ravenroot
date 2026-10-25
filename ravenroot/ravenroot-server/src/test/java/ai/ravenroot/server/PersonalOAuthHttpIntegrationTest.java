package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.node.service.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.extensions.ai.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.assistant.AssistantService;
import ai.ravenroot.server.personal.*;
import ai.ravenroot.server.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class PersonalOAuthHttpIntegrationTest {
    @TempDir Path directory;
    final HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final ai.ravenroot.server.personal.PersonalOAuthServiceTest.FakeProvider provider = new ai.ravenroot.server.personal.PersonalOAuthServiceTest.FakeProvider();
    final RequestContext source = new RequestContext("operator", "anonymous-loopback", PrincipalType.USER,
            "urn:ravenroot:disabled-loopback", "local", Set.of(Role.PLATFORM_ADMIN),
            Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
    final AtomicReference<PersonalOAuthService> personal = new AtomicReference<>();
    final AtomicInteger effects = new AtomicInteger();
    final AtomicReference<ai.ravenroot.core.runtime.NodeHandler> probe = new AtomicReference<>();
    final AtomicBoolean failPersonalOnce = new AtomicBoolean();
    final CountDownLatch firstPersonalFailure = new CountDownLatch(1);
    final List<String> personalAttempts = new CopyOnWriteArrayList<>();

    @Test void sameDeployedMixedDefinitionRunsForTwoBrowsersWithSeparateCredentialsAndPrivateDurableOutputs() throws Exception {
        var registry = registry();
        registry.withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        var monitor = new ExecutionMonitor();
        var completed = new CountDownLatch(2);
        monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) completed.countDown(); });
        try (var engine = new PekkoExecutionEngine("personal-http-integration");
             var store = new SqliteExecutionStore(directory.resolve("execution.db"), Clock.systemUTC())) {
            var app = new DefaultRavenrootApplication(engine, monitor, registry, new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store, 2);
            app.registerLocalDeployment(SecurityContext.of(source), "shared-configmap", input(graph(true)));
            app.startLocalDeployment(SecurityContext.of(source), "shared-configmap").toCompletableFuture().join();
            var view = app.localDeploymentView("local", "shared-configmap").orElseThrow();
            try (var server = server(app)) {
                server.start(); install(server);
                String a = login(server, "A", "oaiapp_A"); String b = login(server, "B", "oaiapp_B");
                Map<String, Object> start = Map.of("deploymentId", "shared-configmap", "incarnationId", view.source().incarnationId(), "graphVersion", view.source().graphVersion());
                var aStart = post(server, "/v1/personal/executions", a, json(start));
                var bStart = post(server, "/v1/personal/executions", b, json(start));
                assertEquals(202, aStart.statusCode(), aStart.body()); assertEquals(202, bStart.statusCode(), bStart.body());
                assertTrue(completed.await(10, TimeUnit.SECONDS));
                String aId = (String) object(aStart.body()).get("executionId"), bId = (String) object(bStart.body()).get("executionId");
                String aProcess = (String) object(aStart.body()).get("processInstanceId"), bProcess = (String) object(bStart.body()).get("processInstanceId");
                assertEquals(200, get(server, "/v1/executions/" + aId, a, true).statusCode());
                assertFalse(get(server, "/v1/executions/" + aId, a, true).body().contains("access-A"));
                assertNotEquals(200, get(server, "/v1/executions/" + aId, b, true).statusCode());
                assertNotEquals(200, get(server, "/v1/executions/" + aId, null, false).statusCode());
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "system-key"));
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "access-A"));
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "access-B"));
                RequestContext ownerA = context(a, "read-a"), ownerB = context(b, "read-b");
                var authorized = new AuthorizedRavenrootApplication(app, new DefaultAuthorizationService(event -> { }), event -> { }, false);
                var aEvents = authorized.durableEventsAfter(ownerA, 0, 200);
                assertFalse(aEvents.isEmpty());
                assertTrue(aEvents.stream().allMatch(e -> e.processInstanceId().toString().equals(aProcess)));
                assertTrue(authorized.durableEventsAfter(ownerB, 0, 200).stream().allMatch(e -> e.processInstanceId().toString().equals(bProcess)));
                assertEquals(2, effects.get());
                // Source deployment remains in the original operator namespace.
                assertTrue(app.localDeployment("local", "shared-configmap").isPresent());
                assertTrue(app.localDeployment(ownerA.tenantId(), "shared-configmap").isEmpty());
                // Disconnection invalidates the persisted execution grant without changing system credentials.
                var status = object(get(server, "/v1/personal", a, false).body());
                String connection = (String) ((Map<?, ?>) ((List<?>) status.get("connections")).getFirst()).get("id");
                assertEquals(200, post(server, "/v1/personal/disconnect", a, json(Map.of("connection", connection))).statusCode());
                assertEquals("system-key", new String(personal.get().resolve("ai.ravenroot.extensions.ai", "local", "system-key").orElseThrow().copy()));
            }
        }
    }

    @Test void missingPersonalAuthorityRefusesBeforeAnEarlierEffectAndAnonymousSystemExecutionStillWorks() throws Exception {
        var registry = registry().withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        try (var engine = new PekkoExecutionEngine("personal-preflight")) {
            var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), registry);
            try (var server = server(app)) {
                server.start(); install(server);
                var refused = post(server, "/v1/executions?mode=run", null, graph(true));
                assertEquals(409, refused.statusCode(), refused.body()); assertEquals(0, effects.get());
                // Direct automated/workload execution cannot acquire personal authority by payload fields.
                var workload = new SecurityContext("forged-request", "local", "worker", PrincipalType.WORKLOAD, "urn:workload");
                assertThrows(PersonalConnectionException.class, () -> app.startGraphMl(workload, UUID.randomUUID(), input(graph(true)), Map.of("owner", "A", "tenant", "personal-A")));
                assertEquals(0, effects.get());
                var done = new CompletableFuture<Void>();
                var system = registry.register("system-finished", message -> { done.complete(null); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); });
                var response = post(server, "/v1/executions?mode=run", null, graph(false));
                assertEquals(202, response.statusCode(), response.body());
                done.get(10, TimeUnit.SECONDS); assertEquals("system", response.headers().firstValue("X-Ravenroot-Execution-Context").orElseThrow());
            }
        }
    }

    @Test void ordinaryBrowserSubmissionPinsOwnerDespiteForgedRequestHeadersAndPreservesSystemMode() throws Exception {
        var registry = registry().withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        registry.register("system-finished", message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        var monitor = new ExecutionMonitor(); var done = new CountDownLatch(3);
        monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) done.countDown(); });
        try (var engine = new PekkoExecutionEngine("personal-ordinary-submission")) {
            var app = new DefaultRavenrootApplication(engine, monitor, registry);
            try (var server = server(app)) {
                server.start(); install(server);
                String a = login(server, "A", "oaiapp_A"), b = login(server, "B", "oaiapp_B");
                var aStart = post(server, "/v1/executions?mode=run&owner=B&tenant=personal-B", a, graph(true));
                var bStart = post(server, "/v1/executions?mode=run&owner=A", b, graph(true));
                var system = post(server, "/v1/executions?mode=run", a, graph(false));
                assertEquals(202, aStart.statusCode(), aStart.body()); assertEquals(202, bStart.statusCode(), bStart.body());
                assertEquals(202, system.statusCode(), system.body());
                assertEquals("personal", aStart.headers().firstValue("X-Ravenroot-Execution-Context").orElseThrow());
                assertEquals("system", system.headers().firstValue("X-Ravenroot-Execution-Context").orElseThrow());
                assertTrue(done.await(10, TimeUnit.SECONDS));
                String id = (String) object(aStart.body()).get("executionId");
                assertEquals(200, get(server, "/v1/executions/" + id, a, true).statusCode());
                assertNotEquals(200, get(server, "/v1/executions/" + id, b, true).statusCode());
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "access-A"));
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "access-B"));
                assertEquals(3, Collections.frequency(provider.inferenceBearers, "system-key"));
            }
        }
    }

    @Test void pausedPersonalRunReopensFromSqliteAndWorkerKeepsOriginalOwnerInsteadOfAnotherBrowser() throws Exception {
        Path database = directory.resolve("recovery.db");
        var owner = new AtomicReference<SecurityContext>(); var paused = new CountDownLatch(1);
        var self = new AtomicReference<DefaultRavenrootApplication>();
        var monitor = new ExecutionMonitor();
        monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_PAUSED) paused.countDown(); });
        probe.set(message -> {
            owner.set(message.security()); self.get().pauseTraversal(message.traversalId());
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        UUID traversal;
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC());
             var definitions = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, Clock.systemUTC(), ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE);
             var engine = new PekkoExecutionEngine("personal-before-restart")) {
            var app = durableApp(engine, store, definitions, monitor); self.set(app);
            try (app; var server = server(app)) {
                server.start(); install(server); String a = login(server, "A", "oaiapp_A");
                var start = post(server, "/v1/executions?mode=run", a, graph(true));
                assertEquals(202, start.statusCode(), start.body());
                traversal = UUID.fromString((String) object(start.body()).get("traversalId"));
                assertTrue(paused.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (store.findHeldExecutionPause(owner.get().tenantId(), traversal).toCompletableFuture().join().isEmpty()
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(store.findHeldExecutionPause(owner.get().tenantId(), traversal).toCompletableFuture().join().isPresent());
                assertTrue(provider.inferenceBearers.isEmpty(), "no model call happened before the held boundary");
            }
        }
        probe.set(null);
        var completed = new CountDownLatch(1); var after = new ExecutionMonitor();
        after.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) completed.countDown(); });
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC());
             var definitions = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, Clock.systemUTC(), ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE);
             var engine = new PekkoExecutionEngine("personal-after-restart")) {
            var app = durableApp(engine, store, definitions, after);
            try (app; var server = server(app)) {
                server.start(); install(server); String b = login(server, "B", "oaiapp_B");
                assertFalse(app.resumeTraversal(context(b, "other-browser").tenantId(), traversal));
                personal.get().requireExecutionAuthority(owner.get(), Set.of("openai"));
                assertTrue(app.resumeTraversal(owner.get().tenantId(), traversal));
                assertTrue(completed.await(10, TimeUnit.SECONDS));
                assertEquals(2, Collections.frequency(provider.inferenceBearers, "access-A"));
                assertEquals(0, Collections.frequency(provider.inferenceBearers, "access-B"));
                assertEquals(1, effects.get(), "completed probe must not run twice after reopening");
            }
        }
    }
    DefaultRavenrootApplication durableApp(PekkoExecutionEngine engine, SqliteExecutionStore store,
            ai.ravenroot.api.persistence.GraphDefinitionStore definitions, ExecutionMonitor monitor) throws Exception {
        var registry = registry().withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        return new DefaultRavenrootApplication(engine, monitor, registry, new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store, 2, UnknownBehaviorPolicy.passThrough(), definitions);
    }

    @Test void sourceDeploymentRefusesPersonalRequirementsBeforeConstructingAnyExternalSource() throws Exception {
        var registry = registry().withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        var constructions = new AtomicInteger();
        class Source implements NodeBehavior, InboundSourceCapable {
            public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() {
                return new ai.ravenroot.api.catalog.NodeTypeDescriptor("test.source", "Source", "Test", "Synthetic external source", "actor", false, List.of(), Set.of(), ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE, Set.of(ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE));
            }
            public NodeAction create(NodeConfiguration configuration) { return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }
            public ai.ravenroot.api.deployment.InboundSource createSource(NodeConfiguration configuration, ai.ravenroot.api.deployment.InboundSourceContext context) {
                constructions.incrementAndGet();
                return new ai.ravenroot.api.deployment.InboundSource() {
                    public CompletionStage<Void> start(ai.ravenroot.api.deployment.InboundSourceContext input) { return CompletableFuture.completedFuture(null); }
                    public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                };
            }
        }
        NodePackages.register(registry, new NodePackage() {
            public String id() { return "synthetic.source"; } public String version() { return "test"; }
            public String sdkContract() { return NodeSdk.CONTRACT; } public List<NodeBehavior> behaviors() { return List.of(new Source()); }
        });
        try (var engine = new PekkoExecutionEngine("personal-source-refusal")) {
            var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), registry,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 2);
            String systemSource = graph(false).replace(">probe</data>", ">test.source</data>");
            String personalSource = graph(true).replace(">probe</data>", ">test.source</data>");
            app.registerLocalDeployment(SecurityContext.of(source), "system-source", input(systemSource));
            app.startLocalDeployment(SecurityContext.of(source), "system-source").toCompletableFuture().join();
            assertEquals(1, constructions.get(), "control must really construct an external source");
            app.registerLocalDeployment(SecurityContext.of(source), "personal-source", input(personalSource));
            assertEquals(LocalDeploymentState.FAILED,
                    app.startLocalDeployment(SecurityContext.of(source), "personal-source").toCompletableFuture().join().orElseThrow().state());
            assertEquals(1, constructions.get());
            try (var server = server(app)) {
                server.start(); install(server);
                var refused = post(server, "/v1/source-sessions?id=personal-source-http", null, personalSource);
                assertEquals(409, refused.statusCode(), refused.body());
                assertEquals(1, constructions.get());
            }
        }
    }

    @Test void queuedRetryKeepsTheAdmittedConnectionAfterBrowserSelectsAnotherLinkedAccount() throws Exception {
        var registry = registry().withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements));
        var completed = new CountDownLatch(1); var monitor = new ExecutionMonitor();
        monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) completed.countDown(); });
        try (var engine = new PekkoExecutionEngine("personal-queued-retry")) {
            var app = new DefaultRavenrootApplication(engine, monitor, registry);
            try (var server = server(app)) {
                server.start(); install(server);
                String a = login(server, "A", "oaiapp_A"), linked = login(server, "C", "oaiapp_C", a);
                var connections = (List<?>) object(get(server, "/v1/personal", linked, false).body()).get("connections");
                String accountA = (String) connections.stream().map(value -> (Map<?, ?>) value)
                        .filter(value -> value.get("label").toString().startsWith("A@")) .findFirst().orElseThrow().get("id");
                String accountC = (String) connections.stream().map(value -> (Map<?, ?>) value)
                        .filter(value -> value.get("label").toString().startsWith("C@")) .findFirst().orElseThrow().get("id");
                assertEquals(200, post(server, "/v1/personal/select", linked, json(Map.of("connection", accountA))).statusCode());
                String retrying = graph(true).replace("<graph id=", "<key id=\"rmax\" for=\"node\" attr.name=\"retry.maxAttempts\" attr.type=\"string\"/>"
                    + "<key id=\"rwait\" for=\"node\" attr.name=\"retry.initialBackoff\" attr.type=\"string\"/>"
                    + "<key id=\"ron\" for=\"node\" attr.name=\"retry.retryOn\" attr.type=\"string\"/><graph id=")
                    .replace("<node id=\"personal\">", "<node id=\"personal\"><data key=\"rmax\">2</data><data key=\"rwait\">1000</data><data key=\"ron\">LlmPromptException</data>");
                failPersonalOnce.set(true);
                var start = post(server, "/v1/executions?mode=run", linked, retrying);
                assertEquals(202, start.statusCode(), start.body());
                assertTrue(firstPersonalFailure.await(5, TimeUnit.SECONDS));
                assertEquals(200, post(server, "/v1/personal/select", linked, json(Map.of("connection", accountC))).statusCode());
                assertTrue(completed.await(10, TimeUnit.SECONDS));
                assertEquals(List.of("access-A", "access-A", "access-A"), personalAttempts,
                        "retry and following agent must retain original admitted account despite browser selection");
                assertFalse(provider.inferenceBearers.contains("access-C"));
            }
        }
    }

    @Test void namedConversationalAgentUsesApprovedSourceDefinitionWithExplicitPersonalBillingAndRefusesWorkspace() throws Exception {
        var policy = new ai.ravenroot.api.runner.RunnerPolicy(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                new ai.ravenroot.api.runner.RunnerPolicy.Limits(Duration.ofSeconds(10), 1024, 1, 1024, 1024, 1024, 16384));
        var command = new ai.ravenroot.api.runner.AgentCommand("review", true, policy, Set.of("approved"));
        var definition = new ai.ravenroot.api.runner.AgentDefinition(new ai.ravenroot.api.runner.AgentDefinition.Reference("local", "reviewer", 1),
                "Approved source instructions", "runtime", "openai", Map.of("review", command), Set.of(), Set.of(), policy, Duration.ZERO, "review-result",
                new ai.ravenroot.api.runner.AgentDefinition.Budgets(2, 1, 1000, 128), Map.of());
        var completed = new CountDownLatch(1); var monitor = new ExecutionMonitor();
        monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) completed.countDown(); });
        try (var store = new SqliteExecutionStore(directory.resolve("named.db"), Clock.systemUTC());
             var engine = new PekkoExecutionEngine("personal-named-agent")) {
            var jobs = new ai.ravenroot.core.runner.RunnerJobService(store, Clock.systemUTC(), List.of(definition), List.of(), Map.of("local", policy));
            var registry = registry().withRunnerJobs(jobs)
                    .withPersonalConnectionAuthority((identity, requirements) -> personal.get().requireExecutionAuthority(identity, requirements))
                    .withPersonalDefinitionTenant(identity -> personal.get().definitionTenant(identity));
            var app = new DefaultRavenrootApplication(engine, monitor, registry, new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store, 2);
            try (var server = server(app)) {
                server.start(); install(server); String a = login(server, "A", "oaiapp_A");
                String named = graph(true).replace("<graph id=", "<key id=\"named\" for=\"node\" attr.name=\"agentDefinition\" attr.type=\"string\"/>"
                        + "<key id=\"command\" for=\"edge\" attr.name=\"command\" attr.type=\"string\"/>"
                        + "<key id=\"workspace\" for=\"node\" attr.name=\"workspaceRef\" attr.type=\"string\"/><graph id=")
                        .replace("<node id=\"agent\">", "<node id=\"agent\"><data key=\"named\">reviewer</data>")
                        .replace(edge("e3", "personal", "agent"), edge("e3", "personal", "agent").replace("</edge>", "<data key=\"command\">review</data></edge>"))
                        .replace(edge("e4", "agent", "end"), edge("e4", "agent", "end").replace(">continue</data>", ">approved</data>"));
                var unsupported = post(server, "/v1/executions?mode=run", a,
                        named.replace("<node id=\"agent\">", "<node id=\"agent\"><data key=\"workspace\">repository</data>"));
                assertEquals(409, unsupported.statusCode(), unsupported.body());
                assertEquals("personal-workspace-agent-unavailable", object(unsupported.body()).get("state"));
                assertEquals(0, effects.get()); assertTrue(provider.inferenceBearers.isEmpty());
                provider.responseText = "{\"outcome\":\"approved\",\"payload\":{\"summary\":\"reviewed\"}}";
                var start = post(server, "/v1/executions?mode=run", a, named);
                assertEquals(202, start.statusCode(), start.body()); assertTrue(completed.await(10, TimeUnit.SECONDS));
                assertEquals(List.of("access-A", "access-A"), personalAttempts);
                var result = get(server, "/v1/executions/" + object(start.body()).get("executionId"), a, true);
                assertEquals(200, result.statusCode()); assertTrue(result.body().contains("reviewed"));
                assertTrue(store.loadRunnerWorkspace(new ai.ravenroot.api.persistence.ExecutionKey(context(a, "read").tenantId(),
                        UUID.fromString((String) object(start.body()).get("processInstanceId")))).toCompletableFuture().join().isEmpty());
            }
        }
    }

    RavenrootServer server(DefaultRavenrootApplication app) throws Exception {
        return new RavenrootServer(app, new InetSocketAddress("127.0.0.1", 0), null,
                new DisabledLoopbackAuthenticator(), new DefaultAuthorizationService(event -> { }), AssistantService.fromEnvironment(Map.of()));
    }
    void install(RavenrootServer server) {
        var config = new PersonalOAuthConfiguration("loopback", URI.create(base(server) + "/auth/callback"), "", "",
                directory.resolve("connections"), directory.resolve("key"), Set.of("auth.openai.com", "api.openai.com"), "account-model");
        personal.set(new PersonalOAuthService(config, (pkg, tenant, ref) -> Optional.of(new SecretValue("system-key".toCharArray())), provider,
                (token, client, nonce) -> Map.of("sub", token, "email", token + "@example.invalid"), Clock.systemUTC()));
        server.installPersonalOAuth(personal.get());
    }
    BehaviorRegistry registry() throws Exception {
        var profile = new LlmProfile("openai", URI.create("https://api.openai.com/v1/chat/completions"), "account-model",
                Optional.of(new OutboundCredentialBinding("openai", "system-key")), 5000, 1024 * 1024, 8);
        LlmProfileResolver profiles = name -> Optional.of(profile);
        var promptConstructor = LlmPromptNodeBehavior.class.getDeclaredConstructor(LlmProfileResolver.class); promptConstructor.setAccessible(true);
        var agentConstructor = AgentNodeBehavior.class.getDeclaredConstructor(LlmProfileResolver.class); agentConstructor.setAccessible(true);
        List<NodeBehavior> nodes = List.of(promptConstructor.newInstance(profiles), agentConstructor.newInstance(profiles));
        NodePackage bundle = new NodePackage() {
            public String id() { return "ai.ravenroot.extensions.ai"; }
            public String version() { return "test"; }
            public String sdkContract() { return NodeSdk.CONTRACT; }
            public List<NodeBehavior> behaviors() { return nodes; }
        };
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("probe", message -> { effects.incrementAndGet(); return probe.get() == null ? CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())) : probe.get().handle(message); });
        NodePackages.register(registry, bundle, NodePackageServiceRegistry.builder().grant(bundle.id(), services()).build());
        return registry;
    }
    NodePackageServices services() {
        return new NodePackageServices() {
            public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP, NodePackageCapability.TOOL_AUTHORIZATION, NodePackageCapability.AGENT_RESOURCES); }
            public NodeCredentialService credentials() { return NodePackageServices.unavailable().credentials(); }
            public OutboundWebSocketService outboundWebSocket() { return NodePackageServices.unavailable().outboundWebSocket(); }
            public ToolCallAuthorizationService toolAuthorization() { return (message, tool, arguments) -> { throw new SecurityException("No effect tools granted"); }; }
            public AgentResourceService agentResources() { return new AgentResourceService() {
              public AgentResourceSession resume(ToolCallContinuationInput input, AgentResourceRequest request) { return admit(input.message(), request); }
              public AgentResourceSession admit(NodeMessage message, AgentResourceRequest request) { return new AgentResourceSession() {
                public AgentModelReservation reserveModelTurn(long ordinal) {
                    if (ordinal > 3) throw new SecurityException("turn budget");
                    return new AgentModelReservation() {
                        public long maximumOutputTokens() { return 128; }
                        public Duration maximumDuration() { return Duration.ofSeconds(5); }
                        public void dispatch() { }
                        public void release() { }
                        public void settle(Optional<Long> input, Optional<Long> output) { }
                        public void indeterminate() { }
                    };
                }
                public AgentResourceSession createChild(AgentChildResourceRequest request) { throw new SecurityException("No child grants"); }
                public void complete() { }
                public void cancel() { }
                public void suspend() { }
            }; } }; }
            public OutboundHttpService outboundHttp() { return (message, request) -> {
                try {
                    var binding = request.credential().orElseThrow();
                    String token;
                    if (binding.reference().equals("personal:openai")) {
                        personal.get().requireExecutionAuthority(message.security(), Set.of("openai"));
                        try (var credential = personal.get().resolveHttp("ai.ravenroot.extensions.ai", message.security(), request.destination(), binding.reference()).orElseThrow()) { token = new String(credential.copy()); }
                        personalAttempts.add(token);
                        if (failPersonalOnce.compareAndSet(true, false)) {
                            firstPersonalFailure.countDown();
                            return OutboundCall.failed(new NodePackageServiceException(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE));
                        }
                    } else token = "system-key";
                    var response = provider.send(HttpRequest.newBuilder(request.destination()).header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(request.body())).build(), HttpResponse.BodyHandlers.ofInputStream());
                    byte[] body; try (var input = response.body()) { body = input.readAllBytes(); }
                    return OutboundCall.completed(new OutboundHttpResponse(200, Map.of(), body));
                } catch (Exception failure) { return OutboundCall.failed(new NodePackageServiceException(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE)); }
            }; }
        };
    }
    String login(RavenrootServer server, String user, String client) throws Exception {
        return login(server, user, client, null);
    }
    String login(RavenrootServer server, String user, String client, String existing) throws Exception {
        var begin = post(server, "/v1/personal/sign-in", existing, "{}"); assertEquals(200, begin.statusCode(), begin.body());
        String cookie = begin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        String url = (String) object(begin.body()).get("authorizationUrl");
        String state = Arrays.stream(URI.create(url).getQuery().split("&")).filter(p -> p.startsWith("state=")).findFirst().orElseThrow().substring(6);
        var callback = get(server, "/auth/callback?code=" + user + "&client_id=" + client + "&state=" + state, cookie, false);
        assertEquals(303, callback.statusCode(), callback.body());
        return callback.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
    RequestContext context(String cookie, String request) {
        var headers = new com.sun.net.httpserver.Headers(); headers.set("Cookie", cookie);
        var p = personal.get().principal(headers, new DisabledLoopbackAuthenticator().authenticate(headers));
        return new RequestContext(request, p.subject(), PrincipalType.USER, p.issuer(), p.tenantId(), p.roles(), p.scopes());
    }
    HttpResponse<String> post(RavenrootServer server, String path, String cookie, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base(server) + path)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        request.header("X-Request-Id", "caller-controlled-same-request-id");
        request.header("X-Ravenroot-Owner", "forged-owner");
        request.header("Content-Type", body.startsWith("<") ? "application/graphml+xml" : "application/json");
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(RavenrootServer server, String path, String cookie, boolean privateContext) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base(server) + path)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        if (privateContext) request.header("X-Ravenroot-Execution-Context", "personal");
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static String base(RavenrootServer server) { return "http://127.0.0.1:" + server.port(); }
    static ByteArrayInputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static String json(Object value) { return PayloadJson.write(PayloadValue.fromJava(value, AssistantLimits.LIMITS)); }
    @SuppressWarnings("unchecked") static Map<String, Object> object(String text) { return (Map<String, Object>) PayloadJson.read(text.getBytes(StandardCharsets.UTF_8), AssistantLimits.LIMITS).toJava(); }
    static class AssistantLimits { static final PayloadLimits LIMITS = new PayloadLimits(2 * 1024 * 1024, 32, 100_000, 100_000, 1024 * 1024, 4096); }
    static String graph(boolean mixed) {
        return "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">" +
                "<key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/><key id=\"behavior\" for=\"node\" attr.name=\"behavior\" attr.type=\"string\"/>" +
                "<key id=\"provider\" for=\"node\" attr.name=\"provider\" attr.type=\"string\"/><key id=\"prompt\" for=\"node\" attr.name=\"prompt\" attr.type=\"string\"/>" +
                "<key id=\"instructions\" for=\"node\" attr.name=\"instructions\" attr.type=\"string\"/><key id=\"objective\" for=\"node\" attr.name=\"objective\" attr.type=\"string\"/>" +
                "<key id=\"credentialMode\" for=\"node\" attr.name=\"credentialMode\" attr.type=\"string\"/><key id=\"outcome\" for=\"edge\" attr.name=\"outcome\" attr.type=\"string\"/>" +
                "<graph id=\"shared\" edgedefault=\"directed\"><node id=\"start\"><data key=\"kind\">START</data></node><node id=\"error\"><data key=\"kind\">ERROR</data></node>" +
                "<node id=\"probe\"><data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">probe</data></node>" +
                "<node id=\"system\"><data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">llm-prompt</data><data key=\"provider\">openai</data><data key=\"prompt\">hello</data></node>" +
                (mixed ? "<node id=\"personal\"><data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">llm-prompt</data><data key=\"provider\">openai</data><data key=\"prompt\">hello</data><data key=\"credentialMode\">personal</data></node>" +
                        "<node id=\"agent\"><data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">agent</data><data key=\"provider\">openai</data><data key=\"instructions\">answer</data><data key=\"objective\">hello</data><data key=\"credentialMode\">personal</data></node>" :
                        "<node id=\"finished\"><data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">system-finished</data></node>") +
                "<node id=\"end\"><data key=\"kind\">END</data></node>" + edge("e0", "start", "probe") + edge("e1", "probe", "system") +
                (mixed ? edge("e2", "system", "personal") + edge("e3", "personal", "agent") + edge("e4", "agent", "end") : edge("e2", "system", "finished") + edge("e3", "finished", "end")) + "</graph></graphml>";
    }
    static String edge(String id, String source, String target) { return "<edge id=\"" + id + "\" source=\"" + source + "\" target=\"" + target + "\"><data key=\"outcome\">continue</data></edge>"; }
}
