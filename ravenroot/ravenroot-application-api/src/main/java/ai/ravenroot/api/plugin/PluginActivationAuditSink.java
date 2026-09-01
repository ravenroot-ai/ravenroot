package ai.ravenroot.api.plugin;

/**
 * Records every plugin bundle activation outcome to the durable audit trail (PLAT-12).
 *
 * <p>Same shape as {@code AuthorizationAuditSink} and {@code ArtifactLifecycleAuditSink}: a single
 * functional method, implemented in {@code ravenroot-server} against the concrete {@code AuditTrail}
 * so that the plugin loader (in {@code ravenroot-plugin-bundle}) never depends on the audit
 * infrastructure directly.</p>
 */
@FunctionalInterface
public interface PluginActivationAuditSink {
/**
 * Persists one activation outcome without changing the loader's outcome.
 * @param event immutable activation event, including its correlation identifier and diagnostic detail.
 */
    void record(PluginActivationEvent event);
}
