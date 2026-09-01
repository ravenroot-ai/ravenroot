package ai.ravenroot.server;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.programming.graalvm.GraalVmProgramRuntime;
import ai.ravenroot.programming.graalvm.GraalVmWorkerMain;
import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof: choosing Python, the whole
 * five-step workbench pipeline completes over the real HTTP routes, and a {@code program} node
 * carrying the resulting {@code artifactId} genuinely executes that artifact through a live GraalVM
 * Python worker -- not a stub {@code ProgramRuntime}, and not a captured-and-replayed recording.
 *
 * <h2>Why a real subprocess and not {@code ravenroot-programming-graalvm}'s own {@code FakeSupervisor}</h2>
 * <p>{@code FakeSupervisor} is package-private test-only plumbing that lives in that module's test
 * sources, not reachable from here. {@link LiveGraalVmWorkerSupervisor} below is this test's own
 * minimal {@link SandboxSupervisorLauncher}: it spawns the real {@link GraalVmWorkerMain} the same
 * way {@code FakeSupervisor}'s own live branch and {@code RealWorkerRun} do, and wraps its stdout in
 * the same {@code SandboxSupervisorProtocol}
 * envelope a trusted supervisor would. It does not soften or bypass anything in
 * {@link GraalVmProgramRuntime} -- {@code verifyArtifact}, the wire protocol and the worker's own
 * language check all run exactly as they do in production. What it does not attempt is OS-level
 * process/resource sandboxing, because the real supervisor binary that would enforce that is
 * deliberately not part of this repository (see {@code docs/architecture/python-programmable-nodes.md});
 * that isolation question belongs to SEC-11, not what this test is proving.
 *
 * <h2>Why {@code BehaviorRegistry} rather than a submitted GraphML traversal</h2>
 * <p>{@code ProgramNodeAdmissionTest} already establishes, and this test reuses, that
 * {@code BehaviorRegistry.standard(environment).create(programNode).handle(message)} is the exact
 * mechanism a graph's {@code program} node runs through -- {@code ProgramNodeBehaviorFactory} calls
 * {@code ArtifactRegistry#admitForExecution} and then {@code runtime.execute(admission, request)},
 * with no additional step a full graph traversal would add on this path. Driving it directly, against
 * the very {@link InMemoryArtifactRegistry} and {@link GraalVmProgramRuntime} instances the HTTP
 * pipeline above just used, proves the same fact a submitted-and-polled GraphML execution would with
 * far less incidental machinery between the assertion and the thing being asserted.
 */
class PythonProgramNodeLiveExecutionTest {
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void choosingPythonTheFiveStepPipelineCompletesAndAProgramNodeExecutesTheResultForReal() throws Exception {
        var artifacts = new InMemoryArtifactRegistry(artifact -> { }); // SEC-12: provenance is not under test here
        var runtime = new GraalVmProgramRuntime(new LiveGraalVmWorkerSupervisor(), policy());
        var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(), artifacts,
                runtime, ignored -> Optional.empty(),
                ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                OutboundHttpPolicy.disabled());

        // The source is read from the runtime's own declared catalog, exactly as the
        // editor's workbench would read it -- never a Python snippet authored by this test.
        String pythonSource = runtime.supportedLanguages().stream()
                .filter(language -> "python".equals(language.id())).findFirst()
                .map(ProgramLanguageDescriptor::exampleSource)
                .orElseThrow(() -> new AssertionError("GraalVmProgramRuntime no longer declares python"));

        try (var engine = new PekkoExecutionEngine("python-program-node-e2e");
             var server = new RavenrootServer(
                     new DefaultRavenrootApplication(engine, new ExecutionMonitor(), environment),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, lifecycleAuthenticator())) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port() + "/v1/program-artifacts";

            // Step 1: Create.
            var created = client.send(HttpRequest.newBuilder(
                            URI.create(base + "?language=python&name=python-echo"))
                    .header("Authorization", "Bearer creator")
                    .POST(HttpRequest.BodyPublishers.ofString(pythonSource)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"language\":\"python\""), created.body());
            String id = created.body().split("\"id\":\"")[1].split("\"")[0];

            // Step 2: Validate.
            assertEquals(200, post(client, base + "/" + id + "/validate", "creator").statusCode());

            // Step 3: Test.
            var tested = post(client, base + "/" + id + "/test?payload=hello%20ravenroot", "creator");
            assertEquals(200, tested.statusCode(), tested.body());
            assertTrue(tested.body().contains("\"value\":\"hello ravenroot\""), tested.body());

            // Step 4: Approve (dual control: the creator cannot approve its own artifact).
            assertEquals(403, post(client, base + "/" + id + "/approve?reason=self", "creator").statusCode());
            assertEquals(200, post(client, base + "/" + id + "/approve?reason=reviewed", "approver").statusCode());

            // Step 5: Activate.
            assertEquals(200, post(client, base + "/" + id + "/activate", "approver").statusCode());

            // A `program` node carrying this artifactId executes it,
            // through the real GraalVM Python worker, via the exact mechanism ProgramNodeBehaviorFactory
            // uses -- not the HTTP test route, and not a second stub runtime.
            var node = new GraphNode("program-node", NodeKind.BEHAVIOR, "program", Map.of(
                    "language", "python", "source", pythonSource, "artifactId", id));
            var message = new NodeMessage(
                    new SecurityContext("req-e2e", "local", "graph-author", PrincipalType.USER, "test-issuer"),
                    UUID.randomUUID(), UUID.randomUUID(), node.id(), "hello from a graph", Map.of());
            NodeResult result = BehaviorRegistry.standard(environment).create(node).orElseThrow()
                    .handle(message).toCompletableFuture().get();

            assertEquals(Map.of("value", "hello from a graph"), result.payload());
            assertEquals(id, result.attributes().get("program.artifact"));
        }
    }

    private static SandboxPolicy policy() {
        Duration deadline = Duration.ofSeconds(150);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new SandboxPolicy(deadline, Math.toIntExact(deadline.toMillis()), 128, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-e2e-test-id", java,
                "jre-e2e-test-id");
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Mirrors {@code RavenrootServerTest#lifecycleAuthenticator}: "creator" may create/validate/test its
     * own work; "approver" carries {@code APPROVER} and may approve/activate someone else's. */
    private static RequestAuthenticator lifecycleAuthenticator() {
        return headers -> {
            boolean approver = "Bearer approver".equals(headers.getFirst("Authorization"));
            return new AuthenticatedPrincipal(approver ? "bob" : "alice", AuthenticatedPrincipal.Type.USER,
                    "issuer", "local",
                    Set.of(approver ? Role.APPROVER : Role.DEVELOPER),
                    approver
                            ? Set.of("ravenroot.artifact.read", "ravenroot.artifact.approve",
                            "ravenroot.artifact.activate", "ravenroot.artifact.retire")
                            : Set.of("ravenroot.artifact.read", "ravenroot.artifact.manage"));
        };
    }

    /**
     * Test-only {@link SandboxSupervisorLauncher} that spawns a genuine {@link GraalVmWorkerMain}
     * child JVM and speaks the real supervisor wire framing around its stdout. See the class Javadoc
     * for why this is legitimate plumbing rather than a stand-in for the evidence under test.
     */
    private static final class LiveGraalVmWorkerSupervisor implements SandboxSupervisorLauncher {
        private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
        private static final int SUPERVISOR_MAGIC = 0x52525331;

        @Override
        public void verifyCapability() {
            // Nothing to verify: this double IS the capability, unconditionally, for this test only.
        }

        @Override
        public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException {
            Process process = new ProcessBuilder(JAVA.toString(), "-cp", System.getProperty("java.class.path"),
                    GraalVmWorkerMain.class.getName()).start();
            return new LiveSession(process);
        }

        private static final class LiveSession implements SandboxSupervisorSession {
            private final Process process;
            private volatile byte[] control = new byte[0];

            private LiveSession(Process process) {
                this.process = process;
            }

            @Override
            public OutputStream workerInput() {
                return process.getOutputStream();
            }

            @Override
            public InputStream supervisorControl() {
                return new ByteArrayInputStream(control);
            }

            @Override
            public InputStream diagnostics() {
                return process.getErrorStream();
            }

            @Override
            public void terminate(SandboxTermination termination) {
                process.destroyForcibly();
            }

            @Override
            public SandboxOutcome await(Duration remaining) throws Exception {
                if (!process.waitFor(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS)) {
                    return SandboxOutcome.DEADLINE_EXCEEDED;
                }
                byte[] worker = process.getInputStream().readAllBytes();
                var envelope = new ByteArrayOutputStream();
                var data = new DataOutputStream(envelope);
                data.writeInt(SUPERVISOR_MAGIC);
                data.writeInt(SandboxPolicy.PROTOCOL_VERSION);
                data.writeByte(SandboxOutcome.COMPLETED.ordinal());
                data.writeInt(worker.length);
                data.write(worker);
                data.flush();
                control = envelope.toByteArray();
                return SandboxOutcome.COMPLETED;
            }

            @Override
            public void close() {
                process.destroyForcibly();
            }
        }
    }
}
