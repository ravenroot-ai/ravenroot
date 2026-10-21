package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded public classifier in a node-completion journal body; never input, output or raw diagnostics. */
public final class NodeOutcomeEventData {
    public static final String CONTENT_TYPE = "application/vnd.ravenroot.node-outcome+json;version=1";
    public static final int MAX_BYTES = 1024;
    private static final PayloadLimits LIMITS = new PayloadLimits(MAX_BYTES, 2, 4, 8, 256, 32);
    private NodeOutcomeEventData() { }
    public static OpaquePayload of(String outcome) {
        if (outcome == null || !outcome.matches("[A-Za-z0-9._:-]{1,128}"))
            return OpaquePayload.empty(CONTENT_TYPE);
        return OpaquePayload.of(PayloadJson.write(PayloadValue.fromJava(Map.of("outcome", outcome), LIMITS))
                .getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }
    public static Optional<String> outcome(OpaquePayload payload) {
        if (payload == null || !CONTENT_TYPE.equals(payload.contentType()) || payload.size() == 0 || payload.size() > MAX_BYTES)
            return Optional.empty();
        try {
            if (PayloadJson.read(payload.bytes(), LIMITS) instanceof PayloadValue.MapValue map
                    && map.entries().size() == 1 && map.entries().get("outcome") instanceof PayloadValue.TextValue text
                    && text.value().matches("[A-Za-z0-9._:-]{1,128}")) return Optional.of(text.value());
        } catch (IllegalArgumentException invalid) { /* Old/foreign/unreadable data is absent, never coerced. */ }
        return Optional.empty();
    }
}
