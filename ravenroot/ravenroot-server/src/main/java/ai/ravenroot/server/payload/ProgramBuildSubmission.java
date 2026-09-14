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
    /** Compatibility ceiling; operator policy may narrow it. */
    public static final int MAX_PROGRAMS = 256;

    public ProgramBuildSubmission {
        programs = List.copyOf(programs);
        if (programs.isEmpty() || programs.size() > MAX_PROGRAMS) {
            throw new IllegalArgumentException("one to " + MAX_PROGRAMS + " programs are required");
        }
    }

    public static ProgramBuildSubmission read(byte[] body, PayloadLimits limits) {
        return read(body, limits, ai.ravenroot.api.programming.ProgramAuthoringLimits.DEFAULTS);
    }

    public static ProgramBuildSubmission read(byte[] body, PayloadLimits limits,
                                              ai.ravenroot.api.programming.ProgramAuthoringLimits authoring) {
        PayloadValue root = PayloadJson.read(body, buildEnvelopeLimits(limits, authoring));
        enforceGenericTextLimitsOutsideProgramSource(root, limits);
        if (!(root instanceof PayloadValue.MapValue object)
                || !(object.entries().get("programs") instanceof PayloadValue.ListValue programs)) {
            throw new IllegalArgumentException("programs array is required");
        }
        var result = new java.util.ArrayList<Item>();
        authoring.requireProgramCount(programs.values().size());
        for (PayloadValue value : programs.values()) {
            if (!(value instanceof PayloadValue.MapValue item)) {
                throw new IllegalArgumentException("each program must be an object");
            }
            String nodeId = requiredText(item, "nodeId");
            String language = requiredText(item, "language");
            String source = requiredTextPreservingWhitespace(item, "source");
            authoring.requireSource(source);
            String testPayload = optionalText(item, "testPayload", ProgramTestPayload.DEFAULT_TEXT);
            ProgramArtifactIdentity.sha256(language, source);
            result.add(new Item(nodeId, language, source, testPayload,
                    ProgramTestPayload.parse(testPayload)));
        }
        return new ProgramBuildSubmission(result);
    }

    /**
     * Preserves the server's structural JSON limits while making the selected authoring byte
     * ceilings reachable by the build route. The source byte limit is still enforced after parsing,
     * because JSON text length counts UTF-16 code units rather than encoded UTF-8 bytes.
     */
    static PayloadLimits buildEnvelopeLimits(
            PayloadLimits structural,
            ai.ravenroot.api.programming.ProgramAuthoringLimits authoring) {
        java.util.Objects.requireNonNull(structural, "structural");
        java.util.Objects.requireNonNull(authoring, "authoring");
        return new PayloadLimits(
                authoring.maxBuildRequestBytes(),
                structural.maxDepth(),
                structural.maxCollectionSize(),
                structural.maxValueCount(),
                Math.max(structural.maxTextLength(), authoring.maxSourceBytes()),
                structural.maxKeyLength());
    }

    private static void enforceGenericTextLimitsOutsideProgramSource(
            PayloadValue root, PayloadLimits structural) {
        if (!(root instanceof PayloadValue.MapValue object)) {
            enforceGenericTextLimits(root, structural);
            return;
        }
        for (var entry : object.entries().entrySet()) {
            if (!"programs".equals(entry.getKey()) || !(entry.getValue() instanceof PayloadValue.ListValue programs)) {
                enforceGenericTextLimits(entry.getValue(), structural);
                continue;
            }
            for (PayloadValue program : programs.values()) {
                if (!(program instanceof PayloadValue.MapValue item)) {
                    enforceGenericTextLimits(program, structural);
                    continue;
                }
                for (var field : item.entries().entrySet()) {
                    if (!"source".equals(field.getKey())) {
                        enforceGenericTextLimits(field.getValue(), structural);
                    }
                }
            }
        }
    }

    private static void enforceGenericTextLimits(PayloadValue value, PayloadLimits structural) {
        switch (value) {
            case PayloadValue.TextValue ignored -> structural.enforce(value);
            case PayloadValue.ListValue list -> list.values().forEach(item ->
                    enforceGenericTextLimits(item, structural));
            case PayloadValue.MapValue map -> map.entries().values().forEach(item ->
                    enforceGenericTextLimits(item, structural));
            default -> { }
        }
    }

    public static Approval readApproval(byte[] body, PayloadLimits limits) {
        return readApproval(body, limits, ai.ravenroot.api.programming.ProgramAuthoringLimits.DEFAULTS);
    }

    public static Approval readApproval(byte[] body, PayloadLimits limits,
                                        ai.ravenroot.api.programming.ProgramAuthoringLimits authoring) {
        PayloadValue root = PayloadJson.read(body, limits);
        if (!(root instanceof PayloadValue.MapValue object)
                || !(object.entries().get("artifactIds") instanceof PayloadValue.ListValue ids)) {
            throw new IllegalArgumentException("artifactIds array is required");
        }
        authoring.requireProgramCount(ids.values().size());
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
