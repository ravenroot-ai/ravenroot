package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.core.audit.AuditTrailAuthorizationSink;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API-02 category mapping for {@link AuditTrailAuthorizationSink#categoryFor}, specifically
 * mutation-proving the {@code EXECUTION_CONTROL -&gt;
 * AuditCategory.CONTROL} arm against the {@code default -> AuditCategory.DECISION} every other action
 * (this test included, via {@code EXECUTION_START}) falls through to.
 */
class AuditTrailAuthorizationSinkTest {
    @Test
    void categorisesExecutionControlDecisionsAsControlRatherThanTheGenericDecisionCategory() {
        var trail = new InMemoryAuditTrail();
        var sink = new AuditTrailAuthorizationSink(trail);

        sink.record(new AuthorizationAuditEvent(Instant.now(), "req-1", "alice", "tenant-a",
                AuthorizationAction.EXECUTION_CONTROL, "execution", "e-1", true, "policy allowed"));
        sink.record(new AuthorizationAuditEvent(Instant.now(), "req-2", "alice", "tenant-a",
                AuthorizationAction.EXECUTION_START, "executions", "executions", true, "policy allowed"));

        var records = trail.read("tenant-a", 0, 10);
        assertEquals(2, records.size());
        assertEquals(AuditCategory.CONTROL, records.get(0).envelope().category(),
                "EXECUTION_CONTROL must be categorised CONTROL, not fall through to the generic default");
        assertEquals(AuditCategory.DECISION, records.get(1).envelope().category(),
                "an ordinary action must still fall through to the generic default unchanged");
    }
}
