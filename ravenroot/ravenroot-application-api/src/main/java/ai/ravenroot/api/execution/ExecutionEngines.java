package ai.ravenroot.api.execution;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Defines the execution engines contract exposed to Ravenroot integrators.
 */
public final class ExecutionEngines {
    private ExecutionEngines() {
    }

/**
 * Locates a runtime adapter by its service-provider ID and creates its engine.
 * @param id case-insensitive ID advertised by an {@link ExecutionEngineProvider}
 * @param systemName adapter-specific runtime or actor-system name
 * @return a newly created engine from the selected provider
 */
    public static ExecutionEngine create(String id, String systemName) {
        return providers().stream()
                .filter(provider -> provider.id().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown execution engine '" + id + "'. Available engines: " + available()))
                .create(systemName);
    }

/**
 * Lists the runtime adapter IDs visible through Java's service loader.
 * @return immutable, sorted provider IDs available on the application class path
 */
    public static List<String> available() {
        return providers().stream().map(ExecutionEngineProvider::id).sorted().toList();
    }

    private static List<ExecutionEngineProvider> providers() {
        return ServiceLoader.load(ExecutionEngineProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }
}
