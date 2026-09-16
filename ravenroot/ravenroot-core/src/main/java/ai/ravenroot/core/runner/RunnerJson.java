package ai.ravenroot.core.runner;

import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.runner.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Bounded protocol-v1 JSON projection, independent of server/framework serializers. */
public final class RunnerJson {
    public static final PayloadLimits LIMITS = new PayloadLimits(1_048_576, 24, 1024, 20_000, 131_072, 256);
    private RunnerJson() { }
    public static byte[] write(Object value) {
        return PayloadJson.write(PayloadValue.fromJava(value, LIMITS)).getBytes(StandardCharsets.UTF_8);
    }
    public static Map<String, Object> read(byte[] bytes) { return map(PayloadJson.read(bytes, LIMITS).toJava()); }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("runner object required");
        return (Map<String, Object>) value;
    }
    public static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String result)) throw new IllegalArgumentException("missing runner field " + key);
        return result;
    }
    public static long number(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("missing runner number");
        return new java.math.BigDecimal(number.toString()).longValueExact();
    }
    private static Set<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("runner list required");
        return list.stream().map(value -> { if (!(value instanceof String)) throw new IllegalArgumentException("runner string required"); return (String) value; })
                .collect(Collectors.toUnmodifiableSet());
    }
    private static boolean bool(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Boolean result)) throw new IllegalArgumentException("runner boolean required");
        return result;
    }
    public static Map<String, Object> policy(RunnerPolicy policy) {
        var limits = policy.limits();
        return Map.of("capabilities", policy.capabilities().stream().map(Enum::name).sorted().toList(),
                "tools", policy.tools(), "network", policy.egress(), "secrets", policy.secrets(), "mounts", policy.mounts(),
                "limits", Map.of("wallTime", limits.wallTime().toString(), "memoryBytes", limits.memoryBytes(),
                "processes", limits.processes(), "workspaceBytes", limits.workspaceBytes(), "artifactBytes", limits.artifactBytes(),
                "logBytes", limits.logBytes(), "payloadBytes", limits.payloadBytes()));
    }
    public static RunnerPolicy policy(Map<String, Object> value) {
        var limits = map(value.get("limits"));
        return new RunnerPolicy(strings(value.get("capabilities")).stream().map(RunnerPolicy.Capability::valueOf)
                .collect(Collectors.toUnmodifiableSet()), strings(value.get("tools")), strings(value.get("network")),
                strings(value.get("secrets")), strings(value.get("mounts")), new RunnerPolicy.Limits(
                Duration.parse(text(limits, "wallTime")), number(limits, "memoryBytes"), Math.toIntExact(number(limits, "processes")),
                number(limits, "workspaceBytes"), number(limits, "artifactBytes"), number(limits, "logBytes"),
                Math.toIntExact(number(limits, "payloadBytes"))));
    }
    public static Map<String, Object> definition(AgentDefinition definition) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", definition.reference().name()); result.put("version", definition.reference().version());
        result.put("instructions", definition.instructions()); result.put("runtimeProfile", definition.runtimeProfile());
        result.put("modelProfile", definition.modelProfile()); result.put("skills", definition.skills());
        result.put("runnerRequirements", definition.runnerRequirements()); result.put("policy", policy(definition.policy()));
        result.put("workspaceRetention", definition.workspaceRetention().toString()); result.put("outputSchema", definition.outputSchema());
        result.put("commands", definition.commands().values().stream().sorted(Comparator.comparing(AgentCommand::name))
                .map(command -> Map.of("name", command.name(), "readOnly", command.readOnly(),
                        "policy", policy(command.policy()), "outcomes", command.outcomes())).toList());
        return result;
    }
    public static AgentDefinition definition(String tenant, Map<String, Object> value) {
        if (!(value.get("commands") instanceof List<?> commands)) throw new IllegalArgumentException("runner commands required");
        var mapped = new LinkedHashMap<String, AgentCommand>();
        for (Object raw : commands) {
            var command = map(raw); String name = text(command, "name");
            if (mapped.put(name, new AgentCommand(name, bool(command, "readOnly"), policy(map(command.get("policy"))),
                    strings(command.get("outcomes")))) != null) throw new IllegalArgumentException("duplicate runner command");
        }
        return new AgentDefinition(new AgentDefinition.Reference(tenant, text(value, "name"), number(value, "version")),
                text(value, "instructions"), text(value, "runtimeProfile"), text(value, "modelProfile"), mapped,
                strings(value.get("skills")), strings(value.get("runnerRequirements")), policy(map(value.get("policy"))),
                Duration.parse(text(value, "workspaceRetention")), text(value, "outputSchema"));
    }
    public static Map<String, Object> registration(RunnerRegistration registration) {
        return Map.of("protocolVersion", registration.protocolVersion(), "runnerId", registration.runnerId(),
                "trustProfile", registration.trustProfile(), "labels", registration.labels(), "capabilities", policy(registration.capabilities()));
    }
    public static RunnerRegistration registration(String tenant, Map<String, Object> value) {
        return new RunnerRegistration(Math.toIntExact(number(value, "protocolVersion")), tenant, text(value, "runnerId"),
                text(value, "trustProfile"), strings(value.get("labels")), policy(map(value.get("capabilities"))));
    }
    public static Map<String, Object> resource(GovernedRunnerResource resource) {
        Object body = resource.kind() == GovernedRunnerResource.Kind.AGENT_DEFINITION
                ? definition(RunnerCodec.definition(resource.document().bytes())) : registration(RunnerCodec.registration(resource.document().bytes()));
        return Map.of("kind", resource.kind().name(), "name", resource.name(), "version", resource.version(),
                "approved", resource.approved(), "revision", resource.revision(), "actor", resource.actor(),
                "updatedAt", resource.updatedAt().toString(), "document", body);
    }
    public static GovernedRunnerResource resource(String tenant, Map<String, Object> value) {
        var kind = GovernedRunnerResource.Kind.valueOf(text(value, "kind"));
        byte[] document = kind == GovernedRunnerResource.Kind.AGENT_DEFINITION
                ? RunnerCodec.definition(definition(tenant, map(value.get("document"))))
                : RunnerCodec.registration(registration(tenant, map(value.get("document"))));
        return new GovernedRunnerResource(kind, tenant, text(value, "name"), number(value, "version"),
                bool(value, "approved"), OpaquePayload.of(document, kind == GovernedRunnerResource.Kind.AGENT_DEFINITION
                        ? "application/vnd.ravenroot.agent-definition.v1" : "application/vnd.ravenroot.runner-registration.v1"),
                0, "untrusted-ingress", Instant.EPOCH);
    }
    public static Map<String, Object> entry(RunnerWorkspaceState.Entry entry) {
        var value = new LinkedHashMap<>(job(entry.job()));
        value.put("continuationUncertain", entry.continuationUncertain()); return value;
    }
    public static Map<String, Object> job(RunnerJob job) {
        var id = job.identity(); var result = new LinkedHashMap<String, Object>();
        result.put("processInstanceId", id.execution().processInstanceId().toString()); result.put("traversalId", id.traversalId().toString());
        result.put("invocationId", id.invocationId().toString()); result.put("attemptId", id.attemptId().toString());
        result.put("runnerJobId", id.runnerJobId().toString()); result.put("runnerId", job.runner().runnerId());
        result.put("state", job.state().name()); result.put("fence", job.fence()); result.put("revision", job.revision());
        result.put("command", job.command().name()); result.put("readOnly", job.command().readOnly());
        result.put("definition", job.definition().reference().name()); result.put("definitionVersion", job.definition().reference().version());
        result.put("deadline", job.deadline().toString()); result.put("leaseUntil", job.leaseUntil() == null ? null : job.leaseUntil().toString());
        result.put("authority", policy(job.authority())); result.put("stopReason", job.stopReason().name());
        result.put("outcome", job.result() == null ? null : job.result().outcome());
        result.put("artifacts", job.result() == null ? List.of() : job.result().artifacts().stream().map(RunnerJson::artifact).toList());
        return result;
    }
    public static Map<String, Object> artifact(RunnerArtifact artifact) {
        return Map.of("artifactId", artifact.artifactId().toString(), "kind", artifact.kind().name(),
                "sha256", artifact.sha256(), "sizeBytes", artifact.sizeBytes());
    }
}
