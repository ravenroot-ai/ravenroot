package ai.ravenroot.api.ai;

import java.util.Map;
import java.util.UUID;

/**
 * Vendor-neutral input for one model completion.
 * @param executionId non-null execution that owns this completion
 * @param nodeId non-blank graph node requesting the completion
 * @param prompt non-blank rendered prompt
 * @param payload application payload supplied to the provider adapter, possibly {@code null}
 * @param model optional model selector; normalized to an empty string
 * @param credentialReference optional secret reference, never secret material; normalized to an empty string
 * @param parameters optional immutable provider-specific parameters
 */
public record ModelRequest(
        UUID executionId,
        String nodeId,
        String prompt,
        Object payload,
        String model,
        String credentialReference,
        Map<String, Object> parameters) {

/**
 * Rejects missing execution identity, node, or prompt; normalizes optional strings and freezes parameters.
 */
    public ModelRequest {
        if (executionId == null) throw new IllegalArgumentException("executionId cannot be null");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt cannot be blank");
        model = model == null ? "" : model;
        credentialReference = credentialReference == null ? "" : credentialReference;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
