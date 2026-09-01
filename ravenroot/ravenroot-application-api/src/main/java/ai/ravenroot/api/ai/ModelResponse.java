package ai.ravenroot.api.ai;

import java.util.Map;

/**
 * Vendor-neutral result of one model completion.
 * @param payload generated payload, which may be {@code null}
 * @param providerId provider implementation identifier; normalized to an empty string when absent
 * @param model resolved model identifier; normalized to an empty string when absent
 * @param metadata immutable provider metadata; an absent map becomes empty
 */
public record ModelResponse(Object payload, String providerId, String model, Map<String, Object> metadata) {
/**
 * Normalizes nullable identifiers and snapshots metadata before publication.
 */
    public ModelResponse {
        providerId = providerId == null ? "" : providerId;
        model = model == null ? "" : model;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
