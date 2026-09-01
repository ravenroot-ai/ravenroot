package ai.ravenroot.api.execution;

/** Service-provider entry point implemented by each runtime adapter. */
public interface ExecutionEngineProvider {
/**
 * Returns the selection key used by {@link ExecutionEngines#create(String, String)}.
 * @return a stable provider identifier
 */
    String id();

/**
 * Creates an engine owned by this adapter.
 * @param systemName adapter-specific name assigned to the underlying runtime
 * @return an independent execution engine instance
 */
    ExecutionEngine create(String systemName);
}
