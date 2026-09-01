package ai.ravenroot.api.node.service;

/**
 * Selects an operator-owned dynamic signing grant without carrying signing authority or secrets.
 *
 * @param bindingId exact operator-owned signing grant
 */
public record OutboundHttpSigning(String bindingId) {
    /**
     * Validates the opaque signing-grant selector. It is intentionally not a signature or key.
     */
    public OutboundHttpSigning {
        bindingId = bindingId == null ? "" : bindingId.strip();
        if (bindingId.isEmpty() || bindingId.length() > 256) {
            throw new IllegalArgumentException("bindingId must contain 1-256 characters");
        }
        for (int i = 0; i < bindingId.length(); i++) {
            if (Character.isISOControl(bindingId.charAt(i))) {
                throw new IllegalArgumentException("bindingId contains a control character");
            }
        }
    }
}
