package ai.ravenroot.core.graph;

import java.util.regex.Pattern;

/** Stable logical identity of one immutable version of a graph definition. */
public record GraphVersionKey(String graphId, String versionId) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public GraphVersionKey {
        graphId = requireId(graphId, "graphId");
        versionId = requireId(versionId, "versionId");
    }

    private static String requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " must be 1-128 stable identifier characters: [A-Za-z0-9._:-]");
        }
        return value;
    }
}
