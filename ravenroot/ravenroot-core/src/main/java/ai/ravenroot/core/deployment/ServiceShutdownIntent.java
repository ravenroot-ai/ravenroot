package ai.ravenroot.core.deployment;

/**
 * Whether a service-scoped shutdown is in effect, asked rather than assumed (ADR 0038 D7).
 *
 * <h2>Why shutdown is not a lifecycle command</h2>
 * <p>Global shutdown outranks every command in the deployment hierarchy and is deliberately not
 * <em>in</em> it. A generation is a fact about one {@code (tenant, deployment)} aggregate, so a
 * shutdown expressed as a generation move would have to advance the generation of every deployment
 * in the process — rewriting the lifecycle history of deployments nobody touched, and leaving an
 * operator reading a deployment's generation unable to tell which of the moves were about that
 * deployment at all. Shutdown carries its own service-scoped epoch instead, on the model of the
 * store-global agent-authority epoch, and reaches the lifecycle only as this one question.</p>
 *
 * <h2>Why an interface and not a flag</h2>
 * <p>The coordinator does not own service shutdown and must not be able to declare it: the epoch
 * belongs to whoever is shutting the service down, which is the composition root. Asking through a
 * port keeps the authority where it is and keeps the coordinator honest — it can read the intent and
 * refuse, and it has no way to set it.</p>
 */
@FunctionalInterface
public interface ServiceShutdownIntent {

    /** The intent of a service that is not shutting down: every lifecycle command is considered. */
    ServiceShutdownIntent RUNNING = () -> false;

    /**
     * Whether a service-scoped shutdown has been declared.
     *
     * <p>Read once per command rather than cached, because the whole point of the answer is that it
     * changes underneath the coordinator exactly once and must be observed when it does.</p>
     *
     * @return whether the service is shutting down and no further lifecycle command is accepted.
     */
    boolean inEffect();
}
