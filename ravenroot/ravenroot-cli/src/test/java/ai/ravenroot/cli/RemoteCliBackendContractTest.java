package ai.ravenroot.cli;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.cli.remote.RemoteBackend;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * The remote half of {@link CliBackendContract}, over a real, running {@link RavenrootServer} and
 * a real {@link RemoteBackend} -- the same assertions the embedded subclass runs.
 *
 * <p><strong>What this proves, and what the gap was.</strong> Every assertion inherited
 * from {@link CliBackendContract} passing on both subclasses shows the two transports agree on status
 * codes, identifiers, inspection results and the read-back result's shape. Previously it showed
 * nothing about whether they <em>execute behaviors</em> alike: {@code GRAPH}, the fixture shared with
 * the embedded subclass, has no {@code BEHAVIOR} node at all -- only {@code start -> end} -- so a
 * transport that silently never requested real execution was indistinguishable here from one that
 * did, and {@link RemoteBackend#run} was silently that transport. It sent no {@code mode} parameter,
 * so every remote submission ran under the server's default {@code TEST_PASSTHROUGH} while the
 * embedded backend ran under {@code STANDARD}.</p>
 *
 * <p>{@code RemoteBackend#run} now states {@code mode=run}, and
 * {@code CliBackendContract#bothTransportsExecuteBehaviorsAndReachTheTerminal} runs a graph with a
 * decision node whose reached-node set differs between the two policies -- so this subclass now fails
 * if the divergence returns, which is the property the sentence above could not previously claim. The
 * measured before-state, kept because a fixed defect with no record of its symptom is one that gets
 * reintroduced: remote answered {@code visitedNodes=[greeting, is-ravenroot, start]} and no payload,
 * embedded the full five nodes and {@code "Hello Ravenroot"}, both {@code COMPLETED},
 * {@code degraded=false}.</p>
 */
class RemoteCliBackendContractTest extends CliBackendContract {
    /** See {@code EmbeddedCliBackendContractTest}'s own field for why this must be non-zero --
     * the two-argument constructor this class used before defaults {@code maxActiveDeployments} to 0
     * (the deployment-admission contract), which would refuse every deployment {@code start} this contract now issues. */
    private static final int TEST_MAX_ACTIVE_DEPLOYMENTS = 8;

    @TempDir
    Path uiDirectory;

    private PekkoExecutionEngine engine;
    private RavenrootServer server;

    @BeforeEach
    void setUp() {
        engine = new PekkoExecutionEngine("dual-transport-remote-" + UUID.randomUUID());
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null,
                TEST_MAX_ACTIVE_DEPLOYMENTS);
        server = new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                new DisabledLoopbackAuthenticator());
        server.start();
        // DisabledLoopbackAuthenticator accepts any Authorization header (loopback-only trust) -- the
        // token's value is irrelevant here, only its presence, so a fixed placeholder is honest.
        backend = new RemoteBackend(URI.create("http://localhost:" + server.port() + "/"),
                "dual-transport-placeholder-token", Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        engine.close();
    }
}
