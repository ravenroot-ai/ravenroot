package ai.ravenroot.api.correlation;

import ai.ravenroot.api.catalog.*;
import java.util.*;

/** Explicit portable outbound headers for established context, separate from transport identity. */
public final class CorrelationHeaderMapping {
    /** Optional target header for the immutable application identity. */
    public static final String APPLICATION = "correlation.outgoing.applicationHeader";
    /** Optional target header for a distinct contribution identity. */
    public static final String CONTRIBUTION = "correlation.outgoing.contributionHeader";
    /** Optional target header for the authored contribution kind. */
    public static final String KIND = "correlation.outgoing.kindHeader";
    /** Optional target header for the explicit join round. */
    public static final String ROUND = "correlation.outgoing.roundHeader";
    /** Optional target header for the bounded public reference. */
    public static final String REFERENCE = "correlation.outgoing.referenceHeader";
    private static final Map<String, String> FIELDS = Map.of(APPLICATION, "Application ID", CONTRIBUTION, "Contribution ID",
            KIND, "Contribution kind", ROUND, "Join round", REFERENCE, "Public reference");
    private static final Set<String> PROTOCOL_HEADERS = Set.of("x-correlation-id", "x-request-id", "x-message-id", "x-reply-to", "x-idempotency-key");
    private final Map<String, String> targets;
    private CorrelationHeaderMapping(Map<String, String> targets) { this.targets = Map.copyOf(targets); }

    /** Known descriptor keys, so a connector can retain its strict configuration allowlist.
     * @return exact five opt-in property names
     */
    public static Set<String> propertyNames() { return FIELDS.keySet(); }

    /** Portable controls; the existing connector profile must independently authorize each header.
     * @return optional dedicated X-header target descriptors
     */
    public static NodePropertyDescriptor[] descriptors() {
        return FIELDS.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> NodePropertyDescriptor.boundedText(
                entry.getKey(), entry.getValue() + " outgoing header", NodePropertyType.STRING, false,
                "Optional dedicated X-* header, outside reserved X-Ravenroot-*. Copies established context only; existing connector/profile limits still apply. Blank disables this mapping.",
                "", 64, 1, 64)).toArray(NodePropertyDescriptor[]::new);
    }

    /** Compiles literal targets without reading or interpreting an event payload.
     * @param properties actual authored node properties
     * @param reservedHeaders connector-owned credential or protocol header names
     * @return immutable mapping; no configured target means disabled
     */
    public static CorrelationHeaderMapping read(Map<String, ?> properties, Collection<String> reservedHeaders) {
        var reserved = new HashSet<String>(); reservedHeaders.forEach(name -> reserved.add(name.toLowerCase(Locale.ROOT)));
        var targets = new LinkedHashMap<String, String>(); var names = new HashSet<String>();
        for (String field : FIELDS.keySet()) {
            Object raw = properties.get(field); if (raw == null || "".equals(raw)) continue;
            if (!(raw instanceof String name) || !name.matches("(?i)X-[A-Za-z0-9-]{1,62}")) throw refused("INVALID_TARGET");
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("x-ravenroot-") || PROTOCOL_HEADERS.contains(normalized) || reserved.contains(normalized)) throw refused("RESERVED_TARGET");
            if (!names.add(normalized)) throw refused("DUPLICATE_TARGET");
            targets.put(field, name);
        }
        return new CorrelationHeaderMapping(targets);
    }

    /** Copies effective headers and refuses ambiguous or conflicting targets before external I/O.
     * Optional absent fields emit no header; an existing target with no established value refuses.
     * @param original effective authored headers, after connector default/payload precedence
     * @param context immutable established context, required when a mapping is configured
     * @return copied headers for the connector's normal authority, count and size validation
     */
    public Map<String, Object> merge(Map<String, ?> original, ApplicationCorrelationContext context) {
        var result = new LinkedHashMap<String, Object>(); result.putAll(original);
        if (targets.isEmpty()) return Collections.unmodifiableMap(result);
        if (context == null) throw refused("CONTEXT_UNAVAILABLE");
        var names = new HashMap<String, String>();
        for (String name : original.keySet()) {
            if (name == null) throw refused("INVALID_HEADERS");
            if (names.putIfAbsent(name.toLowerCase(Locale.ROOT), name) != null) throw refused("DUPLICATE_HEADER");
        }
        for (var target : targets.entrySet()) {
            String value = switch (target.getKey()) {
                case APPLICATION -> context.applicationCorrelationId();
                case CONTRIBUTION -> context.contributionId().orElse(null);
                case KIND -> context.contributionKind().orElse(null);
                case ROUND -> context.roundId();
                case REFERENCE -> context.publicReference().orElse(null);
                default -> throw new IllegalStateException("Unknown established context field");
            };
            String prior = names.get(target.getValue().toLowerCase(Locale.ROOT));
            if (value == null) { if (prior != null) throw refused("CONFLICTING_HEADER"); continue; }
            if (value.codePoints().anyMatch(character -> character < 32 || character > 126)) throw refused("UNREPRESENTABLE_VALUE");
            if (prior != null && !value.equals(original.get(prior))) throw refused("CONFLICTING_HEADER");
            if (prior == null) result.put(target.getValue(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Copies the explicit headers member of a structured connector input; disabled means no change.
     * @param original actual structured connector payload
     * @param context immutable established context
     * @return copied payload for its unchanged native schema and profile validation
     */
    public Object attachPayloadHeaders(Object original, ApplicationCorrelationContext context) {
        if (targets.isEmpty()) return original;
        if (!(original instanceof Map<?, ?> payload)) throw refused("INVALID_PAYLOAD");
        Object rawHeaders = payload.get("headers"); var headers = new LinkedHashMap<String, Object>();
        if (rawHeaders != null) {
            if (!(rawHeaders instanceof Map<?, ?> declared)) throw refused("INVALID_HEADERS");
            for (var entry : declared.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw refused("INVALID_HEADERS");
                headers.put(name, entry.getValue());
            }
        }
        var result = new LinkedHashMap<Object, Object>(payload); result.put("headers", merge(headers, context));
        return Collections.unmodifiableMap(result);
    }

    private static IllegalArgumentException refused(String reason) { return new IllegalArgumentException("Correlation header mapping refused: " + reason); }
}
