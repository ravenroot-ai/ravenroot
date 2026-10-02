package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedExecutionCommandTest {
    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
    private static final String PREDECESSOR = "00000000-0000-0000-0000-000000000002";

    @Test
    void previewGeneratesAKeyAndForwardsTheExplicitDecision() {
        var backend = new StubBackend();
        var output = new ByteArrayOutputStream();
        var cli = new RavenrootCli(backend, new PrintStream(output), System.err);

        assertEquals(0, cli.run("derive-preview", SOURCE, "B", PREDECESSOR, "repair",
                "safe-to-repeat", "--authorize-effects"));

        assertNotNull(backend.key);
        assertFalse(backend.key.isBlank());
        assertEquals("safe-to-repeat", backend.decision);
        assertTrue(backend.authorize);
        assertTrue(output.toString().contains("idempotency-key=" + backend.key));
        assertTrue(output.toString().contains("possible-scope-node-ids=B,C"));
    }

    @Test
    void startHonorsAdvancedKeyOverride() {
        var backend = new StubBackend();
        var output = new ByteArrayOutputStream();
        var cli = new RavenrootCli(backend, new PrintStream(output), System.err);

        assertEquals(0, cli.run("derive", SOURCE, "B", PREDECESSOR, "repair", "--key=known-key"));

        assertEquals("known-key", backend.key);
        assertTrue(output.toString().contains("process-instance-id=derived-process"));
    }

    private static final class StubBackend implements CliBackend {
        private String key;
        private String decision;
        private boolean authorize;

        @Override
        public DerivedPreviewView previewDerived(String source, String node, String predecessor, String key,
                String reason, String decision, boolean authorize) {
            capture(key, decision, authorize);
            return new DerivedPreviewView(true, List.of(), List.of(predecessor), List.of("B", "C"),
                    List.of(), List.of("B"), "graph", "manifest");
        }

        @Override
        public DerivedStartView startDerived(String source, String node, String predecessor, String key,
                String reason, String decision, boolean authorize) {
            capture(key, decision, authorize);
            return new DerivedStartView("derived-process", "derived-traversal", "graph");
        }

        private void capture(String value, String repeatabilityDecision, boolean effects) {
            key = value;
            decision = repeatabilityDecision;
            authorize = effects;
        }

        @Override public StatusView status() { throw new UnsupportedOperationException(); }
        @Override public RuntimeView runtime() { throw new UnsupportedOperationException(); }
        @Override public List<NodeTypeView> nodeTypes() { throw new UnsupportedOperationException(); }
        @Override public InspectView inspect(byte[] graphMl) { throw new UnsupportedOperationException(); }
        @Override public RunView run(byte[] graphMl, String payload) { throw new UnsupportedOperationException(); }
        @Override public ResultView result(String executionId) { throw new UnsupportedOperationException(); }
        @Override public List<LiveView> live() { throw new UnsupportedOperationException(); }
        @Override public InventoryListing inventory() { throw new UnsupportedOperationException(); }
        @Override public TraversalListing traversals(String processInstanceId) { throw new UnsupportedOperationException(); }
        @Override public CancelView cancel(String traversalId) { throw new UnsupportedOperationException(); }
        @Override public DrainView drain() { throw new UnsupportedOperationException(); }
        @Override public List<CredentialView> credentials() { throw new UnsupportedOperationException(); }
        @Override public CredentialView addCredential(String label, String scheme, String username, String value) {
            throw new UnsupportedOperationException();
        }
        @Override public List<DeploymentView> deployments() { throw new UnsupportedOperationException(); }
        @Override public DeploymentView registerDeployment(String id, byte[] graphMl) { throw new UnsupportedOperationException(); }
        @Override public DeploymentView deployment(String id) { throw new UnsupportedOperationException(); }
        @Override public DeploymentView startDeployment(String id) { throw new UnsupportedOperationException(); }
        @Override public DeploymentView stopDeployment(String id) { throw new UnsupportedOperationException(); }
        @Override public DeploymentView restartDeployment(String id) { throw new UnsupportedOperationException(); }
        @Override public DeploymentView undeployDeployment(String id) { throw new UnsupportedOperationException(); }
    }
}
