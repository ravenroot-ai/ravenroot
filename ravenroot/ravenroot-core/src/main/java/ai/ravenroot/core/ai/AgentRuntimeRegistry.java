package ai.ravenroot.core.ai;

import ai.ravenroot.api.ai.AgentRuntime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit agent-runtime composition, held by {@link ai.ravenroot.core.runtime.BehaviorEnvironment}.
 *
 * <p><b>No behaviour shipped by the core reads this registry (ADR 0029).</b> The {@code agent}
 * node that used to resolve against it left the core with {@code llm-prompt}; what remains is the
 * embedding seam, for an application that composes its own {@code BehaviorEnvironment} and supplies
 * the behaviour factory as well as the runtime. See {@link ai.ravenroot.api.ai.AgentRuntime}.</p>
 *
 * <p>{@code isEmpty()} was removed with that node (the registry-removal contract). It existed for one caller
 * and one purpose — letting {@code AgentNodeBehaviorFactory} tell "nothing is implemented anywhere"
 * apart from "not configured here" — and a method whose declared purpose no caller performs is
 * indistinguishable from dead code.</p>
 */
public final class AgentRuntimeRegistry {
    private final Map<String, AgentRuntime> runtimes = new ConcurrentHashMap<>();

    public AgentRuntimeRegistry register(AgentRuntime runtime) {
        if (runtime == null || runtime.id() == null || runtime.id().isBlank()) {
            throw new IllegalArgumentException("Agent runtime and id are required");
        }
        runtimes.put(runtime.id(), runtime);
        return this;
    }

    public Optional<AgentRuntime> find(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }
}
