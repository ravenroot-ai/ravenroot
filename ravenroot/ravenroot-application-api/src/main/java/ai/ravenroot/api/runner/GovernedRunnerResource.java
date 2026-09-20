package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.OpaquePayload;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable catalog body with revisioned operator approval. Changing a definition requires a new version.
 * @param kind catalog document type
 * @param tenantId authenticated owning tenant
 * @param name bounded symbolic resource name
 * @param version immutable definition version; runner registrations use version one
 * @param approved operator approval state, independent of self-registration
 * @param document bounded versioned binary document
 * @param revision compare-and-set revision, zero before first acceptance
 * @param actor authenticated operator responsible for this approval revision
 * @param updatedAt authoritative acceptance time
 */
public record GovernedRunnerResource(Kind kind, String tenantId, String name, long version,
                                     boolean approved, OpaquePayload document, long revision,
                                     String actor, Instant updatedAt) {
    /** Supported independently approved catalog documents. */
    public enum Kind {
        /** Immutable versioned specialist definition. */
        AGENT_DEFINITION,
        /** Exact designated runner advertisement. */
        RUNNER,
        /** Immutable placement, lifecycle, authorization and capacity policy. */
        WORKSPACE_PROFILE
    }
    /** Maximum catalog entries retained per tenant. */
    public static final int MAX_RESOURCES_PER_TENANT = 256;
    /** Validates the bounded body and its exact catalog identity. */
    public GovernedRunnerResource {
        Objects.requireNonNull(kind); Objects.requireNonNull(document); Objects.requireNonNull(updatedAt);
        name = RunnerPolicy.identifier(name);
        if (revision < 0 || version < 1 || actor == null || actor.isBlank() || actor.length() > 1024
                || tenantId == null || tenantId.isBlank() || tenantId.length() > 256 || document.size() > RunnerCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("invalid governed runner resource");
        }
        if (kind == Kind.AGENT_DEFINITION) {
            var definition = RunnerCodec.definition(document.bytes());
            if (!definition.reference().equals(new AgentDefinition.Reference(tenantId, name, version))) {
                throw new IllegalArgumentException("agent catalog key does not match its body");
            }
        } else if (kind == Kind.WORKSPACE_PROFILE) {
            var profile = RunnerCodec.workspaceProfile(document.bytes());
            if (!profile.reference().equals(new AgentDefinition.Reference(tenantId, name, version))) {
                throw new IllegalArgumentException("workspace profile key does not match its body");
            }
        } else {
            var runner = RunnerCodec.registration(document.bytes());
            if (version != 1 || !tenantId.equals(runner.tenantId()) || !name.equals(runner.runnerId())) {
                throw new IllegalArgumentException("runner catalog key does not match its body");
            }
        }
    }
    /**
     * Identifies the immutable document within its tenant.
     * @return kind, symbolic name and version key
     */
    public String key() { return kind.name() + ":" + name + ":" + version; }
    /**
     * Applies an approval change without permitting a body or tenant replacement.
     * @param existing current catalog document, or null before creation
     * @param expectedRevision exact current revision, or zero before creation
     * @param now authoritative store acceptance time
     * @return accepted document with its incremented revision
     */
    public GovernedRunnerResource accepted(GovernedRunnerResource existing, long expectedRevision, Instant now) {
        long currentRevision = existing == null ? 0 : existing.revision();
        if (expectedRevision != currentRevision) throw new IllegalStateException("runner catalog revision conflict");
        if (existing != null && (!key().equals(existing.key()) || !tenantId.equals(existing.tenantId())
                || !document.equals(existing.document()))) {
            throw new IllegalArgumentException("governed runner bodies are immutable; publish a new definition version");
        }
        return new GovernedRunnerResource(kind, tenantId, name, version, approved, document,
                Math.incrementExact(currentRevision), actor, now);
    }
}
