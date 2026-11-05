package ai.ravenroot.api.history;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.persistence.ExecutionKey;
import java.util.*;

/** Shared ordinary HTTP, embedded viewer and CLI read query and explicit public JSON projection. */
public final class DeploymentHistoryRequest {
    private final Map<String, String> fields;
    private final String view;
    private final int limit;
    private static final Set<String> COMMON = Set.of("view", "cursor", "limit");
    /** Validates the exact public history view and its allowed text-valued query fields.
 * @param fields strict text-valued public history query fields; unsupported combinations are refused
 */
    public DeploymentHistoryRequest(Map<String, String> fields) {
        this.fields = Map.copyOf(fields);
        view = fields.getOrDefault("view", "histories");
        limit = Integer.parseInt(fields.getOrDefault("limit", "50"));
        var allowed = new HashSet<>(COMMON);
        allowed.addAll(switch (view) {
            case "policy" -> Set.of();
            case "histories" -> Set.of("applicationCorrelationId", "publicReference", "lifecycle");
            case "traversals", "joins" -> Set.of("applicationCorrelationId");
            case "join-members" -> Set.of("applicationCorrelationId", "nodeId", "roundId", "memberState");
            case "uncorrelated-traversals" -> Set.of();
            case "process-traversals" -> Set.of("processInstanceId");
            case "nodes", "edges" -> Set.of("processInstanceId", "traversalId");
            case "visits" -> Set.of("processInstanceId", "traversalId", "nodeId");
            case "attempts", "parents" -> Set.of("processInstanceId", "traversalId", "invocationId");
            case "activity" -> Set.of("processInstanceId", "traversalId", "invocationId", "attemptId");
            default -> throw new IllegalArgumentException("invalid history view");
        });
        if (view.equals("policy") && fields.containsKey("cursor")) throw new IllegalArgumentException("policy has no cursor");
        if (!allowed.containsAll(fields.keySet()) || fields.values().stream().anyMatch(value ->
                value.isBlank() || value.length() > 32768 || value.codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("invalid history query");
    }
    /** Strict text-valued request used inside the proof-protected embed envelope.
 * @param json JSON object whose values are text-valued history query fields
 * @return validated strict history query with unsupported fields refused
 */
    public static DeploymentHistoryRequest fromJson(String json) {
        var value = PayloadJson.read(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), PayloadLimits.DEFAULTS);
        if (!(value instanceof PayloadValue.MapValue object)) throw new IllegalArgumentException("invalid history query");
        var fields = new LinkedHashMap<String, String>();
        object.entries().forEach((name, field) -> {
            if (!(field instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException("history fields require text");
            fields.put(name, text.value());
        });
        return new DeploymentHistoryRequest(fields);
    }
    private String required(String name) {
        String value = fields.get(name); if (value == null) throw new IllegalArgumentException("missing history field: " + name);
        return value;
    }
    private Optional<String> optional(String name) { return Optional.ofNullable(fields.get(name)); }
    private UUID uuid(String name) { return UUID.fromString(required(name)); }
    private UUID optionalUuid(String name) { return optional(name).map(UUID::fromString).orElse(null); }
    /** Executes one bounded read. JSON contains scalar metadata; activity content is separate and policy-governed.
 * @param reader already authorized read-only deployment history facade
 * @param tenantId authenticated tenant owning this history scope
 * @return bounded JSON page with exact source, explicit availability and continuation
 */
    public String read(DeploymentHistoryReader reader, String tenantId) {
        if (limit < 1 || limit > reader.policy().maxPageSize()) throw new IllegalArgumentException("invalid history page size");
        Optional<String> cursor = optional("cursor");
        Map<String, Object> result = switch (view) {
            case "policy" -> page(List.of(map("maxPageSize", reader.policy().maxPageSize(), "readTimeoutMillis", reader.policy().readTimeout().toMillis(),
                    "maxResponseBytes", reader.policy().response().maxEncodedBytes())), Optional.empty(), "AVAILABLE");
            case "histories" -> {
                var lifecycles = optional("lifecycle").map(value -> Set.of(BusinessHistory.Lifecycle.valueOf(value))).orElse(Set.of());
                var page = reader.histories(new BusinessHistoryQuery(optional("applicationCorrelationId"), optional("publicReference"), lifecycles, cursor, limit));
                yield page(page.items().stream().map(DeploymentHistoryRequest::history).toList(), page.nextCursor(), "AVAILABLE");
            }
            case "traversals" -> {
                var page = reader.traversals(required("applicationCorrelationId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("processInstanceId", row.processInstanceId(), "traversalId", row.traversalId(),
                        "entryNodeId", row.entryNodeId(), "status", row.status(), "terminationReason", row.terminationReason(),
                        "cause", row.cause(), "startedAt", row.startedAt(), "updatedAt", row.updatedAt(), "endedAt", row.endedAt(),
                        "applicationContext", context(row.context()), "derivation", derivation(row.derivation()))).toList(), page.nextCursor(), "AVAILABLE");
            }
            case "process-traversals", "uncorrelated-traversals" -> {
                var page = view.equals("uncorrelated-traversals") ? reader.uncorrelatedTraversals(cursor, limit)
                        : reader.processTraversals(uuid("processInstanceId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("processInstanceId", row.processInstanceId(), "traversalId", row.traversalId(),
                        "entryNodeId", row.entryNodeId(), "status", row.status(), "terminationReason", row.terminationReason(),
                        "cause", row.cause(), "visitCount", row.visitCount(), "applicationContext", context(row.applicationCorrelation()), "derivation", derivation(row.derivation()))).toList(), page.nextCursor(), page.availability());
            }
            case "nodes" -> {
                var page = reader.nodes(uuid("processInstanceId"), uuid("traversalId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("nodeId", row.nodeId(), "visitCount", row.visitCount(),
                        "activeVisits", row.activeVisits(), "completedVisits", row.completedVisits(), "failedVisits", row.failedVisits(), "attemptCount", row.attemptCount())).toList(), page.nextCursor(), page.availability());
            }
            case "edges" -> {
                var page = reader.edges(uuid("processInstanceId"), uuid("traversalId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("edgeId", row.edgeId(), "traversalCount", row.traversalCount())).toList(), page.nextCursor(), page.availability());
            }
            case "visits" -> {
                var page = reader.visits(uuid("processInstanceId"), uuid("traversalId"), optional("nodeId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("processInstanceId", row.processInstanceId(), "traversalId", row.traversalId(),
                        "invocationId", row.invocationId(), "nodeId", row.nodeId(), "position", row.position(), "status", row.status(),
                        "command", row.command(), "attemptCount", row.attemptCount(), "parentCount", row.parentCount(),
                        "startedAt", row.startedAt(), "settledAt", row.settledAt(), "outcome", row.outcome(),
                        "eventHistoryAvailable", row.eventHistoryAvailable())).toList(), page.nextCursor(), page.availability());
            }
            case "attempts" -> {
                var page = reader.attempts(uuid("processInstanceId"), uuid("traversalId"), uuid("invocationId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("attemptId", row.attemptId(), "ordinal", row.ordinal(), "status", row.status(),
                        "completion", row.completion(), "startedAt", row.startedAt(), "settledAt", row.settledAt(),
                        "eventHistoryAvailable", row.eventHistoryAvailable())).toList(), page.nextCursor(), page.availability());
            }
            case "parents" -> {
                var page = reader.parents(uuid("processInstanceId"), uuid("traversalId"), uuid("invocationId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("processInstanceId", row.processInstanceId(), "traversalId", row.traversalId(),
                        "invocationId", row.invocationId(), "nodeId", row.nodeId())).toList(), page.nextCursor(), page.availability());
            }
            case "joins" -> {
                var page = reader.joins(required("applicationCorrelationId"), cursor, limit);
                yield page(page.items().stream().map(row -> map("applicationCorrelationId", row.key().applicationCorrelationId(), "nodeId", row.key().nodeId(),
                        "roundId", row.key().roundId(), "revision", row.revision(), "phase", row.phase(),
                        "expectation", row.expectation(), "expectedContributions", row.expectedContributions(), "receivedContributions", row.receivedContributions(),
                        "sealed", row.sealed(), "retainedPayloadBytes", row.retainedPayloadBytes(), "openedAt", row.openedAt(),
                        "deadlineAt", row.deadlineAt(), "updatedAt", row.updatedAt(), "continuationProcessInstanceId", row.key().continuationProcessId(),
                        "continuationTraversalId", row.key().continuationTraversalId())).toList(), page.nextCursor(), "AVAILABLE");
            }
            case "join-members" -> {
                var filter = JoinMemberFilter.valueOf(fields.getOrDefault("memberState", "ALL"));
                var page = reader.members(required("applicationCorrelationId"), required("nodeId"), required("roundId"), filter, cursor, limit);
                yield page(page.items().stream().map(row -> map("contributionId", row.contributionId(), "expected", row.expected(), "received", row.received(),
                        "kind", row.kind(), "processInstanceId", row.processInstanceId(), "traversalId", row.traversalId(), "invocationId", row.invocationId(),
                        "attemptId", row.attemptId(), "acceptedAt", row.acceptedAt())).toList(), page.nextCursor(), page.availability());
            }
            case "activity" -> activity(reader, tenantId, cursor);
            default -> throw new IllegalArgumentException("invalid history view");
        };
        var source = reader.source();
        result.put("source", map("kind", "deployment", "deploymentId", source.deploymentId(), "incarnationId", source.incarnationId(), "graphVersion", source.graphVersion()));
        result.put("view", view);
        return PayloadJson.write(PayloadValue.fromJava(result, reader.policy().response()));
    }
    private Map<String, Object> activity(DeploymentHistoryReader reader, String tenantId, Optional<String> cursor) {
        UUID process = uuid("processInstanceId"), traversal = uuid("traversalId"), invocation = optionalUuid("invocationId"), attempt = optionalUuid("attemptId");
        String binding = "activity:" + reader.source().incarnationId() + ":" + reader.source().graphVersion() + ":" + invocation + ':' + attempt + ':' + limit;
        var key = new ExecutionKey(tenantId, process);
        long after = cursor.map(value -> Long.parseLong(ExecutionHistoryCursor.decode(key, traversal, binding, value))).orElse(0L);
        var result = reader.activity(process, traversal, invocation, attempt, after, limit);
        if (result.page() == null) return page(List.of(), Optional.empty(), result.availability());
        var page = result.page();
        var records = page.records().stream().map(row -> {
            var event = row.event();
            return map("eventId", event.eventId(), "nodeId", event.nodeId(), "invocationId", event.invocationId(), "attemptId", event.attemptId(),
                    "attemptOrdinal", event.attemptOrdinal(), "contentKind", event.contentKind(), "command", event.command(), "outcome", event.outcome(),
                    "occurredAt", event.occurredAt(), "expiresAt", event.expiresAt(), "contentType", event.content().contentType(),
                    "content", PayloadJson.read(event.content().bytes(), PayloadLimits.DEFAULTS).toJava());
        }).toList();
        var projected = page(records, page.nextCursor() > after ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, binding, Long.toString(page.nextCursor()))) : Optional.empty(), result.availability());
        projected.put("gap", after > 0 && after < page.retainedFromCursor() - 1);
        projected.put("contentPolicy", "REDACTED_BOUNDED_CAPTURE");
        return projected;
    }
    private static Map<String, Object> history(BusinessHistory row) {
        return map("applicationCorrelationId", row.applicationCorrelationId(), "publicReference", row.publicReference(), "lifecycle", row.lifecycle(),
                "state", row.state(), "revision", row.revision(), "runningTraversals", row.runningTraversals(), "completedTraversals", row.completedTraversals(),
                "failedTraversals", row.failedTraversals(), "cancelledTraversals", row.cancelledTraversals(), "incompleteHistory", row.incompleteHistory(),
                "createdAt", row.createdAt(), "updatedAt", row.updatedAt(), "expiresAt", row.expiresAt(), "closureSignal", closure(row.closureSignal()));
    }
    private static Map<String, Object> derivation(CorrelationDerivation value) {
        return value == null ? null : map("processInstanceId", value.processInstanceId(), "traversalId", value.traversalId(),
                "invocationId", value.invocationId(), "attemptId", value.attemptId(), "auditReason", value.auditReason());
    }
    private static Map<String, Object> closure(BusinessClosureSignal value) {
        return value == null ? null : map("requested", value.requested(), "auditReason", value.auditReason(),
                "processInstanceId", value.processInstanceId(), "traversalId", value.traversalId(), "invocationId", value.invocationId(),
                "attemptId", value.attemptId(), "requestedAt", value.requestedAt());
    }
    private static Map<String, Object> context(ApplicationCorrelationContext value) {
        return value == null ? null : map("applicationCorrelationId", value.applicationCorrelationId(), "contributionId", value.contributionId(),
                "contributionKind", value.contributionKind(), "roundId", value.roundId(), "publicReference", value.publicReference());
    }
    private static Map<String, Object> page(List<?> items, Optional<String> cursor, Object availability) {
        return map("items", items, "nextCursor", cursor, "availability", availability);
    }
    private static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (value instanceof UUID || value instanceof Enum<?> || value instanceof java.time.Instant) value = value.toString();
            result.put((String) pairs[i], value);
        }
        return result;
    }
}
