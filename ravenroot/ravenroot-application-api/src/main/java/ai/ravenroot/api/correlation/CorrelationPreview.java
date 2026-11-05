package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-mutating sample evaluation through the exact mapping used for admitted events.
 * @param valid whether configuration and sample extraction passed the configured bounds
 * @param mode compiled identity policy, absent when configuration is invalid
 * @param context illustrative extracted context, absent on failure; generated UUIDs are preview placeholders
 * @param generatedIdentity whether the sample needed an illustrative generated identity
 * @param requiresExistingHistory whether actual admission must additionally find a scoped open history
 * @param selector authored pointer responsible for failure, absent when no single pointer is responsible
 * @param reason fixed payload-safe failure category, absent on success
 */
public record CorrelationPreview(boolean valid, CorrelationMapping.Mode mode,
        ApplicationCorrelationContext context, boolean generatedIdentity, boolean requiresExistingHistory,
        String selector, String reason) {
    /** Evaluates authoring configuration without creating or reading any business history.
     * @param properties explicit mapping properties only
     * @param sample actual exposed connector input or computed structured value
     * @param limits operator-owned extraction and input bounds
     * @return sample result; errors contain no selected sample value
     */
    public static CorrelationPreview evaluate(Map<String, ?> properties, Object sample, CorrelationLimits limits) {
        CorrelationMapping mapping = null;
        try {
            if (properties.size() > 8 || properties.values().stream().anyMatch(value -> !(value instanceof String)) || !Set.of(CorrelationMappingProperties.MODE, CorrelationMappingProperties.SELECTOR,
                    CorrelationMappingProperties.SECONDARY_SELECTOR, CorrelationMappingProperties.CONTRIBUTION,
                    CorrelationMappingProperties.KIND, CorrelationMappingProperties.ROUND, CorrelationMappingProperties.REFERENCE)
                    .containsAll(properties.keySet()))
                return new CorrelationPreview(false, null, null, false, false, null, "INVALID_CONFIGURATION");
            mapping = CorrelationMappingProperties.read(properties, limits);
            var generated = new AtomicBoolean();
            var context = mapping.extract(PayloadValue.fromJava(sample, limits.input()), () -> {
                generated.set(true); return new UUID(0, 0);
            });
            return new CorrelationPreview(true, mapping.mode(), context, generated.get(),
                    mapping.mode() == CorrelationMapping.Mode.REQUIRE_EXISTING, null, null);
        } catch (CorrelationSelector.Invalid invalid) {
            return failure(mapping, invalid.selector(), invalid.reason().name());
        } catch (PayloadException tooLarge) {
            return failure(mapping, null, "INPUT_LIMIT");
        } catch (IllegalArgumentException invalid) {
            return failure(mapping, null, mapping == null ? "INVALID_CONFIGURATION" : "CONFLICTING_MAPPINGS");
        }
    }
    private static CorrelationPreview failure(CorrelationMapping mapping, String selector, String reason) {
        return new CorrelationPreview(false, mapping == null ? null : mapping.mode(), null, false,
                mapping != null && mapping.mode() == CorrelationMapping.Mode.REQUIRE_EXISTING, selector, reason);
    }
    /** Explicit bounded JSON projection shared by HTTP and CLI adapters.
     * @return public sample result with no authority or stored execution content
     */
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("valid", valid); result.put("mode", mode == null ? null : mode.name());
        result.put("generatedIdentity", generatedIdentity); result.put("requiresExistingHistory", requiresExistingHistory);
        result.put("selector", selector); result.put("reason", reason);
        Map<String, Object> fields = null;
        if (context != null) {
            fields = new LinkedHashMap<>(); fields.put("applicationCorrelationId", context.applicationCorrelationId());
            fields.put("contributionId", context.contributionId().orElse(null)); fields.put("contributionKind", context.contributionKind().orElse(null));
            fields.put("roundId", context.roundId()); fields.put("publicReference", context.publicReference().orElse(null));
        }
        result.put("applicationContext", fields);
        return Collections.unmodifiableMap(result);
    }
    /** Encodes the explicit public sample result within the finite history response ceiling.
     * @return bounded JSON object suitable for a local authoring preview
     */
    public String toJson() {
        return PayloadJson.write(PayloadValue.fromJava(toMap(), BusinessHistoryPolicy.DEFAULT_RESPONSE_LIMITS));
    }
}
