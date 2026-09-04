package ai.ravenroot.server.audit;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Routes the four decisional {@link ExecutionEventType}s onto the SEC-13 durable trail. Registered
 * <em>alongside</em> the existing {@code StructuredExecutionLogger}/
 * {@code TelemetryBridge} subscriptions, not in place of either — this class adds a destination, it
 * does not move one, and every non-decisional event it sees is simply not appended anywhere by it.
 *
 * <h2>Exactly four types, and why the excluded ones are not a near miss</h2>
 * <p>A per-node event fires once per node invocation, scaling with nodes &times; traversals, which is
 * the wrong order of
 * magnitude for {@link AuditTrail#append}'s synchronous, per-tenant-serialized, double-fsync write.
 * the documented contract keeps the chain at request-scope frequency — the same order
 * {@link AuditTrailRateLimitSink} already proves works in production — by admitting only the
 * traversal's own admission/terminal facts, plus the one join event with unique diagnostic value:</p>
 * <ul>
 *   <li>{@link ExecutionEventType#EXECUTION_STARTED} — the traversal's admission, recorded as
 *       {@link AuditOutcome#ATTEMPTED} because {@code EXECUTION_COMPLETED}/{@code EXECUTION_FAILED}
 *       is the terminal record that follows, sharing the same correlation id — the same
 *       attempt/terminal pairing {@link AuditTrailArtifactLifecycleSink} already establishes.</li>
 *   <li>{@link ExecutionEventType#EXECUTION_COMPLETED} — the terminal success.</li>
 *   <li>{@link ExecutionEventType#EXECUTION_FAILED} — the terminal failure, the record a security
 *       trail exists for.</li>
 *   <li>{@link ExecutionEventType#JOIN_FAILED} — <b>failure has many shapes, success has one.</b> A
 *       join failing is one of several distinct ways a traversal can fail, and CORE-03's own
 *       {@code reason}/{@code arrived}/{@code failed}/{@code outstanding} breakdown, carried in this
 *       event's own detail, is structural information {@code EXECUTION_FAILED}'s generic failure
 *       message does not necessarily reproduce. {@link ExecutionEventType#JOIN_SATISFIED} has no
 *       equivalent case: a join succeeding is a normal step toward the one outcome
 *       {@code EXECUTION_COMPLETED} already fully represents, so recording it separately would add no
 *       decision an auditor could not already read off the terminal event. That asymmetry, not a
 *       volume difference — both join events fire at the same bounded per-join-per-traversal
 *       frequency — is why one join event is on the chain and its twin is not.</li>
 * </ul>
 *
 * <p>Every {@code NODE_*} type is node-invocation-scoped, not traversal-scoped — checked directly
 * against the definition each carries, not assumed from its name — and stays on spans/metrics via
 * {@code TelemetryBridge}, entirely untouched by this class.
 * {@link ExecutionEventType#JOIN_ARRIVAL_DISCARDED} is the sharpest exclusion: unbounded by the
 * graph's own shape, driven instead by how often an upstream system redelivers, and its own Javadoc
 * states plainly that neither of its two causes is a failure. An audit chain whose volume is set by
 * someone else's retry behaviour is not an audit chain.</p>
 *
 * <p>{@link ExecutionEventType#EXECUTION_PAUSED} and {@link ExecutionEventType#EXECUTION_RESUMED}
 * are excluded on the opposite ground from every exclusion above, and it is worth being explicit
 * because they otherwise look like exactly the kind of thing this chain is for: they are control
 * actions taken over somebody's work, they are traversal-scoped, and they are bounded by operator
 * behaviour rather than by graph shape. They are excluded because they are <b>already audited, and
 * audited better</b>. {@code AuthorizedRavenrootApplication} records every pause and resume at the
 * point it authorizes them, carrying the acting principal's own identity. This class cannot match
 * that — see the known gap below — so admitting them here would write a second, weaker record of an
 * act already recorded properly, and an investigator would find two entries per pause of which one
 * could not say who did it. Duplication that degrades attribution is worse than no duplication.</p>
 *
 * <h2>A known gap, stated rather than papered over</h2>
 * <p>{@link AuditEnvelope#principal()} is required and non-blank, but {@link ExecutionEvent} carries
 * no subject/actor identifier — only {@code tenantId} and {@code requestId} were ever extracted from
 * the originating {@code SecurityContext} onto the event (see {@code ExecutionMonitor.publish}). This
 * class records {@link #PRINCIPAL_NOT_CARRIED} rather than inventing an identity, matching
 * {@code AuditEnvelope}'s own documented accommodation for "the producer does not yet carry more".
 * These records are therefore tenant-attributed but not principal-attributed the way authorization,
 * artifact-lifecycle and rate-limit records already are; closing that gap means threading
 * {@code SecurityContext.subject()} onto {@code ExecutionEvent} the same way
 * {@code deploymentId}/{@code workloadId} are threaded; this class does not add that field.</p>
 */
public final class AuditTrailExecutionSink implements Consumer<ExecutionEvent> {
    /** No subject/actor identifier reaches {@link ExecutionEvent} today; see the class Javadoc. */
    public static final String PRINCIPAL_NOT_CARRIED = "-";

    private static final Set<ExecutionEventType> DECISIONAL = EnumSet.of(ExecutionEventType.EXECUTION_STARTED,
            ExecutionEventType.EXECUTION_COMPLETED, ExecutionEventType.EXECUTION_FAILED,
            ExecutionEventType.JOIN_FAILED);

    private final AuditTrail auditTrail;

    public AuditTrailExecutionSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void accept(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (!DECISIONAL.contains(event.type())) {
            return;
        }
        // A real JSON blob, escaped with the same JsonStrings every other
        // AuditTrail*Sink now uses -- Base64 protects the byte sequence FileAuditTrail persists this
        // as, not the JSON syntax inside it, and this previously had no escaping at all.
        // event.detail() itself is placed unescaped into AuditEnvelope's own `reason` parameter below,
        // which needs none: that field is Base64-encoded by FileAuditTrail like every other envelope
        // field, so it is not this class's own constructed structure the way the JSON blob is.
        String detail = "{\"detail\":\"" + JsonStrings.escape(event.detail())
                + "\",\"graphVersion\":\"" + JsonStrings.escape(event.graphVersion()) + "\""
                + ",\"deploymentId\":" + (event.deploymentId() == null ? "null"
                        : "\"" + JsonStrings.escape(event.deploymentId()) + "\"")
                + ",\"workloadId\":" + (event.workloadId() == null ? "null"
                        : "\"" + JsonStrings.escape(event.workloadId()) + "\"")
                + "}";
        auditTrail.append(AuditEnvelope.of(event.tenantId(), PRINCIPAL_NOT_CARRIED, AuditCategory.ACCESS,
                action(event.type()), "execution", event.traversalId().toString(), outcome(event.type()),
                event.detail(), event.requestId(), event.occurredAt(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "application/json")));
    }

    private static String action(ExecutionEventType type) {
        return switch (type) {
            case EXECUTION_STARTED -> "execution.started";
            case EXECUTION_COMPLETED -> "execution.completed";
            case EXECUTION_FAILED -> "execution.failed";
            case JOIN_FAILED -> "execution.join_failed";
            default -> throw new IllegalArgumentException("not a decisional event type: " + type);
        };
    }

    private static AuditOutcome outcome(ExecutionEventType type) {
        return switch (type) {
            case EXECUTION_STARTED -> AuditOutcome.ATTEMPTED;
            case EXECUTION_COMPLETED -> AuditOutcome.ALLOWED;
            case EXECUTION_FAILED, JOIN_FAILED -> AuditOutcome.FAILED;
            default -> throw new IllegalArgumentException("not a decisional event type: " + type);
        };
    }
}
