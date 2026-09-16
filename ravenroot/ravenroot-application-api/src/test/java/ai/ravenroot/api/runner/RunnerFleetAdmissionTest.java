package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerFleetAdmissionTest {
    @org.junit.jupiter.api.Test void namedReuseRequiresQuiescentPriorOwnerAndAUniqueFencedGeneration() {
        var original = WorkspaceResourceTest.profile(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE);
        var named = new WorkspaceProfile(original.reference(), WorkspaceProfile.Scope.NAMED, original.runtimeLifecycle(), original.runnerPool(),
                original.runtimeProfile(), original.policy(), original.capacity(), original.retention(), original.completionPolicy(), original.allowedAgents());
        var id = WorkspaceResource.namedWorkspaceId(TENANT, named.reference().name());
        java.util.function.BiFunction<Long, WorkspaceResource.State, RunnerWorkspaceState> resource = (generation, state) -> {
            var item = new WorkspaceResource("tree", id, named, RUNNER, state, null, null, false, NOW, generation);
            return new RunnerWorkspaceState(new ExecutionKey(TENANT, UUID.randomUUID()), id, RUNNER, Map.of(), null, Map.of("tree", item));
        };
        var first = resource.apply(1L, WorkspaceResource.State.READY);
        var second = resource.apply(2L, WorkspaceResource.State.OPENING);
        assertThrows(IllegalStateException.class, () -> RunnerFleetAdmission.validate(List.of(first, second)));
        assertThrows(IllegalStateException.class, () -> RunnerFleetAdmission.validate(List.of(resource.apply(1L, WorkspaceResource.State.CLOSED),
                resource.apply(1L, WorkspaceResource.State.OPENING))));
        assertDoesNotThrow(() -> RunnerFleetAdmission.validate(List.of(resource.apply(1L, WorkspaceResource.State.CLOSED), second)));
        assertThrows(IllegalStateException.class, () -> RunnerFleetAdmission.validate(List.of(resource.apply(1L, WorkspaceResource.State.READY),
                resource.apply(2L, WorkspaceResource.State.RELEASING))));
        assertDoesNotThrow(() -> RunnerFleetAdmission.validate(List.of(resource.apply(1L, WorkspaceResource.State.RELEASING), second)),
                "an obsolete owner's cleanup releases only its control-plane references, never the newer owner's physical filesystem");
    }
    @ParameterizedTest @EnumSource(RunnerFleetLimits.Scope.class)
    void everyScopeIndependentlyLimitsRetainedResourcesUntilPhysicalCleanup(RunnerFleetLimits.Scope scope) {
        var original = WorkspaceResourceTest.profile(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE);
        var ceilings = new EnumMap<RunnerFleetLimits.Scope, RunnerFleetLimits.Ceiling>(RunnerFleetLimits.Scope.class);
        for (var item : RunnerFleetLimits.Scope.values()) ceilings.put(item, new RunnerFleetLimits.Ceiling(20, 20, 20, 100_000_000));
        ceilings.put(scope, new RunnerFleetLimits.Ceiling(20, 20, 1, 100_000_000));
        var profile = new WorkspaceProfile(original.reference(), original.workspaceScope(), original.runtimeLifecycle(), original.runnerPool(),
                original.runtimeProfile(), original.policy(), original.capacity(), original.retention(), original.completionPolicy(),
                original.allowedAgents(), new RunnerFleetLimits(ceilings));
        var first = state(profile, WorkspaceResource.State.READY);
        var second = state(profile, WorkspaceResource.State.RELEASING);
        assertDoesNotThrow(() -> RunnerFleetAdmission.validate(List.of(first)));
        assertThrows(IllegalStateException.class, () -> RunnerFleetAdmission.validate(List.of(first, second)));
        assertDoesNotThrow(() -> RunnerFleetAdmission.validate(List.of(first, state(profile, WorkspaceResource.State.RELEASED))));
    }
    private static RunnerWorkspaceState state(WorkspaceProfile profile, WorkspaceResource.State status) {
        var resource = new WorkspaceResource("tree", UUID.randomUUID(), profile, RUNNER, status, null, null, false, NOW);
        return new RunnerWorkspaceState(new ExecutionKey(TENANT, UUID.randomUUID()), resource.workspaceId(), RUNNER, Map.of(), null, Map.of("tree", resource));
    }
}
