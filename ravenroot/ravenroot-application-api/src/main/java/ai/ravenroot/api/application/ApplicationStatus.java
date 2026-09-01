package ai.ravenroot.api.application;

import java.util.Set;

/**
 * Immutable summary of an application's currently selected execution engine and its advertised
 * capabilities. It is returned by {@link RavenrootApplication#status()} for a caller that needs to
 * decide which operations the selected runtime can accept.
 *
 * @param state lifecycle state reported by the application implementation
 * @param executionEngine stable identifier of the runtime serving the application
 * @param capabilities immutable capability names advertised by that runtime; empty when it
 *                     advertises none
 */
public record ApplicationStatus(String state, String executionEngine, Set<String> capabilities) {
/**
 * Defensively copies the capability set so a status snapshot cannot be changed by its caller.
 */
    public ApplicationStatus {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
