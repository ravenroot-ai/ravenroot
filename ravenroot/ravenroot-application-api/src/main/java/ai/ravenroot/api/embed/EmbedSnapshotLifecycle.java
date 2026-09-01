package ai.ravenroot.api.embed;

/**
 * The graph-version lifecycle states a provision may be built on, verified once and then frozen.
 *
 * <p>These two names are the subset of the definition lifecycle admitted by browser projection.
 * The enum is deliberately not the control plane's own type: this module is engine-neutral, and a
 * provisioner that could pass {@code VALIDATED} or {@code RETIRED} through as an opaque string would
 * make the acceptance rule a runtime string comparison instead of a type.</p>
 */
public enum EmbedSnapshotLifecycle {
    /** Version is published and eligible for an embed projection. */
    PUBLISHED,
    /** Version is active and eligible for an embed projection. */
    ACTIVE
}
