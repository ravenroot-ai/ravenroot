package ai.ravenroot.server.payload;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.programming.ProgramArtifactIdentity;
import ai.ravenroot.api.programming.ProgramTestPayload;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bounded transport for one graph-level program readiness/build operation. */
public record ProgramBuildSubmission(List<Item> programs) {
    public static final int MAX_PROGRAMS = 256;

    public ProgramBuildSubmission {
        programs = List.copyOf(programs);
        if (programs.isEmpty() || programs.size() > MAX_PROGRAMS) {
            throw new IllegalArgumentException("one to " + MAX_PROGRAMS + " programs are required");
        }
    }

    public static ProgramBuildSubmission read(byte[] body, PayloadLimits limits) {
        PayloadValue root = PayloadJson.read(body, limits);
        if (!(root instanceof PayloadValue.MapValue object)
                || !(object.entries().get("programs") instanceof PayloadValue.ListValue programs)) {
            throw new IllegalArgumentException("programs array is required");
        }
        var result = new java.util.ArrayList<Item>();
        for (PayloadValue value : programs.values()) {
            if (!(value instanceof PayloadValue.MapValue item)) {
                throw new IllegalArgumentException("each program must be an object");
            }
            String nodeId = requiredText(item, "nodeId");
            String language = requiredText(item, "language");
            String source = requiredTextPreservingWhitespace(item, "source");
            String testPayload = optionalText(item, "testPayload", ProgramTestPayload.DEFAULT_TEXT);
            ProgramArtifactIdentity.sha256(language, source);
            result.add(new Item(nodeId, language, source, testPayload,
                    ProgramTestPayload.parse(testPayload)));
        }
        return new ProgramBuildSubmission(result);
    }

    public static Approval readApproval(byte[] body, PayloadLimits limits) {
        PayloadValue root = PayloadJson.read(body, limits);
        if (!(root instanceof PayloadValue.MapValue object)
                || !(object.entries().get("artifactIds") instanceof PayloadValue.ListValue ids)) {
            throw new IllegalArgumentException("artifactIds array is required");
        }
        if (ids.values().isEmpty() || ids.values().size() > MAX_PROGRAMS) {
            throw new IllegalArgumentException("one to " + MAX_PROGRAMS + " artifact ids are required");
        }
        var result = new java.util.ArrayList<String>();
        for (PayloadValue id : ids.values()) {
            if (!(id instanceof PayloadValue.TextValue text) || text.value().isBlank()) {
                throw new IllegalArgumentException("artifact id must be text");
            }
            result.add(text.value());
        }
        return new Approval(result, requiredText(object, "reason"));
    }

    private static String requiredText(PayloadValue.MapValue object, String name) {
        String value = requiredTextPreservingWhitespace(object, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }

    private static String requiredTextPreservingWhitespace(PayloadValue.MapValue object, String name) {
        if (!(object.entries().get(name) instanceof PayloadValue.TextValue text)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return text.value();
    }

    private static String optionalText(PayloadValue.MapValue object, String name, String fallback) {
        PayloadValue value = object.entries().get(name);
        if (value == null) return fallback;
        if (!(value instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException(name + " must be text");
        return text.value();
    }

    public record Item(String nodeId, String language, String source, String testPayloadText, Object testPayload) { }
    public record Approval(List<String> artifactIds, String reason) {
        public Approval { artifactIds = List.copyOf(artifactIds); }
    }
}
