package ai.ravenroot.extensions.ai;

import java.util.Optional;

/**
 * Where an {@code agent} node's declared MCP server names are turned into operator-owned profiles.
 *
 * <p>The seam exists for the same reason {@link LlmProfileResolver} does: the node reads names from
 * graph content and must never read anything else from it, so the thing that maps a name onto an
 * endpoint is injected and, in a test, is not the environment.</p>
 */
@FunctionalInterface
public interface McpProfileResolver {

    /**
     * @param profileName the name a graph wrote in the node's {@code mcpServers} property
     * @return the operator's declaration, or empty when this deployment declared no such server —
     *     including when it declared one this bundle refuses to read
     */
    Optional<McpProfile> resolve(String profileName);
}
