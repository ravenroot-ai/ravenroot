package ai.ravenroot.server.authoring;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import java.util.Map;
import java.util.Set;

/**
 * Optional operator-owned binding from graph promotion to an existing automation deployment.
 * @param label the only automation label applied to a validated isolated review
 * @param deploymentId existing tenant-scoped deployment alias
 * @param registrationIdentity stable durable registration UUID
 * @param graphVersion exact deployed graph SHA-256 digest
 * @param sourceNodeId enabled named SOURCE to receive a release request
 */
public record ReleaseAutomationPolicy(String label, String deploymentId, String registrationIdentity,
        String graphVersion, String sourceNodeId) {
    /** Returns the default policy without automation authority. @return disabled policy */
    public static ReleaseAutomationPolicy disabled() { return new ReleaseAutomationPolicy("", "", "", "", ""); }
    /** Validates either a completely empty disabled policy or a complete pinned binding. */
    public ReleaseAutomationPolicy {
        if (label == null || deploymentId == null || registrationIdentity == null || graphVersion == null || sourceNodeId == null)
            throw new IllegalArgumentException("Invalid graph release automation binding");
        boolean disabled = label.isEmpty() && deploymentId.isEmpty() && registrationIdentity.isEmpty()
                && graphVersion.isEmpty() && sourceNodeId.isEmpty();
        if (!disabled && (!label.matches("[A-Za-z0-9:_-]{1,100}")
                || !deploymentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !registrationIdentity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !graphVersion.matches("[0-9a-f]{64}")
                || !sourceNodeId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
            throw new IllegalArgumentException("Invalid graph release automation binding");
    }
    /** Whether a complete binding is configured. @return whether automation is enabled */
    public boolean enabled() { return !label.isEmpty(); }
    /**
     * Parses the optional operator-owned JSON binding, rejecting partial or unknown fields.
     * @param environment server environment snapshot
     * @return validated binding, or the disabled default
     */
    public static ReleaseAutomationPolicy fromEnvironment(Map<String,String> environment) {
        String raw = environment.getOrDefault("RAVENROOT_GRAPH_RELEASE_AUTOMATION", "");
        if (raw == null) throw new IllegalArgumentException("Invalid graph release automation configuration");
        if (raw.isBlank()) return disabled();
        Object value = PayloadJson.read(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new PayloadLimits(4096, 4, 20, 20, 1024, 64)).toJava();
        if (!(value instanceof Map<?,?> map) || !map.keySet().equals(Set.of("label", "deploymentId",
                "registrationIdentity", "graphVersion", "sourceNodeId"))
                || map.values().stream().anyMatch(x -> !(x instanceof String)))
            throw new IllegalArgumentException("Invalid graph release automation configuration");
        return new ReleaseAutomationPolicy((String)map.get("label"), (String)map.get("deploymentId"),
                (String)map.get("registrationIdentity"), (String)map.get("graphVersion"), (String)map.get("sourceNodeId"));
    }
    /** Returns browser-safe pinned capability metadata. @return immutable capability projection */
    public Map<String,Object> capabilities() {
        return Map.of("mode", "isolated-automerge", "deploymentId", deploymentId,
                "registrationIdentity", registrationIdentity, "graphVersion", graphVersion, "sourceNodeId", sourceNodeId);
    }
}
