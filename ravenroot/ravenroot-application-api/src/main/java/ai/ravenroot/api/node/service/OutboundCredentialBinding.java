package ai.ravenroot.api.node.service;

/**
 * Selects an operator-owned credential placement without carrying placement authority itself.
 *
 * @param bindingId operator-owned binding that fixes destination, header/handshake placement and prefix
 * @param reference opaque tenant-scoped credential reference
 */
public record OutboundCredentialBinding(String bindingId, String reference) {
    /**
     * Normalizes the two opaque routing tokens and refuses empty, oversized, or control-bearing data.
     * The values are selectors; this value never contains credential characters.
     */
    public OutboundCredentialBinding {
        bindingId = requireSafeToken(bindingId, "bindingId");
        reference = requireSafeToken(reference, "reference");
    }

    private static String requireSafeToken(String value, String name) {
        String safe = value == null ? "" : value.strip();
        if (safe.isEmpty() || safe.length() > 256) {
            throw new IllegalArgumentException(name + " must contain 1-256 characters");
        }
        for (int i = 0; i < safe.length(); i++) {
            if (Character.isISOControl(safe.charAt(i))) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return safe;
    }
}
