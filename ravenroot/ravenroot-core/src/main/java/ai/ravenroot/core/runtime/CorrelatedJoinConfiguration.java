package ai.ravenroot.core.runtime;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.core.graph.GraphNode;
import java.util.*;

/** Authored join policy; event data may announce/seal only through explicitly trusted entry nodes. */
record CorrelatedJoinConfiguration(Mode mode, Integer count, Set<String> expected,
        Optional<CorrelationSelector> countSelector, Optional<CorrelationSelector> manifestSelector,
        Optional<CorrelationSelector> sealSelector, Set<String> trustedProducerEntries) {
    enum Mode { NONE, FIXED_COUNT, FIXED_MANIFEST, DYNAMIC_MANIFEST }
    record Announcement(Set<String> expected, Integer count, boolean seal, boolean controlAllowed) { }
    static final String PREFIX = "correlatedJoin.";
    static boolean enabled(GraphNode node) { return !Objects.toString(node.properties().get(PREFIX + "mode"), "NONE").equals("NONE"); }
    static CorrelatedJoinConfiguration read(GraphNode node, BusinessHistoryPolicy policy) {
        var properties = node.properties();
        final Mode mode;
        try { mode = Mode.valueOf(Objects.toString(properties.get(PREFIX + "mode"), "NONE")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid correlatedJoin.mode"); }
        var limits = policy.selection();
        Integer count = null;
        Object raw = properties.get(PREFIX + "expectedCount");
        if (raw != null) {
            try { count = Integer.valueOf(raw.toString()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid correlatedJoin.expectedCount"); }
            if (count < 0 || count > policy.maxContributionsPerJoin()) throw new IllegalArgumentException("correlated join expected count exceeds policy");
        }
        var expected = ids(properties.get(PREFIX + "expectedIds"), limits);
        var producers = ids(properties.get(PREFIX + "trustedProducerEntries"), limits);
        var countSelector = selector(properties, "expectedCountSelector", limits);
        var manifestSelector = selector(properties, "manifestSelector", limits);
        var sealSelector = selector(properties, "sealSelector", limits);
        if (mode == Mode.FIXED_COUNT && (count == null == countSelector.isEmpty())
                || mode == Mode.FIXED_MANIFEST && !properties.containsKey(PREFIX + "expectedIds")
                || mode != Mode.FIXED_COUNT && (count != null || countSelector.isPresent())
                || mode != Mode.FIXED_MANIFEST && !expected.isEmpty()
                || mode != Mode.DYNAMIC_MANIFEST && (manifestSelector.isPresent() || sealSelector.isPresent())
                || mode == Mode.DYNAMIC_MANIFEST && (manifestSelector.isEmpty() || sealSelector.isEmpty())
                || (countSelector.isPresent() || manifestSelector.isPresent() || sealSelector.isPresent()) && producers.isEmpty())
            throw new IllegalArgumentException("inconsistent correlated join expectation or trusted producer configuration");
        return new CorrelatedJoinConfiguration(mode, count, expected, countSelector, manifestSelector, sealSelector, producers);
    }
    Announcement announcement(String entryNodeId, PayloadValue input) {
        boolean trusted = trustedProducerEntries.contains(entryNodeId);
        if (mode == Mode.FIXED_COUNT) {
            Integer target = count;
            if (target == null && trusted) target = countSelector.orElseThrow().selectCount(input);
            return new Announcement(Set.of(), target, target != null, trusted);
        }
        if (mode == Mode.FIXED_MANIFEST) return new Announcement(expected, null, true, trusted);
        if (mode == Mode.DYNAMIC_MANIFEST && trusted) {
            var manifest = manifestSelector.orElseThrow().selectManifest(input);
            if (!(sealSelector.orElseThrow().select(input) instanceof PayloadValue.BooleanValue seal))
                throw new IllegalArgumentException("correlated join seal selector requires a boolean");
            return new Announcement(Set.copyOf(manifest), null, seal.value(), true);
        }
        return new Announcement(Set.of(), null, false, false);
    }
    private static Optional<CorrelationSelector> selector(Map<String, Object> properties, String name, CorrelationLimits limits) {
        Object value = properties.get(PREFIX + name);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text)) throw new IllegalArgumentException("invalid correlated join selector");
        return Optional.of(new CorrelationSelector(text, limits));
    }
    private static Set<String> ids(Object value, CorrelationLimits limits) {
        if (value == null) return Set.of();
        PayloadValue structured = value instanceof String text
                ? PayloadJson.read(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), limits.input())
                : PayloadValue.fromJava(value, limits.input());
        return Set.copyOf(new CorrelationSelector("", limits).selectManifest(structured));
    }
}
