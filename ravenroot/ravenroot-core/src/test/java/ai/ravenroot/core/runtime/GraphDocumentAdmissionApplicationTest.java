package ai.ravenroot.core.runtime;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphMlParseException;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphDocumentAdmissionApplicationTest {

    @Test
    void everyDirectGraphLifecycleReadsOnlyLimitPlusOneBeforeRefusing() {
        int maximum = 16;
        var limits = limits(maximum);
        var application = new DefaultRavenrootApplication(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                null, 8, UnknownBehaviorPolicy.passThrough(), limits);
        try {
            assertBounded(maximum, input -> application.activateDeployment(
                    TestIdentities.TENANT_A, DeploymentId.of("bounded"), input));
            assertBounded(maximum, input -> application.registerLocalDeployment(
                    TestIdentities.TENANT_A, "bounded", input));
            assertBounded(maximum, input -> application.startSourceSession(
                    TestIdentities.TENANT_A, "bounded", input));
        } finally {
            application.close();
        }
    }

    private static void assertBounded(int maximum, Submission submission) {
        var input = new CountingInputStream(new byte[maximum + 100]);
        var rejection = assertThrows(GraphMlParseException.class, () -> submission.submit(input));
        assertEquals(GraphMlParseException.Reason.DOCUMENT_TOO_LARGE, rejection.reason());
        assertEquals(maximum + 1, input.readCount,
                "the application must not pre-buffer bytes beyond the proof that the limit was exceeded");
    }

    private static GraphExecutionLimits limits(int maximum) {
        var defaults = GraphExecutionLimits.DEFAULTS;
        var graph = defaults.graphMl();
        var graphMl = new GraphMlLimits(maximum, graph.maxNodes(), graph.maxEdges(), graph.maxProperties(),
                graph.maxDepth(), graph.maxStringLength(), graph.maxKeys(), graph.maxElements(),
                graph.maxAttributes(), graph.maxNamespaceDeclarations());
        return new GraphExecutionLimits(graphMl, defaults.payload(), defaults.maxFanOut(),
                defaults.maxResidentActors(), defaults.maxLiveActorsPerTraversal(),
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                defaults.maxTraversalSteps(), defaults.maxAmplifiedDeliveries(),
                defaults.maxCumulativePayloadBytes(), defaults.maxRecoveryDeliveriesPerAttempt());
    }

    @FunctionalInterface
    private interface Submission {
        void submit(java.io.InputStream input);
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private int readCount;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            int read = super.read(target, offset, length);
            if (read > 0) readCount += read;
            return read;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) readCount++;
            return value;
        }
    }
}
