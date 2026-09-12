package ai.ravenroot.api.persistence;

import java.util.regex.Pattern;

/**
 * Stable logical identity of one immutable version of a graph definition.
 *
 * <p>This is the identity a human or a catalog assigns — the graph a definition belongs to and the
 * version within it — and it is independent of {@link GraphContentId}, which is the address the
 * bytes themselves hash to. Keeping the two separate is what makes both of the following legal and
 * distinguishable: one document bound under two logical identities, which happens whenever a graph
 * is copied and published under a new name; and one logical identity that must never be rebound to
 * different content, which is what "immutable version" means and what a store enforces with
 * {@link GraphDefinitionStoreFailure.IdentityConflict}.</p>
 *
 * <p>The accepted character set matches the one the graph versioning contract already uses for a
 * version key, so the two cannot drift apart into identities that one layer accepts and the other
 * rejects.</p>
 *
 * @param graphId stable logical identifier of the graph a definition version belongs to.
 * @param versionId stable identifier of the immutable version within that graph.
 */
public record GraphDefinitionIdentity(String graphId, String versionId) {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /**
     * Reserved graph identifier used by an execution submitted as a document rather than selected
     * from a catalog.
     */
    public static final String SUBMISSION_GRAPH_ID = "submission";

    /** Rejects an identity that cannot stably address one immutable graph version. */
    public GraphDefinitionIdentity {
        graphId = requireStableId(graphId, "graphId");
        versionId = requireStableId(versionId, "versionId");
    }

    /**
     * Returns the identity of a definition submitted directly as a document, where the content
     * address is the only stable version identifier that exists.
     *
     * @param contentId address of the submitted canonical document.
     * @return identity under the reserved submission graph identifier.
     */
    public static GraphDefinitionIdentity forSubmission(GraphContentId contentId) {
        if (contentId == null) {
            throw new IllegalArgumentException("contentId cannot be null");
        }
        return new GraphDefinitionIdentity(SUBMISSION_GRAPH_ID, contentId.value());
    }

    private static String requireStableId(String value, String name) {
        if (value == null || !STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " must be 1 to 128 stable identifier characters from [A-Za-z0-9._:-] "
                    + "and must not begin with a punctuation character");
        }
        return value;
    }
}
