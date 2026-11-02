package ai.ravenroot.api.application;

/** A known deployment whose presentation cannot currently be served. Never means not found. */
public final class DeploymentViewException extends RuntimeException {
    public enum Reason { DEFINITION_UNAVAILABLE, PROJECTION_UNAVAILABLE }
    private final Reason reason;
    public DeploymentViewException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
