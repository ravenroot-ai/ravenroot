package ai.ravenroot.api.runner;

import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.OpaquePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"plan", "read", "research", "review", "summarize"})
    void readOnlyVocabularyCannotAcquireWritesThroughAnOverprivilegedPolicy(String command) {
        var read = new AgentCommand(command, true, policy(), Set.of("answered"));
        var effective = RunnerPolicy.effective(policy(), policy(), read, policy());
        assertEquals(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), effective.capabilities());
        assertTrue(effective.tools().isEmpty());
        assertTrue(effective.egress().isEmpty());
        assertTrue(effective.secrets().isEmpty());
        assertTrue(effective.mounts().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCommand(command, false, policy(), Set.of("answered")));
    }

    @Test
    void everyAuthorityLayerCanRemoveCapabilitiesAndReduceEveryLimit() {
        var limits = new RunnerPolicy.Limits(Duration.ofSeconds(10), 4, 2, 3, 2, 1, 1);
        var restricted = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ,
                RunnerPolicy.Capability.TOOL_CALL), Set.of("search"), Set.of(), Set.of(), Set.of(), limits);
        for (int layer = 0; layer < 4; layer++) {
            var policies = new RunnerPolicy[] {policy(), policy(), policy(), policy()};
            policies[layer] = restricted;
            var effective = RunnerPolicy.effective(policies[0], policies[1],
                    new AgentCommand("implement", false, policies[2], Set.of("completed")), policies[3]);
            assertEquals(restricted, effective);
        }
    }

    @Test
    void grantsUseIntersectionAndNeverUnionOrWildcards() {
        var first = policy();
        var runner = new RunnerPolicy(first.capabilities(), Set.of("search", "unapproved"), Set.of("other"),
                Set.of("other"), Set.of("other"), first.limits());
        var result = RunnerPolicy.effective(first, first,
                new AgentCommand("implement", false, first, Set.of("completed")), runner);
        assertEquals(Set.of("search"), result.tools());
        assertTrue(result.egress().isEmpty());
        assertTrue(result.secrets().isEmpty());
        assertTrue(result.mounts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RunnerPolicy(first.capabilities(), Set.of("*"),
                Set.of(), Set.of(), Set.of(), first.limits()));
    }

    @Test
    void policyAndDefinitionSnapshotsAreImmutableAndDiagnosticsHideInstructions() {
        var capabilities = new HashSet<>(policy().capabilities());
        var tools = new HashSet<>(Set.of("search"));
        var policy = new RunnerPolicy(capabilities, tools, Set.of(), Set.of(), Set.of(), policy().limits());
        capabilities.clear();
        tools.clear();
        assertFalse(policy.capabilities().isEmpty());
        assertEquals(Set.of("search"), policy.tools());
        assertThrows(UnsupportedOperationException.class, () -> policy.tools().clear());
        assertFalse(definition().toString().contains(definition().instructions()));
        assertThrows(UnsupportedOperationException.class, () -> definition().commands().clear());
    }

    @Test
    void customCommandsAreExplicitAndEngineCommandCompatibilityIsUnchanged() {
        var extension = new AgentCommand("triage", true, policy(), Set.of("triaged"));
        assertEquals("triage", extension.name());
        assertEquals(NodeCommand.PROCESS, NodeCommand.parse("continue"));
        assertEquals(NodeCommand.PASSTHROUGH, NodeCommand.parse("passthrough"));
        for (String name : Set.of("process", "continue", "passthrough")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AgentCommand(name, false, policy(), Set.of("completed")));
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentCommand("triage", false, policy(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> RunnerJob.accept(identity(), definition(), "triage",
                policy(), runner(), EMPTY, NOW, NOW.plusSeconds(20)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../host", "/root", "${secret}", "shell;write", "https://host", "*"})
    void graphLikeAuthorityStringsCannotBecomeSymbolicGrants(String grant) {
        assertThrows(IllegalArgumentException.class, () -> new RunnerRegistration(1, TENANT, RUNNER, grant,
                Set.of("sandboxed"), policy()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentDefinition.Reference(TENANT, grant, 1));
    }

    @Test
    void definitionVersionAndProtocolVersionAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition.Reference(TENANT, "agent", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RunnerRegistration(2, TENANT, RUNNER, "sandboxed", Set.of(), policy()));
        var d = definition();
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition(d.reference(), d.instructions(),
                d.runtimeProfile(), d.modelProfile(), Map.of("read", d.commands().get("implement")), d.skills(),
                d.runnerRequirements(), d.policy(), d.workspaceRetention(), d.outputSchema()));
    }

    @Test
    void missingWorkspaceReadFailsClosedAndPayloadCannotGrantAuthority() {
        var none = new RunnerPolicy(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), policy().limits());
        assertThrows(IllegalArgumentException.class, () -> RunnerPolicy.effective(none, policy(),
                new AgentCommand("implement", false, policy(), Set.of("completed")), policy()));
        var payload = OpaquePayload.of("{\"command\":\"implement\",\"capabilities\":[\"PROCESS_EXECUTE\"]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");
        var job = RunnerJob.accept(identity(), definition(TENANT, "read", true), "read", policy(), runner(),
                payload, NOW, NOW.plusSeconds(30));
        assertEquals(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), job.authority().capabilities());
        assertEquals("read", job.command().name());
    }
}
