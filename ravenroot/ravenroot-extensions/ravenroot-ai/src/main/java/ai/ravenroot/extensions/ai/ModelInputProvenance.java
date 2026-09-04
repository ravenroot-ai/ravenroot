package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.provenance.SyntheticProvenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-invocation, payload-free provenance for every untrusted value sent to a model. */
final class ModelInputProvenance {
    static final String AGENT_ATTRIBUTE = "agent.inputProvenance";
    static final String PROMPT_ATTRIBUTE = "llm.inputProvenance";
    private static final int MAX_ENTRIES = 4096;

    enum Kind {
        GRAPH_INSTRUCTIONS,
        GRAPH_OBJECTIVE,
        RENDERED_PROMPT,
        INBOUND_PAYLOAD,
        INBOUND_ATTRIBUTES,
        GENERATED_SYSTEM_MESSAGE,
        GENERATED_AUTHOR_MESSAGE,
        TOOL_DESCRIPTION,
        TOOL_RESULT,
        MODEL_OUTPUT
    }

    private final List<Map<String, Object>> entries = new ArrayList<>();

    void add(Kind kind, String source, Object content) {
        if (entries.size() >= MAX_ENTRIES) {
            // Omitting an input would make the provenance claim incomplete. Refuse the invocation
            // instead of silently truncating the evidence an operator relies on.
            throw new IllegalStateException("model input provenance limit exceeded");
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("sequence", entries.size() + 1);
        entry.put("kind", kind.name());
        entry.put("source", safeSource(source));
        entry.put("digest", SyntheticProvenance.bind(content).orElse(SyntheticProvenance.UNBOUND));
        entries.add(Map.copyOf(entry));
    }

    List<Map<String, Object>> snapshot() {
        return List.copyOf(entries);
    }

    /** Restores only the bounded payload-free projection written by {@link #snapshot()}. */
    void restore(Object value) {
        if (!(value instanceof List<?> restored) || restored.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid model input provenance checkpoint");
        }
        entries.clear();
        for (int index = 0; index < restored.size(); index++) {
            if (!(restored.get(index) instanceof Map<?, ?> raw) || raw.size() != 4
                    || !(raw.get("sequence") instanceof Number sequence)
                    || sequence.longValue() != index + 1L
                    || !(raw.get("kind") instanceof String kind)
                    || !(raw.get("source") instanceof String source)
                    || !(raw.get("digest") instanceof String digest)
                    || !source.equals(safeSource(source))
                    || !digest.matches("(?:sha256:[0-9a-f]{64}|unbound)")) {
                throw new IllegalArgumentException("invalid model input provenance checkpoint");
            }
            Kind.valueOf(kind);
            entries.add(Map.of("sequence", index + 1, "kind", kind,
                    "source", source, "digest", digest));
        }
    }

    private static String safeSource(String source) {
        String raw = source == null ? "" : source;
        var safe = new StringBuilder(Math.min(raw.length(), 128));
        raw.codePoints().limit(128).filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }
}
