package ai.ravenroot.core.runtime;

import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.OutboundHttpPolicy;

/** Constructor-injected capabilities available to standard behavior factories. */
public record BehaviorEnvironment(
        ModelProviderRegistry modelProviders,
        AgentRuntimeRegistry agentRuntimes,
        ArtifactRegistry artifacts,
        ProgramRuntime programRuntime,
        CredentialResolver credentials,
        ToolPolicy toolPolicy,
        OutboundHttpPolicy outboundHttpPolicy) {

    public BehaviorEnvironment {
        modelProviders = modelProviders == null ? new ModelProviderRegistry() : modelProviders;
        agentRuntimes = agentRuntimes == null ? new AgentRuntimeRegistry() : agentRuntimes;
        artifacts = artifacts == null ? new InMemoryArtifactRegistry() : artifacts;
        programRuntime = programRuntime == null ? new DisabledProgramRuntime() : programRuntime;
        credentials = credentials == null ? ignored -> java.util.Optional.empty() : credentials;
        toolPolicy = toolPolicy == null ? ToolPolicy.denyAll() : toolPolicy;
        outboundHttpPolicy = outboundHttpPolicy == null ? OutboundHttpPolicy.disabled() : outboundHttpPolicy;
    }

    public static BehaviorEnvironment safeDefaults() {
        return new BehaviorEnvironment(null, null, null, null, null, null, null);
    }
}
