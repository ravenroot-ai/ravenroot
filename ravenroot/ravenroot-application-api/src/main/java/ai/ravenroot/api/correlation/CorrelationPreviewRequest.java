package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.*;
import java.util.*;

/** Caller-supplied authoring sample; no stored execution or connector content is fetched.
 * @param properties explicit shared mapping controls
 * @param sample actual exposed structured input, including an explicit JSON null
 */
public record CorrelationPreviewRequest(Map<String, Object> properties, Object sample) {
    /** Copies the bounded configuration; evaluation applies the operator's input limits to the sample. */
    public CorrelationPreviewRequest {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        if (properties.size() > 8) throw new IllegalArgumentException("too many correlation mapping controls");
    }
    /** Reads the exact bounded request shared by HTTP and CLI adapters.
     * @param document UTF-8 JSON request containing only properties and sample
     * @param limits finite request document bounds
     * @return explicit caller-supplied mapping and sample
     */
    public static CorrelationPreviewRequest read(byte[] document, PayloadLimits limits) {
        if (!(PayloadJson.read(document, limits) instanceof PayloadValue.MapValue root)
                || !root.entries().keySet().equals(Set.of("properties", "sample"))
                || !(root.entries().get("properties") instanceof PayloadValue.MapValue configuration))
            throw new IllegalArgumentException("expected properties and sample");
        var properties = new LinkedHashMap<String, Object>();
        configuration.entries().forEach((name, value) -> properties.put(name, value.toJava()));
        return new CorrelationPreviewRequest(properties, root.entries().get("sample").toJava());
    }
}
