package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic, length-safe text encoding for bounded replay metadata stored beside payload bytes. */
public final class ReplayMetadataCodec {
    private ReplayMetadataCodec() {}

    /**
     * Encodes parent identities.
     *
     * @param parents parent identities
     * @return deterministic text encoding
     */
    public static String parents(Set<UUID> parents) {
        return parents.stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Decodes parent identities.
     *
     * @param encoded encoded identities
     * @return immutable decoded identities
     */
    public static Set<UUID> parents(String encoded) {
        var values = new LinkedHashSet<UUID>();
        if (encoded == null || encoded.isEmpty()) return Set.of();
        for (String token : encoded.split(",", -1)) values.add(UUID.fromString(token));
        return Set.copyOf(values);
    }

    /**
     * Encodes iteration coordinates.
     *
     * @param iteration iteration coordinates
     * @return deterministic text encoding
     */
    public static String iteration(Map<String, Integer> iteration) {
        return iteration.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> token(entry.getKey()) + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Decodes iteration coordinates.
     *
     * @param encoded encoded iteration coordinates
     * @return immutable decoded coordinates
     */
    public static Map<String, Integer> iteration(String encoded) {
        var values = new LinkedHashMap<String, Integer>();
        if (encoded == null || encoded.isEmpty()) return Map.of();
        for (String item : encoded.split(",", -1)) {
            int split = item.lastIndexOf(':');
            if (split < 1) throw new IllegalArgumentException("invalid replay iteration metadata");
            values.put(text(item.substring(0, split)), Integer.parseInt(item.substring(split + 1)));
        }
        return Map.copyOf(values);
    }

    /**
     * Encodes ordered replay boundaries.
     *
     * @param seeds ordered replay boundaries
     * @return deterministic text encoding
     */
    public static String seeds(List<ReplayBoundarySeed> seeds) {
        return seeds.stream().map(seed -> token(seed.nodeId()) + "=" + parents(seed.predecessorInvocationIds()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * Decodes replay boundaries.
     *
     * @param encoded encoded replay boundaries
     * @return immutable decoded boundaries
     */
    public static List<ReplayBoundarySeed> seeds(String encoded) {
        var values = new ArrayList<ReplayBoundarySeed>();
        if (encoded == null || encoded.isEmpty()) return List.of();
        for (String item : encoded.split(";", -1)) {
            int split = item.indexOf('=');
            if (split < 1) throw new IllegalArgumentException("invalid replay boundary metadata");
            values.add(new ReplayBoundarySeed(text(item.substring(0, split)), parents(item.substring(split + 1))));
        }
        return List.copyOf(values);
    }

    private static String token(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
