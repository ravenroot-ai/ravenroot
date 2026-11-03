package ai.ravenroot.api.application;

/** A known deployment whose presentation cannot currently be served. Never means not found. */
public final class DeploymentViewException extends RuntimeException {
    /** Closed reasons why an existing deployment cannot supply a browser-safe view. */
    public enum Reason {
        /** The retained immutable graph definition is unavailable. */
        DEFINITION_UNAVAILABLE,
        /** A browser-safe projection cannot be produced from the retained definition. */
        PROJECTION_UNAVAILABLE
    }
    /** Presentation failure category, without exposing retained source content. */
    private final Reason reason;

    /**
     * Creates a presentation failure for an existing deployment.
     * @param reason failure category also used as the exception message
     */
    public DeploymentViewException(Reason reason) { super(reason.name()); this.reason = reason; }

    /**
     * Returns the closed presentation failure category.
     * @return reason the existing deployment cannot currently be presented
     */
    public Reason reason() { return reason; }
}
