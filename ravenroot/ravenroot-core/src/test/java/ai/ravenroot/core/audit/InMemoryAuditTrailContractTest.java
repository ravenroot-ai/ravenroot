package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.testkit.audit.AuditTrailContract;

import java.time.Clock;
import java.time.Duration;

/** The ADR 0013 conformance suite, run against the in-memory reference adapter. */
class InMemoryAuditTrailContractTest extends AuditTrailContract {

    @Override
    protected AuditTrail createTrail(String trailId, Clock clock) {
        // Every call returns a fresh, empty instance: correct, because the DURABLE-gated reopen
        // assertion is skipped for an adapter that does not declare it, exactly as
        // ExecutionStoreContract documents for its own in-memory adapter.
        return new InMemoryAuditTrail(clock, Duration.ofHours(24));
    }
}
