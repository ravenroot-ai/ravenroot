package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerJobTest {
    @Test
    void acceptancePinsDefinitionAuthorityIdentityAndDeadline() {
        var job = job();
        assertEquals(RunnerJob.State.QUEUED, job.state());
        assertEquals(1, job.revision());
        assertEquals(0, job.fence());
        assertEquals(definition(), job.definition());
        assertEquals(policy(), job.authority());
        assertEquals(NOW.plusSeconds(600), job.deadline());
        assertTrue(job.retainsWorkspace());
        var shortJob = RunnerJob.accept(identity(), definition(), "implement", policy(), runner(), EMPTY,
                NOW, NOW.plusSeconds(5));
        assertEquals(NOW.plusSeconds(5), shortJob.deadline());
        assertEquals(shortJob.deadline(), shortJob.claim(RUNNER, NOW, TTL).leaseUntil());
    }

    @Test
    void claimIsSingleUseAndCompletionIsFencedBeforeIdempotentReplay() {
        var original = job();
        var claimed = original.claim(RUNNER, NOW, TTL);
        assertEquals(RunnerJob.State.QUEUED, original.state());
        assertEquals(1, claimed.fence());
        assertThrows(IllegalStateException.class, () -> claimed.claim(RUNNER, NOW, TTL));
        var report = result();
        var completed = claimed.complete(RUNNER, 1, report, NOW.plusSeconds(1));
        assertEquals(RunnerJob.State.COMPLETED, completed.state());
        assertFalse(completed.retainsWorkspace());
        assertSame(completed, completed.complete(RUNNER, 1, report, NOW.plusSeconds(90)));
        assertThrows(IllegalStateException.class,
                () -> completed.complete(RUNNER, 2, report, NOW.plusSeconds(90)));
        assertThrows(IllegalStateException.class,
                () -> completed.complete(RUNNER, 1, result("needs-work"), NOW.plusSeconds(90)));
        assertThrows(IllegalArgumentException.class,
                () -> completed.complete("runner-b", 1, report, NOW.plusSeconds(90)));
    }

    @Test
    void expiryDoesNotAuthorizeRetryOrFreeWorkspace() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        assertThrows(IllegalStateException.class,
                () -> claimed.heartbeat(RUNNER, 1, NOW.plusSeconds(30), TTL));
        assertThrows(IllegalStateException.class,
                () -> claimed.complete(RUNNER, 1, result(), NOW.plusSeconds(30)));
        var unknown = claimed.reconcileLiveness(NOW.plusSeconds(30));
        assertEquals(RunnerJob.State.UNKNOWN, unknown.state());
        assertTrue(unknown.retainsWorkspace());
        assertNull(unknown.leaseUntil());
        assertThrows(IllegalStateException.class, () -> unknown.claim(RUNNER, NOW.plusSeconds(30), TTL));
        assertThrows(IllegalStateException.class,
                () -> unknown.complete(RUNNER, 1, result(), NOW.plusSeconds(30)));
    }

    @Test
    void reconciliationIssuesANewReportOnlyFenceAndPreservesAllIdentities() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        var unknown = claimed.reconcileLiveness(NOW.plusSeconds(30));
        var reconciling = unknown.beginReconciliation(RUNNER, NOW.plusSeconds(31), TTL);
        assertEquals(RunnerJob.State.RECONCILING, reconciling.state());
        assertEquals(2, reconciling.fence());
        assertEquals(claimed.identity(), reconciling.identity());
        assertEquals(claimed.input(), reconciling.input());
        assertThrows(IllegalStateException.class, () -> reconciling.claim(RUNNER, NOW.plusSeconds(31), TTL));
        assertThrows(IllegalStateException.class,
                () -> reconciling.complete(RUNNER, 1, result(), NOW.plusSeconds(32)));
        var completed = reconciling.complete(RUNNER, 2, result(), NOW.plusSeconds(32));
        assertEquals(RunnerJob.State.COMPLETED, completed.state());
        assertEquals(claimed.identity(), completed.identity());
    }

    @Test
    void lostReconciliationCannotExtendAnOldFence() {
        var unknown = job().claim(RUNNER, NOW, TTL).reconcileLiveness(NOW.plusSeconds(30));
        var reconciling = unknown.beginReconciliation(RUNNER, NOW.plusSeconds(31), TTL);
        var lostAgain = reconciling.reconcileLiveness(NOW.plusSeconds(61));
        assertEquals(RunnerJob.State.UNKNOWN, lostAgain.state());
        var fresh = lostAgain.beginReconciliation(RUNNER, NOW.plusSeconds(62), TTL);
        assertEquals(3, fresh.fence());
        assertThrows(IllegalStateException.class,
                () -> fresh.complete(RUNNER, 2, result(), NOW.plusSeconds(63)));
    }

    @Test
    void heartbeatExtendsOnlyALiveLeaseWithoutChangingJobOrFence() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        var renewed = claimed.heartbeat(RUNNER, 1, NOW.plusSeconds(20), TTL);
        assertEquals(NOW.plusSeconds(50), renewed.leaseUntil());
        assertEquals(claimed.fence(), renewed.fence());
        assertEquals(claimed.identity(), renewed.identity());
        assertEquals(claimed.revision() + 1, renewed.revision());
        assertSame(renewed, renewed.reconcileLiveness(NOW.plusSeconds(31)));
        assertThrows(IllegalStateException.class,
                () -> renewed.heartbeat(RUNNER, 0, NOW.plusSeconds(21), TTL));
    }

    @Test
    void cancelBeforeDispatchIsTerminalWithoutFabricatingARunnerReport() {
        var cancelled = job().cancel(NOW);
        assertEquals(RunnerJob.State.CANCELLED, cancelled.state());
        assertFalse(cancelled.retainsWorkspace());
        assertEquals(0, cancelled.fence());
        assertNull(cancelled.result());
        assertSame(cancelled, cancelled.cancel(NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> cancelled.claim(RUNNER, NOW, TTL));
    }

    @Test
    void cancelAfterDispatchWaitsForQuiescenceAndWinsLateSuccessRace() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        var cancelling = claimed.cancel(NOW.plusSeconds(1));
        assertEquals(RunnerJob.State.CANCELLING, cancelling.state());
        assertTrue(cancelling.retainsWorkspace());
        var report = result("completed");
        var cancelled = cancelling.complete(RUNNER, 1, report, NOW.plusSeconds(2));
        assertEquals(RunnerJob.State.CANCELLED, cancelled.state());
        assertEquals(report, cancelled.result());
        assertFalse(cancelled.retainsWorkspace());
        var completedFirst = claimed.complete(RUNNER, 1, report, NOW.plusSeconds(1));
        assertSame(completedFirst, completedFirst.cancel(NOW.plusSeconds(2)));
    }

    @Test
    void cancelWhileDisconnectedRequiresReconciliationAndRetainsStopReason() {
        var cancelledUnknown = job().claim(RUNNER, NOW, TTL).cancel(NOW.plusSeconds(30));
        assertEquals(RunnerJob.State.UNKNOWN, cancelledUnknown.state());
        assertEquals(RunnerJob.StopReason.CANCEL, cancelledUnknown.stopReason());
        assertTrue(cancelledUnknown.retainsWorkspace());
        var reconciling = cancelledUnknown.beginReconciliation(RUNNER, NOW.plusSeconds(31), TTL);
        var cancelled = reconciling.complete(RUNNER, 2, result(), NOW.plusSeconds(32));
        assertEquals(RunnerJob.State.CANCELLED, cancelled.state());
    }

    @Test
    void deadlineNeverDeclaresDispatchedChildrenStopped() {
        var queued = RunnerJob.accept(identity(), definition(), "implement", policy(), runner(), EMPTY,
                NOW, NOW.plusSeconds(10));
        assertEquals(RunnerJob.State.DEADLINE_EXCEEDED,
                queued.reconcileLiveness(NOW.plusSeconds(10)).state());
        assertEquals(RunnerJob.State.DEADLINE_EXCEEDED, queued.claim(RUNNER, NOW.plusSeconds(10), TTL).state());
        var unknown = queued.claim(RUNNER, NOW, TTL).reconcileLiveness(NOW.plusSeconds(10));
        assertEquals(RunnerJob.State.UNKNOWN, unknown.state());
        assertEquals(RunnerJob.StopReason.DEADLINE, unknown.stopReason());
        assertTrue(unknown.retainsWorkspace());
        var reconciling = unknown.beginReconciliation(RUNNER, NOW.plusSeconds(11), TTL);
        assertEquals(NOW.plusSeconds(41), reconciling.leaseUntil());
        var expired = reconciling.complete(RUNNER, 2, result(), NOW.plusSeconds(12));
        assertEquals(RunnerJob.State.DEADLINE_EXCEEDED, expired.state());
        assertFalse(expired.retainsWorkspace());
    }

    @Test
    void deadlineOverridesAnEarlierCancellationDuringCleanup() {
        var queued = RunnerJob.accept(identity(), definition(), "implement", policy(), runner(), EMPTY,
                NOW, NOW.plusSeconds(10));
        var cancelling = queued.claim(RUNNER, NOW, TTL).cancel(NOW.plusSeconds(1));
        var extendedCleanup = cancelling.heartbeat(RUNNER, 1, NOW.plusSeconds(2), TTL);
        assertEquals(NOW.plusSeconds(32), extendedCleanup.leaseUntil());
        var expired = extendedCleanup.complete(RUNNER, 1, result(), NOW.plusSeconds(11));
        assertEquals(RunnerJob.State.DEADLINE_EXCEEDED, expired.state());
    }

    @Test
    void resultMustMatchThePinnedOutcomePayloadAndArtifactQuotas() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        assertThrows(IllegalArgumentException.class,
                () -> claimed.complete(RUNNER, 1, result("unapproved"), NOW));
        var oversized = new RunnerResult("completed", OpaquePayload.of(new byte[513], "application/json"),
                List.of(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> claimed.complete(RUNNER, 1, oversized, NOW));
        for (var kind : List.of(RunnerArtifact.Kind.LOG, RunnerArtifact.Kind.STDOUT, RunnerArtifact.Kind.STDERR)) {
            var log = artifact(claimed.identity(), kind, 1_001);
            assertThrows(IllegalArgumentException.class,
                    () -> claimed.complete(RUNNER, 1, report(log), NOW));
        }
        var huge = artifact(claimed.identity(), RunnerArtifact.Kind.PATCH, Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> claimed.complete(RUNNER, 1, report(huge), NOW));
        var a = artifact(claimed.identity(), RunnerArtifact.Kind.PATCH, 6_000);
        var b = artifact(claimed.identity(), RunnerArtifact.Kind.MANIFEST, 6_000);
        assertThrows(IllegalArgumentException.class, () -> claimed.complete(RUNNER, 1, report(a, b), NOW));
        var boundary = artifact(claimed.identity(), RunnerArtifact.Kind.PATCH, 10_000);
        assertEquals(RunnerJob.State.COMPLETED, claimed.complete(RUNNER, 1, report(boundary), NOW).state());
    }

    @Test
    void everyArtifactRequiresTheFullJobIdentity() {
        var claimed = job().claim(RUNNER, NOW, TTL);
        var id = claimed.identity();
        for (var other : List.of(identity(), identity(id.execution()),
                new RunnerJobIdentity(new ExecutionKey("tenant-b", id.execution().processInstanceId()),
                        id.traversalId(), id.invocationId(), id.attemptId(), id.runnerJobId()),
                new RunnerJobIdentity(id.execution(), UUID.randomUUID(), id.invocationId(), id.attemptId(), id.runnerJobId()),
                new RunnerJobIdentity(id.execution(), id.traversalId(), UUID.randomUUID(), id.attemptId(), id.runnerJobId()),
                new RunnerJobIdentity(id.execution(), id.traversalId(), id.invocationId(), UUID.randomUUID(), id.runnerJobId()),
                new RunnerJobIdentity(id.execution(), id.traversalId(), id.invocationId(), id.attemptId(), UUID.randomUUID()))) {
            var artifact = artifact(other, RunnerArtifact.Kind.PATCH, 1);
            assertThrows(IllegalArgumentException.class,
                    () -> claimed.complete(RUNNER, 1, report(artifact), NOW));
        }
    }

    @Test
    void acceptanceRejectsCrossTenantProfilesMissingRequirementsAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> RunnerJob.accept(identity(),
                definition("tenant-b", "implement", false), "implement", policy(), runner(), EMPTY,
                NOW, NOW.plusSeconds(60)));
        var wrongTenant = new RunnerRegistration(1, "tenant-b", RUNNER, "sandboxed", Set.of("sandboxed"), policy());
        var missingRequirements = new RunnerRegistration(1, TENANT, RUNNER, "sandboxed", Set.of(), policy());
        for (var runner : List.of(wrongTenant, missingRequirements)) {
            assertThrows(IllegalArgumentException.class, () -> RunnerJob.accept(identity(), definition(),
                    "implement", policy(), runner, EMPTY, NOW, NOW.plusSeconds(60)));
        }
        assertThrows(IllegalArgumentException.class, () -> RunnerJob.accept(identity(), definition(),
                "implement", policy(), runner(), OpaquePayload.of(new byte[513], "application/json"),
                NOW, NOW.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> RunnerJob.accept(identity(), definition(),
                "implement", policy(), runner(), EMPTY, NOW, NOW));
    }

    @Test
    void malformedArtifactsAndAbsentQuiescenceAreRejected() {
        assertThrows(NullPointerException.class, () -> new RunnerResult("completed", EMPTY, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RunnerArtifact(identity(), UUID.randomUUID(), RunnerArtifact.Kind.LOG, "../path", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RunnerArtifact(identity(), UUID.randomUUID(), RunnerArtifact.Kind.LOG, "0".repeat(64), -1));
        var artifact = artifact(identity(), RunnerArtifact.Kind.LOG, 0);
        assertThrows(IllegalArgumentException.class, () -> report(artifact, artifact));
    }

    @Test
    void invalidLeasesAndBackwardsStoreTimeCannotReviveWork() {
        var job = job();
        for (var invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(6))) {
            assertThrows(IllegalArgumentException.class, () -> job.claim(RUNNER, NOW, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> job.claim(RUNNER, NOW.minusSeconds(1), TTL));
        var claimed = job.claim(RUNNER, NOW, TTL);
        assertThrows(IllegalArgumentException.class,
                () -> claimed.heartbeat(RUNNER, 1, NOW.minusSeconds(1), TTL));
        assertThrows(IllegalArgumentException.class, () -> claimed.cancel(NOW.minusSeconds(1)));
    }

    private static RunnerArtifact artifact(RunnerJobIdentity job, RunnerArtifact.Kind kind, long size) {
        return new RunnerArtifact(job, UUID.randomUUID(), kind, "a".repeat(64), size);
    }

    private static RunnerResult report(RunnerArtifact... artifacts) {
        return new RunnerResult("completed", EMPTY, List.of(artifacts), UUID.randomUUID());
    }
}
