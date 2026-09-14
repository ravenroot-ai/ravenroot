package ai.ravenroot.api.runner;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerCodecTest {
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
}
