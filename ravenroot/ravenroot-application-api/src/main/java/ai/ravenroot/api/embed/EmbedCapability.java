package ai.ravenroot.api.embed;

/** Capability granted to an embedded-browser session. */
public enum EmbedCapability {
    /** Permits read-only projection of the granted graph. */
    GRAPH_READ,
    /** Explicit opt-in to one deployment's bounded, read-only runtime observation. */
    DEPLOYMENT_OBSERVE,
    /** Lists only authorized runs of the exact deployment source bound to the session. */
    DEPLOYMENT_RUN_READ,
    /** Starts one idempotent traversal of the exact immutable deployment source bound to the session. */
    DEPLOYMENT_EXECUTE
}
