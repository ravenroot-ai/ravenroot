package ai.ravenroot.server.audit;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exactly the five decisional {@link ExecutionEventType}s reach the SEC-13 durable
 * trail through {@link AuditTrailExecutionSink}, and every other type reaches whatever else is
 * subscribed on the same {@code ExecutionMonitor} unaffected — {@code AuditTrailExecutionSink} adds a
 * destination, it does not consume events other subscribers would otherwise have seen.
 *
 * <p>See {@code AuditTrailExecutionSink}'s own Javadoc for the full reasoning behind the split; this
 * class exists to make the "exactly five, no more, no fewer" claim mutation-provable rather than only
 * documented.</p>
 */
class AuditTrailExecutionSinkTest {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";
    private static final UUID PROCESS_INSTANCE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TRAVERSAL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID INVOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-0000000000f4");

    private InMemoryAuditTrail trail() {
        return new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
    }

    /**
     * Every {@link ExecutionEventType} is driven through the
     * sink once; exactly five must reach the trail. A sixth reaching it, or one of the five being
     * silently dropped, must both be failures a maintainer adding an event type in the future would
     * see immediately.
     */
    @Test
    void exactlyTheFiveDecisionalTypesReachTheTrailAndNoOthers() {
        try (var trail = trail()) {
            var sink = new AuditTrailExecutionSink(trail);
            for (ExecutionEventType type : ExecutionEventType.values()) {
                sink.accept(eventOf(type));
            }

            List<AuditRecord> records = trail.read(TENANT, 0, 100);
            assertEquals(5, records.size(),
                    () -> "expected exactly the 5 decisional types, got " + records.size() + ": "
                            + records.stream().map(r -> r.envelope().action()).toList());
            var actions = records.stream().map(r -> r.envelope().action()).collect(java.util.stream.Collectors.toSet());
            assertEquals(java.util.Set.of("execution.started", "execution.completed", "execution.failed",
                    "execution.cancelled", "execution.join_failed"), actions);
        }
    }

    /**
     * Pause and resume are deliberately not decisional here, and this is where that stays decided.
     *
     * <p>They are control actions over somebody's work, so they plainly belong in an audit trail —
     * and they are already in one. {@code AuthorizedRavenrootApplication} audits every pause and
     * resume at the point it authorizes them, with the acting principal's own identity attached.
     * This sink cannot match that: no subject reaches {@link ExecutionEvent}, so it records
     * {@link AuditTrailExecutionSink#PRINCIPAL_NOT_CARRIED} in place of one. Admitting these types
     * here would therefore write a second, weaker record of an act that is already recorded properly,
     * and an investigator reading the trail would find two entries per pause of which one cannot say
     * who did it.</p>
     *
     * <p>Pinned as its own test rather than left to the count above, because a count is satisfied by
     * any four types and would not notice these two being swapped in for two others.</p>
     */
    @Test
    void pauseAndResumeAreNotDecisionalBecauseTheAuthorizedControlPathAlreadyAuditsThemWithAPrincipal() {
        try (var trail = trail()) {
            var sink = new AuditTrailExecutionSink(trail);
            sink.accept(eventOf(ExecutionEventType.EXECUTION_PAUSED));
            sink.accept(eventOf(ExecutionEventType.EXECUTION_RESUMED));
            assertEquals(List.of(), trail.read(TENANT, 0, 100),
                    "a pause is audited by the control path that authorized it, not a second time here");
        }
    }

    /**
     * The other half of "adds a destination, does not move one": a plain collector registered on the
     * same stream as the audit sink must see every event, including every type the audit sink discards.
     * Nothing about subscribing the new sink may suppress what another subscriber receives —
     * {@code ExecutionMonitor}'s own fan-out already guarantees this structurally, and this test is
     * what makes that guarantee checkable for this specific pairing rather than assumed.
     */
    @Test
    void everyEventTypeStillReachesAnIndependentSubscriberUnaffectedByTheAuditSink() {
        try (var trail = trail()) {
            var auditSink = new AuditTrailExecutionSink(trail);
            var allEvents = new ArrayList<ExecutionEvent>();
            Consumer<ExecutionEvent> fanOut = event -> {
                allEvents.add(event);
                auditSink.accept(event);
            };

            for (ExecutionEventType type : ExecutionEventType.values()) {
                fanOut.accept(eventOf(type));
            }

            assertEquals(ExecutionEventType.values().length, allEvents.size(),
                    "the independent subscriber must see every type, decisional or not");
            assertEquals(5, trail.read(TENANT, 0, 100).size(),
                    "the audit sink must still admit only the decisional five in the same run");
        }
    }

    @Test
    void executionStartedIsRecordedAsAttemptedPendingATerminalRecord() {
        try (var trail = trail()) {
            new AuditTrailExecutionSink(trail).accept(eventOf(ExecutionEventType.EXECUTION_STARTED));

            AuditRecord record = only(trail);
            assertEquals(AuditCategory.ACCESS, record.envelope().category());
            assertEquals("execution.started", record.envelope().action());
            assertEquals(AuditOutcome.ATTEMPTED, record.envelope().outcome());
            assertEquals(TRAVERSAL.toString(), record.envelope().resourceId());
            assertEquals("req-1", record.envelope().correlationId());
        }
    }

    @Test
    void executionStartedAndItsTerminalRecordShareOneCorrelationId() {
        try (var trail = trail()) {
            var sink = new AuditTrailExecutionSink(trail);
            sink.accept(eventOf(ExecutionEventType.EXECUTION_STARTED));
            sink.accept(eventOf(ExecutionEventType.EXECUTION_COMPLETED));

            List<AuditRecord> records = trail.read(TENANT, 0, 100);
            assertEquals(2, records.size());
            assertEquals(records.get(0).envelope().correlationId(), records.get(1).envelope().correlationId(),
                    "the attempt and its terminal record must be joinable by correlation id, the same "
                            + "pairing AuditTrailArtifactLifecycleSink already establishes");
            assertEquals(AuditOutcome.ATTEMPTED, records.get(0).envelope().outcome());
            assertEquals(AuditOutcome.ALLOWED, records.get(1).envelope().outcome());
        }
    }

    @Test
    void executionFailedIsRecordedAsFailedWithTheRootCauseAsReason() {
        try (var trail = trail()) {
            new AuditTrailExecutionSink(trail).accept(eventOf(ExecutionEventType.EXECUTION_FAILED));

            AuditRecord record = only(trail);
            assertEquals(AuditOutcome.FAILED, record.envelope().outcome());
            assertEquals("detail for EXECUTION_FAILED", record.envelope().reason());
        }
    }

    /**
     * A cancellation must not collapse into an ordinary failure record, which is exactly what a
     * regression here would do silently: {@code DECISIONAL} would simply not contain
     * {@code EXECUTION_CANCELLED} and the event would vanish from the trail with no exception raised.
     */
    @Test
    void executionCancelledIsRecordedAsItsOwnActionRatherThanAsAFailure() {
        try (var trail = trail()) {
            new AuditTrailExecutionSink(trail).accept(eventOf(ExecutionEventType.EXECUTION_CANCELLED));

            AuditRecord record = only(trail);
            assertEquals("execution.cancelled", record.envelope().action());
            assertEquals(AuditOutcome.ALLOWED, record.envelope().outcome(),
                    "a cancellation reached its terminal state cleanly; it is not a platform failure");
        }
    }

    @Test
    void joinFailedIsRecordedAsFailedAndCarriesItsStructuralDetail() {
        try (var trail = trail()) {
            new AuditTrailExecutionSink(trail).accept(eventOf(ExecutionEventType.JOIN_FAILED));

            AuditRecord record = only(trail);
            assertEquals(AuditCategory.ACCESS, record.envelope().category());
            assertEquals("execution.join_failed", record.envelope().action());
            assertEquals(AuditOutcome.FAILED, record.envelope().outcome());
            String detail = new String(record.envelope().detail().bytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(detail.contains("detail for JOIN_FAILED"),
                    () -> "the join's own structural detail must reach the record: " + detail);
        }
    }

    /** {@code ExecutionEvent} carries no subject; the known gap is recorded, not invented around. */
    @Test
    void everyDecisionalRecordUsesTheDocumentedPrincipalPlaceholderRatherThanInventingAnIdentity() {
        try (var trail = trail()) {
            new AuditTrailExecutionSink(trail).accept(eventOf(ExecutionEventType.EXECUTION_STARTED));

            assertEquals(AuditTrailExecutionSink.PRINCIPAL_NOT_CARRIED, only(trail).envelope().principal());
        }
    }

    /**
     * Unlike the rejection sinks, every field this sink's own JSON
     * {@code detail} blob is built from ({@code event.detail()}, {@code graphVersion()},
     * {@code deploymentId()}, {@code workloadId()}) is directly controllable by whoever constructs the
     * {@code ExecutionEvent} -- deployment ids in particular are caller-supplied strings with no
     * character restriction beyond non-blank. This proves the blob stays valid JSON, not merely that
     * the canary is present somewhere in it: a raw control character surviving would make the blob
     * itself the forged record, even though {@code FileAuditTrail} would still persist the surrounding
     * envelope line intact.
     */
    @Test
    void theJsonDetailBlobStaysValidWhenEveryControllableFieldCarriesTheCanary() {
        String canary = "before" + '\t' + "middle" + '\n' + "ctrl" + (char) 1 + "after";
        try (var trail = trail()) {
            var event = new ExecutionEvent(1, EPOCH, TENANT, "req-1", "engine", canary, PROCESS_INSTANCE,
                    TRAVERSAL, null, null, ExecutionEventType.EXECUTION_STARTED, null, 0, false, canary, null,
                    null, null, canary, canary);

            new AuditTrailExecutionSink(trail).accept(event);

            String detail = new String(only(trail).envelope().detail().bytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(detail.chars().noneMatch(character -> character < 0x20),
                    () -> "a raw control character reached the JSON detail blob: " + detail);
            assertTrue(detail.contains("\\t") && detail.contains("\\n") && detail.contains("\\u0001"),
                    () -> "the canary must be present in escaped form, not simply absent: " + detail);
        }
    }

    private static AuditRecord only(InMemoryAuditTrail trail) {
        List<AuditRecord> records = trail.read(TENANT, 0, 100);
        assertEquals(1, records.size(), () -> "expected exactly one record: " + records);
        return records.get(0);
    }

    /** One representative, individually valid event per type under the compact-constructor rules. */
    private static ExecutionEvent eventOf(ExecutionEventType type) {
        boolean nodeScoped = Map.of(
                ExecutionEventType.NODE_STARTED, true,
                ExecutionEventType.NODE_DEFAULTED, true,
                ExecutionEventType.NODE_COMPLETED, true,
                ExecutionEventType.NODE_FAILED, true,
                ExecutionEventType.EDGE_TRAVERSED, true).getOrDefault(type, false);
        boolean joinScoped = type == ExecutionEventType.JOIN_SATISFIED
                || type == ExecutionEventType.JOIN_ARRIVAL_DISCARDED || type == ExecutionEventType.JOIN_FAILED;
        Duration processingDuration = type == ExecutionEventType.NODE_COMPLETED
                || type == ExecutionEventType.NODE_FAILED ? Duration.ofMillis(1) : null;
        Duration joinWaitDuration = type == ExecutionEventType.JOIN_SATISFIED
                || type == ExecutionEventType.JOIN_FAILED ? Duration.ofMillis(2) : null;
        return new ExecutionEvent(1, EPOCH, TENANT, "req-1", "engine", "graph-v1", PROCESS_INSTANCE, TRAVERSAL,
                nodeScoped ? INVOCATION : null, nodeScoped ? ATTEMPT : null, type,
                nodeScoped || joinScoped ? "node-or-join" : null, 0, false, "detail for " + type,
                joinWaitDuration, processingDuration, null, null, null, 0,
                type == ExecutionEventType.EDGE_TRAVERSED ? "continue" : null,
                null, null, type == ExecutionEventType.EDGE_TRAVERSED ? "edge-1" : null);
    }
}
