package ai.ravenroot.api.ai;

import java.util.Map;

/**
 * Result returned by an agent runtime after one delegation.
 * @param payload resulting application payload, which may be {@code null}
 * @param runtimeId runtime implementation identifier; an absent value becomes an empty string
 * @param sessionId runtime-selected conversation-session identifier; an absent value becomes an empty string
 * @param metadata immutable supplemental response data; an absent map becomes empty
 */
public record AgentResponse(Object payload, String runtimeId, String sessionId, Map<String, Object> metadata) {
/**
 * Normalizes nullable identifiers and snapshots metadata before the response is exposed.
 */
    public AgentResponse {
        runtimeId = runtimeId == null ? "" : runtimeId;
        sessionId = sessionId == null ? "" : sessionId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
