package ai.ravenroot.api.node.service;

/** Operator-granted services a Node SDK behavior may require. */
public enum NodePackageCapability {
    /** Permission to resolve tenant-bound credentials through {@link NodeCredentialService}. */
    CREDENTIAL_RESOLUTION("credential-resolution"),
    /** Permission to submit requests through the constrained managed HTTP client. */
    OUTBOUND_HTTP("outbound-http"),
    /** Permission to open sessions through the constrained managed WebSocket client. */
    OUTBOUND_WEBSOCKET("outbound-websocket");

    private final String capabilityName;

    NodePackageCapability(String capabilityName) {
        this.capabilityName = capabilityName;
    }

/**
 * Stable, non-sensitive name suitable for startup inventory and audit dimensions.
     * @return stable inventory/audit token; it neither grants the capability nor identifies a secret
 */
    public String capabilityName() {
        return capabilityName;
    }
}
