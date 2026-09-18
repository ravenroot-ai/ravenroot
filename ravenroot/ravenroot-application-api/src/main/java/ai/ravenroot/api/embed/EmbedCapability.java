package ai.ravenroot.api.embed;

/** Capability granted to an embedded-browser session. */
public enum EmbedCapability {
    /** Permits read-only projection of the granted graph. */
    GRAPH_READ,
    /** Explicit opt-in to one deployment's bounded, read-only runtime observation. */
    DEPLOYMENT_OBSERVE
}
