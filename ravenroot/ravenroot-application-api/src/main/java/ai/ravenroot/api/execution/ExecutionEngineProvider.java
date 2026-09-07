package ai.ravenroot.api.execution;

import java.util.Objects;

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

    /**
     * Creates an engine using an explicit operational policy.
     *
     * <p>Providers compiled against the historical SPI safely accept only the frozen legacy policy.
     * A provider that supports configurable policy overrides this method and applies every policy
     * component rather than silently ignoring one.</p>
     *
     * @param systemName adapter-specific name assigned to the underlying runtime
     * @param policy immutable policy for the new engine
     * @return an independent execution engine instance
     * @throws NullPointerException if {@code policy} is missing
     * @throws IllegalArgumentException if this provider does not support the requested policy
     */
    default ExecutionEngine create(String systemName, ExecutionEnginePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (!ExecutionEnginePolicy.FROZEN_LEGACY.equals(policy)) {
            throw new IllegalArgumentException("Execution engine provider does not support the requested policy");
        }
        return create(systemName);
    }
}
