package ai.ravenroot.api.embed;

/**
 * Lifecycle of one embed registration aggregate.
 *
 * <p>There is no third member on purpose. A registration is either the authority for a live embed
 * session family or it is permanently withdrawn: {@link #REVOKED} is terminal, and a revoked
 * registration id is never provisioned back into {@link #ACTIVE}. A "suspended" or "draft" member
 * would be a state in which the answer to «may this session read?» depends on something other than
 * the aggregate, which is exactly the coupling this type exists to remove.</p>
 */
public enum EmbedRegistrationState {
    /** Registration may authorize new embed sessions. */
    ACTIVE,
    /** Registration is terminally withdrawn and can never become active again. */
    REVOKED
}
