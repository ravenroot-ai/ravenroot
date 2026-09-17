package ai.ravenroot.api.runner;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerSessionIsolationTest {
    @Test void workspaceSharingNeverMergesAgentsAndLaterTraversalsKeepTheSameAgentNamespace() {
        var original = job(); var shared = UUID.randomUUID();
        var first = new RunnerAssignment(1, shared, original);
        assertEquals(original.definition().reference().sessionId(original.identity().execution().processInstanceId()), first.agentSessionId());
        var later = new RunnerAssignment(1, UUID.randomUUID(), job(identity(original.identity().execution())));
        assertEquals(first.agentSessionId(), later.agentSessionId());
        assertNotEquals(first.agentSessionId(), new RunnerAssignment(1, shared, job()).agentSessionId());
        var approved = definition();
        for (var reference : java.util.List.of(new AgentDefinition.Reference(TENANT, "reviewer", 1),
                new AgentDefinition.Reference(TENANT, approved.reference().name(), 2))) {
            var other = new AgentDefinition(reference, approved.instructions(), approved.runtimeProfile(), approved.modelProfile(),
                    approved.commands(), approved.skills(), approved.runnerRequirements(), approved.policy(), approved.workspaceRetention(), approved.outputSchema());
            var job = RunnerJob.accept(identity(original.identity().execution()), other, "implement", policy(), runner(), EMPTY, NOW, NOW.plusSeconds(600));
            assertNotEquals(first.agentSessionId(), new RunnerAssignment(1, shared, job).agentSessionId());
        }
    }
}
