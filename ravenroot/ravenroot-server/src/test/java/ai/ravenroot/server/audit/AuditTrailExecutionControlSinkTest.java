package ai.ravenroot.server.audit;

import ai.ravenroot.api.application.ExecutionControlAuditEvent;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API-02 producer of {@link AuditCategory#CONTROL}. Mutation-proves that a cancel/drain audit event
 * lands under {@code CONTROL}, not the generic
 * {@code DECISION} every other {@code AuthorizationAction} falls through to in
 * {@link AuditTrailAuthorizationSink#categoryFor} -- and that the ATTEMPT/DENIED/FAILED/SUCCEEDED
 * disposition maps onto {@link AuditOutcome} exactly like {@link AuditTrailArtifactLifecycleSink} does.
 */
class AuditTrailExecutionControlSinkTest {
    @Test
    void recordsUnderTheControlCategoryWithTheOutcomeTheDispositionImplies() {
        var trail = new InMemoryAuditTrail();
        var sink = new AuditTrailExecutionControlSink(trail);

        sink.record(new ExecutionControlAuditEvent(Instant.now(), "req-1", "alice", "tenant-a", "cancel",
                "execution", "e-1", ExecutionControlAuditEvent.Disposition.ATTEMPT, ""));
        sink.record(new ExecutionControlAuditEvent(Instant.now(), "req-1", "alice", "tenant-a", "cancel",
                "execution", "e-1", ExecutionControlAuditEvent.Disposition.SUCCEEDED, "CANCELLED"));

        var records = trail.read("tenant-a", 0, 10);
        assertEquals(2, records.size());
        assertEquals(AuditCategory.CONTROL, records.get(0).envelope().category());
        assertEquals(AuditCategory.CONTROL, records.get(1).envelope().category());
        assertEquals(AuditOutcome.ATTEMPTED, records.get(0).envelope().outcome());
        assertEquals(AuditOutcome.ALLOWED, records.get(1).envelope().outcome());
    }

    @Test
    void deniedAndFailedDispositionsMapToTheirOwnDistinctOutcomes() {
        var trail = new InMemoryAuditTrail();
        var sink = new AuditTrailExecutionControlSink(trail);

        sink.record(new ExecutionControlAuditEvent(Instant.now(), "req-2", "bob", "tenant-a", "drain",
                "server", "cluster", ExecutionControlAuditEvent.Disposition.DENIED, ""));
        sink.record(new ExecutionControlAuditEvent(Instant.now(), "req-3", "bob", "tenant-a", "drain",
                "server", "cluster", ExecutionControlAuditEvent.Disposition.FAILED, ""));

        var records = trail.read("tenant-a", 0, 10);
        assertEquals(AuditOutcome.DENIED, records.get(0).envelope().outcome());
        assertEquals(AuditOutcome.FAILED, records.get(1).envelope().outcome());
    }
}
