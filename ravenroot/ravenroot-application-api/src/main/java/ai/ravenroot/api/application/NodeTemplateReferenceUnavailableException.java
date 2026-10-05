package ai.ravenroot.api.application;

/**
 * Signals that a sanitized saved node names operator-owned authority unavailable in its destination tenant.
 *
 * <p>The exception carries no public detail. HTTP adapters map the type to a fixed error vocabulary;
 * behavior and resolver diagnostics remain server-side.</p>
 */
public final class NodeTemplateReferenceUnavailableException extends IllegalArgumentException {
    /** Creates the detail-free classification for an unavailable saved-node reference. */
    public NodeTemplateReferenceUnavailableException() {
        super("node template reference is unavailable");
    }

    /**
     * Creates the detail-free public classification while retaining the server-side diagnostic.
     *
     * @param cause private destination-reference failure retained for server diagnostics
     */
    public NodeTemplateReferenceUnavailableException(Throwable cause) {
        super("node template reference is unavailable", cause);
    }
}
