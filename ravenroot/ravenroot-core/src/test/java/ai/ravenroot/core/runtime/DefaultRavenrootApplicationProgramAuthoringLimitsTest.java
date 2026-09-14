package ai.ravenroot.core.runtime;

import ai.ravenroot.api.programming.ProgramAuthoringLimits;
import ai.ravenroot.api.programming.ProgramBuildRequest;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRavenrootApplicationProgramAuthoringLimitsTest {
    @Test
    void customLimitsRefuseCreateAndSingleBuildBeforeArtifactMutation() {
        var artifacts = new InMemoryArtifactRegistry();
        var application = application(artifacts);

        assertThrows(IllegalArgumentException.class,
                () -> application.createProgramArtifact("javascript", "€€", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> application.buildProgramArtifact("node-a", "tenant-a", "javascript", "€€",
                        Map.of(), false, Map.of()));

        assertTrue(artifacts.list().isEmpty(), "a refused source must not create or reserve an artifact");
    }

    @Test
    void customBatchLimitRefusesBeforeDurableBuildReservation() {
        var artifacts = new InMemoryArtifactRegistry();
        var application = application(artifacts);
        var programs = List.of(
                new ProgramBuildRequest("one", "javascript", "ok", Map.of()),
                new ProgramBuildRequest("two", "javascript", "ok", Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> application.startProgramBuild("tenant-a", programs, false, Map.of()));

        assertEquals(List.of(), artifacts.listIncompleteBuilds(),
                "a refused batch must not reserve a durable build");
        assertTrue(artifacts.list().isEmpty(), "a refused batch must not create artifacts");
    }

    private static DefaultRavenrootApplication application(InMemoryArtifactRegistry artifacts) {
        return new DefaultRavenrootApplication(null, new ExecutionMonitor(), new BehaviorRegistry(), artifacts,
                new DisabledProgramRuntime(), new ProgramAuthoringLimits(4, 256, 1));
    }
}
