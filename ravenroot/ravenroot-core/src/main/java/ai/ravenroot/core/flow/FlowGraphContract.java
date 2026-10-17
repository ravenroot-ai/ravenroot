package ai.ravenroot.core.flow;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.graph.GraphManager;

import java.io.ByteArrayInputStream;
import java.util.Map;

/** Graph-level contract interpreted only by the intergraph admission path. */
final class FlowGraphContract {
    private static final String CALLABLE = "callable";
    private final Schema input;
    private final Schema output;

    private FlowGraphContract(Schema input, Schema output) {
        this.input = input;
        this.output = output;
    }

    static FlowGraphContract read(byte[] graphMl) {
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(graphMl))) {
            Map<String, Object> properties = manager.definition().properties();
            Object callable = properties.get(CALLABLE);
            if (callable != null && !booleanValue(callable, CALLABLE)) {
                throw new IllegalArgumentException("target graph refuses intergraph calls (callable=false)");
            }
            return new FlowGraphContract(schema(properties, "call.input"), schema(properties, "call.output"));
        }
    }

    Object input(Object value) { return input == null ? bounded(value) : input.validate(value); }
    Object output(Object value) { return output == null ? bounded(value) : output.validate(value); }

    private static Object bounded(Object value) {
        byte[] encoded = PayloadJson.writeJava(value, PayloadLimits.DEFAULTS);
        return PayloadJson.read(encoded, PayloadLimits.DEFAULTS).toJava();
    }

    private static Schema schema(Map<String, Object> properties, String prefix) {
        String name = text(properties.get(prefix + ".schema"));
        String version = text(properties.get(prefix + ".schemaVersion"));
        String kind = text(properties.get(prefix + ".kind"));
        int present = (name == null ? 0 : 1) + (version == null ? 0 : 1) + (kind == null ? 0 : 1);
        if (present == 0) return null;
        if (present != 3) throw new IllegalArgumentException(prefix + " schema declaration is incomplete");
        try {
            return new Schema(name, version, PayloadKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(prefix + ".kind is invalid", malformed);
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean booleanValue(Object value, String property) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException(property + " must be true or false");
    }

    private record Schema(String name, String version, PayloadKind kind) {
        Object validate(Object value) {
            byte[] encoded = PayloadJson.writeJava(value, PayloadLimits.DEFAULTS);
            PayloadEnvelope envelope = PayloadJson.readEnvelope(encoded, PayloadLimits.DEFAULTS);
            if (!name.equals(envelope.schema()) || !version.equals(envelope.schemaVersion())
                    || kind != envelope.kind()) {
                throw new IllegalArgumentException("flow payload does not match declared " + name + "@" + version);
            }
            return envelope.value().toJava();
        }
    }
}
