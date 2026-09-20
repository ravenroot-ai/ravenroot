package ai.ravenroot.api.runner;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerCodecTest {
    @Test void existingProtocolPayloadBoundaryIsDistinctFromConfiguredJobCapacity() {
        var limits = definition().policy().limits();
        for (int bytes : new int[] {4096, 65536, RunnerCodec.MAX_PAYLOAD_BYTES}) {
            var configured = new RunnerPolicy.Limits(limits.wallTime(), limits.memoryBytes(), limits.processes(),
                    limits.workspaceBytes(), limits.artifactBytes(), limits.logBytes(), bytes);
            assertEquals(bytes, configured.payloadBytes());
            var result = new RunnerResult("completed", ai.ravenroot.api.persistence.OpaquePayload.of(
                    new byte[bytes], "application/octet-stream"), java.util.List.of(), UUID.randomUUID());
            assertEquals(result, RunnerCodec.result(RunnerCodec.result(result)));
        }
        assertThrows(IllegalArgumentException.class, () -> new RunnerPolicy.Limits(limits.wallTime(),
                limits.memoryBytes(), limits.processes(), limits.workspaceBytes(), limits.artifactBytes(),
                limits.logBytes(), RunnerCodec.MAX_PAYLOAD_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new RunnerResult("completed",
                ai.ravenroot.api.persistence.OpaquePayload.of(new byte[RunnerCodec.MAX_PAYLOAD_BYTES + 1],
                        "application/octet-stream"), java.util.List.of(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> RunnerCodec.result(new byte[RunnerCodec.MAX_BYTES + 1]));
    }
    @Test void workspaceVersionFourReadsLegacyVersionsWithoutInventingTerminationTime() throws Exception {
        var job = job();
        var state = new RunnerWorkspaceState(job.identity().execution(), UUID.randomUUID(), RUNNER,
                Map.of(job.identity().runnerJobId(), new RunnerWorkspaceState.Entry(job, EMPTY)));
        byte[] current = RunnerCodec.workspace(state);
        // Version three has no per-job Kubernetes observation boolean. Preserve its budgeted
        // definition and remove exactly that one field, rather than pretending v4 is v3.
        int physicalOffset = current.length - 32 - 6;
        var versionThreeBody = new java.io.ByteArrayOutputStream();
        versionThreeBody.write(current, 0, physicalOffset);
        versionThreeBody.write(current, physicalOffset + 1, current.length - physicalOffset - 1);
        byte[] versionThree = versionThreeBody.toByteArray();
        java.nio.ByteBuffer.wrap(versionThree).putInt(0x52524a33);
        byte[] versionThreeDigest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(versionThree, versionThree.length - 32));
        System.arraycopy(versionThreeDigest, 0, versionThree, versionThree.length - 32, 32);
        assertArrayEquals(current, RunnerCodec.workspace(RunnerCodec.workspace(versionThree)));
        // Old definitions ended at outputSchema. Remove the new four budget scalars and empty
        // skill-body map from this exact embedded definition before constructing the old envelope.
        byte[] encodedDefinition = RunnerCodec.definition(job.definition());
        byte[] body = java.util.Arrays.copyOfRange(encodedDefinition, 4, encodedDefinition.length - 32);
        int embedded = -1;
        for (int offset = 4; offset <= current.length - body.length; offset++) {
            if (java.util.Arrays.equals(body, java.util.Arrays.copyOfRange(current, offset, offset + body.length))) {
                embedded = offset; break;
            }
        }
        assertTrue(embedded >= 4);
        var legacyBody = new java.io.ByteArrayOutputStream();
        legacyBody.write(current, 0, embedded + body.length - 24);
        legacyBody.write(current, embedded + body.length, current.length - embedded - body.length);
        current = legacyBody.toByteArray();
        // This single legacy entry has null workspace-node/command fields (two bytes),
        // no termination time (one byte), and an empty resource map (four bytes).
        // The new physical-observation boolean adds one byte to that tail (eight total).
        for (int version : new int[]{1, 2}) {
        byte[] legacy = java.util.Arrays.copyOf(current, current.length - (version == 1 ? 8 : 7));
        java.nio.ByteBuffer.wrap(legacy).putInt(version == 1 ? 0x52524a31 : 0x52524a32);
        int payloadLength = legacy.length - 32;
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(legacy, payloadLength));
        System.arraycopy(digest, 0, legacy, payloadLength, digest.length);
        var decoded = RunnerCodec.workspace(legacy);
        assertEquals(AgentDefinition.Budgets.LEGACY, decoded.jobs().get(job.identity().runnerJobId()).job().definition().budgets());
        assertNull(decoded.processTerminalAt());
        assertEquals(job.identity(), decoded.jobs().get(job.identity().runnerJobId()).job().identity());
        var terminal = new RunnerWorkspaceState(state.execution(), state.workspaceId(), state.runnerId(), state.jobs(), NOW);
        assertEquals(NOW, RunnerCodec.workspace(RunnerCodec.workspace(terminal)).processTerminalAt());
        assertNull(RunnerCodec.workspace(RunnerCodec.workspace(decoded)).processTerminalAt());
        }
    }

    @Test void snapshotsRoundTripEveryLifecycleWithoutLosingFencesOrPinnedPolicies() {
        var queued = job();
        var claimed = queued.claim(RUNNER, NOW, TTL);
        var unknown = claimed.reconcileLiveness(NOW.plus(TTL));
        var reconciling = unknown.beginReconciliation(RUNNER, NOW.plusSeconds(31), TTL);
        var done = reconciling.complete(RUNNER, 2, result(), NOW.plusSeconds(32));
        for (var job : java.util.List.of(queued, claimed, unknown, reconciling, done,
                claimed.cancel(NOW.plusSeconds(1)), queued.cancel(NOW))) {
            var state = new RunnerWorkspaceState(job.identity().execution(), UUID.randomUUID(), RUNNER,
                    Map.of(job.identity().runnerJobId(), new RunnerWorkspaceState.Entry(job, EMPTY)));
            byte[] bytes = RunnerCodec.workspace(state);
            var decoded = RunnerCodec.workspace(bytes);
            var restored = decoded.jobs().get(job.identity().runnerJobId()).job();
            assertEquals(job.identity(), restored.identity());
            assertEquals(job.definition(), restored.definition());
            assertEquals(job.authority(), restored.authority());
            assertEquals(job.state(), restored.state());
            assertEquals(job.fence(), restored.fence());
            assertEquals(job.result(), restored.result());
            assertArrayEquals(bytes, RunnerCodec.workspace(decoded));
        }
    }

    @Test void catalogRoundTripsAndCorruptionNeverBecomesAuthority() {
        assertEquals(definition(), RunnerCodec.definition(RunnerCodec.definition(definition())));
        assertEquals(runner(), RunnerCodec.registration(RunnerCodec.registration(runner())));
        byte[] bytes = RunnerCodec.definition(definition());
        for (int index : new int[]{0, 4, bytes.length / 2, bytes.length - 1}) {
            byte[] corrupt = bytes.clone(); corrupt[index] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> RunnerCodec.definition(corrupt));
        }
        assertThrows(IllegalArgumentException.class, () -> RunnerCodec.workspace(new byte[0]));
    }
    @Test void definitionBudgetsSkillsAndLongOperatorRetentionRoundTripWithoutLegacyCeilings() {
        var original = definition();
        var limits = original.policy().limits();
        var extended = new RunnerPolicy.Limits(java.time.Duration.ofDays(30), limits.memoryBytes(), limits.processes(),
                limits.workspaceBytes(), limits.artifactBytes(), limits.logBytes(), limits.payloadBytes());
        var policy = new RunnerPolicy(original.policy().capabilities(), original.policy().tools(), original.policy().egress(),
                original.policy().secrets(), original.policy().mounts(), extended);
        var definition = new AgentDefinition(original.reference(), original.instructions(), original.runtimeProfile(), original.modelProfile(),
                original.commands(), java.util.Set.of("review-rubric"), original.runnerRequirements(), policy, java.time.Duration.ofDays(730),
                original.outputSchema(), new AgentDefinition.Budgets(37, 91, 120000, 8192), Map.of("review-rubric", "Verify all invariants."));
        assertEquals(definition, RunnerCodec.definition(RunnerCodec.definition(definition)));
        var oldEncoded = RunnerCodec.definition(original);
        byte[] legacy = java.util.Arrays.copyOf(oldEncoded, oldEncoded.length - 24);
        java.nio.ByteBuffer.wrap(legacy).putInt(0x52524a31);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(legacy, legacy.length - 32));
            System.arraycopy(digest, 0, legacy, legacy.length - 32, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        assertEquals(original, RunnerCodec.definition(legacy));
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition.Budgets(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunnerPolicy.Limits(java.time.Duration.ofSeconds(Long.MAX_VALUE),
                1, 1, 1, 1, 1, 1));
    }
}
