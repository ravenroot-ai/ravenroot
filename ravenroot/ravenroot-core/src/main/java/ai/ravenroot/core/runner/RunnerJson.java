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
    public static Set<String> strings(Object raw) {
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
        result.put("budgets", Map.of("modelTurns", definition.budgets().modelTurns(), "toolCalls", definition.budgets().toolCalls(),
                "modelTokens", definition.budgets().modelTokens(), "tokensPerTurn", definition.budgets().tokensPerTurn()));
        result.put("skillInstructions", definition.skillInstructions());
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
        var budgets = value.containsKey("budgets") ? map(value.get("budgets")) : null;
        var bodies = new LinkedHashMap<String, String>();
        if (value.containsKey("skillInstructions")) map(value.get("skillInstructions")).forEach((name, body) -> {
            if (!(body instanceof String text)) throw new IllegalArgumentException("skill body must be text");
            bodies.put(name, text);
        });
        return new AgentDefinition(new AgentDefinition.Reference(tenant, text(value, "name"), number(value, "version")),
                text(value, "instructions"), text(value, "runtimeProfile"), text(value, "modelProfile"), mapped,
                strings(value.get("skills")), strings(value.get("runnerRequirements")), policy(map(value.get("policy"))),
                Duration.parse(text(value, "workspaceRetention")), text(value, "outputSchema"),
                budgets == null ? AgentDefinition.Budgets.LEGACY : new AgentDefinition.Budgets(
                    Math.toIntExact(number(budgets, "modelTurns")), Math.toIntExact(number(budgets, "toolCalls")),
                    number(budgets, "modelTokens"), Math.toIntExact(number(budgets, "tokensPerTurn"))), bodies);
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
        Object body = switch (resource.kind()) {
            case AGENT_DEFINITION -> definition(RunnerCodec.definition(resource.document().bytes()));
            case RUNNER -> registration(RunnerCodec.registration(resource.document().bytes()));
            case WORKSPACE_PROFILE -> workspaceProfile(RunnerCodec.workspaceProfile(resource.document().bytes()));
        };
        return Map.of("kind", resource.kind().name(), "name", resource.name(), "version", resource.version(),
                "approved", resource.approved(), "revision", resource.revision(), "actor", resource.actor(),
                "updatedAt", resource.updatedAt().toString(), "document", body);
    }
    public static GovernedRunnerResource resource(String tenant, Map<String, Object> value) {
        var kind = GovernedRunnerResource.Kind.valueOf(text(value, "kind"));
        byte[] document = switch (kind) {
            case AGENT_DEFINITION -> RunnerCodec.definition(definition(tenant, map(value.get("document"))));
            case RUNNER -> RunnerCodec.registration(registration(tenant, map(value.get("document"))));
            case WORKSPACE_PROFILE -> RunnerCodec.workspaceProfile(workspaceProfile(tenant, map(value.get("document"))));
        };
        return new GovernedRunnerResource(kind, tenant, text(value, "name"), number(value, "version"),
                bool(value, "approved"), OpaquePayload.of(document, switch (kind) {
                    case AGENT_DEFINITION -> "application/vnd.ravenroot.agent-definition.v1";
                    case RUNNER -> "application/vnd.ravenroot.runner-registration.v1";
                    case WORKSPACE_PROFILE -> "application/vnd.ravenroot.workspace-profile.v1";
                }),
                0, "untrusted-ingress", Instant.EPOCH);
    }
    public static Map<String, Object> entry(RunnerWorkspaceState.Entry entry) {
        var value = new LinkedHashMap<>(job(entry.job()));
        value.put("continuationUncertain", entry.continuationUncertain());
        value.put("kubernetes", kubernetes(entry.kubernetes()));
        value.put("workspaceRef", entry.workspaceNodeId()); value.put("lifecycleCommand", entry.lifecycleCommand()); return value;
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
        result.put("driver", job.runner().trustProfile().equals("kubernetes-pod-v1") ? "KUBERNETES" : "DOCKER");
        result.put("kubernetes", job.result() == null || job.result().workspace() == null ? null : kubernetes(job.result().workspace().kubernetes()));
        result.put("outcome", job.result() == null ? null : job.result().outcome());
        result.put("result", job.result() == null ? null : directResult(job.result().payload()));
        result.put("artifacts", job.result() == null ? List.of() : job.result().artifacts().stream().map(RunnerJson::artifact).toList());
        return result;
    }
    private static Object directResult(OpaquePayload payload) {
        if (payload.size() == 0) return null;
        if (payload.contentType().equals("application/json") || payload.contentType().endsWith("+json"))
            return PayloadJson.read(payload.bytes(), LIMITS).toJava();
        // Legacy drivers may return opaque bytes. Inspection must remain available for recovery,
        // without silently interpreting arbitrary encodings as structured Agent output.
        return Map.of("contentType", payload.contentType(), "sizeBytes", payload.size(), "opaque", true);
    }
    public static Map<String, Object> workspaceProfile(WorkspaceProfile profile) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", profile.reference().name()); result.put("version", profile.reference().version());
        result.put("workspaceScope", profile.workspaceScope().name()); result.put("runtimeLifecycle", profile.runtimeLifecycle().name());
        result.put("driver", profile.driver().name());
        result.put("runnerPool", profile.runnerPool()); result.put("runtimeProfile", profile.runtimeProfile());
        result.put("policy", policy(profile.policy())); result.put("retention", profile.retention().toString());
        result.put("completionPolicy", profile.completionPolicy().name()); result.put("allowedAgents", profile.allowedAgents());
        var c = profile.capacity();
        result.put("capacity", Map.of("mutatingUsers", c.mutatingUsers(), "readOnlyUsers", c.readOnlyUsers(),
                "materializedWorkspaces", c.materializedWorkspaces(), "aggregateStorageBytes", c.aggregateStorageBytes(),
                "queuedJobs", c.queuedJobs(), "retainedJobs", c.retainedJobs(), "admission", c.admission().name()));
        var fleet = new LinkedHashMap<String, Object>();
        profile.fleetLimits().scopes().forEach((scope, ceiling) -> fleet.put(scope.name(), Map.of(
                "claimedJobs", ceiling.claimedJobs(), "queuedJobs", ceiling.queuedJobs(),
                "retainedWorkspaces", ceiling.retainedWorkspaces(), "storageBytes", ceiling.storageBytes())));
        result.put("fleetLimits", fleet);
        result.put("cpuMillicores", profile.cpuMillicores());
        return result;
    }
    public static WorkspaceProfile workspaceProfile(String tenant, Map<String, Object> value) {
        if (!Set.of("name", "version", "workspaceScope", "runtimeLifecycle", "driver", "runnerPool", "runtimeProfile",
                "policy", "retention", "completionPolicy", "allowedAgents", "capacity", "fleetLimits", "cpuMillicores")
                .containsAll(value.keySet())) throw new IllegalArgumentException("unknown Workspace profile field");
        var c = map(value.get("capacity"));
        var capacity = new WorkspaceProfile.Capacity(Math.toIntExact(number(c, "mutatingUsers")), Math.toIntExact(number(c, "readOnlyUsers")),
                Math.toIntExact(number(c, "materializedWorkspaces")), number(c, "aggregateStorageBytes"),
                Math.toIntExact(number(c, "queuedJobs")), Math.toIntExact(number(c, "retainedJobs")),
                WorkspaceProfile.Admission.valueOf(text(c, "admission")));
        RunnerFleetLimits fleetLimits = RunnerFleetLimits.from(capacity);
        if (value.containsKey("fleetLimits")) {
            var scopes = new java.util.EnumMap<RunnerFleetLimits.Scope, RunnerFleetLimits.Ceiling>(RunnerFleetLimits.Scope.class);
            map(value.get("fleetLimits")).forEach((scope, raw) -> {
                var ceiling = map(raw);
                scopes.put(RunnerFleetLimits.Scope.valueOf(scope), new RunnerFleetLimits.Ceiling(
                        Math.toIntExact(number(ceiling, "claimedJobs")), Math.toIntExact(number(ceiling, "queuedJobs")),
                        Math.toIntExact(number(ceiling, "retainedWorkspaces")), number(ceiling, "storageBytes")));
            });
            fleetLimits = new RunnerFleetLimits(scopes);
        }
        return new WorkspaceProfile(new AgentDefinition.Reference(tenant, text(value, "name"), number(value, "version")),
                WorkspaceProfile.Scope.valueOf(text(value, "workspaceScope")),
                WorkspaceProfile.RuntimeLifecycle.valueOf(text(value, "runtimeLifecycle")),
                text(value, "runnerPool"), text(value, "runtimeProfile"), policy(map(value.get("policy"))),
                capacity,
                Duration.parse(text(value, "retention")), WorkspaceProfile.CompletionPolicy.valueOf(text(value, "completionPolicy")),
                strings(value.get("allowedAgents")), fleetLimits,
                value.containsKey("cpuMillicores") ? Math.toIntExact(number(value, "cpuMillicores")) : 1000,
                value.containsKey("driver") ? WorkspaceProfile.Driver.valueOf(text(value, "driver")) : WorkspaceProfile.Driver.DOCKER);
    }
    public static Map<String, Object> workspace(WorkspaceResource workspace) {
        var value = new LinkedHashMap<String, Object>();
        value.put("nodeId", workspace.nodeId()); value.put("workspaceId", workspace.workspaceId().toString());
        value.put("profile", workspaceProfile(workspace.profile())); value.put("runnerId", workspace.runnerId());
        value.put("state", workspace.state().name()); value.put("runtimeId", workspace.runtimeId() == null ? null : workspace.runtimeId().toString());
        value.put("ownershipGeneration", workspace.generation());
        value.put("checkpoint", workspace.checkpoint()); value.put("stopRequested", workspace.stopRequested());
        value.put("driver", workspace.profile().driver().name());
        value.put("kubernetes", kubernetes(workspace.kubernetes()));
        value.put("updatedAt", workspace.updatedAt().toString()); return value;
    }
    /** Bounded technical projection; no endpoint, credential or user-selected Kubernetes object. */
    public static Map<String, Object> kubernetes(KubernetesWorkload workload) {
        if (workload == null) return null;
        var value = new LinkedHashMap<String, Object>();
        value.put("protocolVersion", workload.protocolVersion()); value.put("cluster", workload.cluster());
        value.put("namespace", workload.namespace()); value.put("podName", workload.podName());
        value.put("podUid", workload.podUid() == null ? null : workload.podUid().toString());
        value.put("claimName", workload.claimName()); value.put("claimUid", workload.claimUid().toString());
        value.put("volumeName", workload.volumeName()); value.put("ownershipGeneration", workload.generation());
        value.put("phase", workload.phase().name()); value.put("requestedBytes", workload.requestedBytes());
        value.put("enforcedBytes", workload.enforcedBytes()); value.put("attestationDigest", workload.attestationDigest());
        value.put("condition", workload.condition().name()); value.put("reason", workload.reason().name()); value.put("exitCode", workload.exitCode());
        value.put("modelTurns", workload.modelTurns()); value.put("toolCalls", workload.toolCalls()); value.put("modelTokens", workload.modelTokens());
        return value;
    }
    /** Strict ingress projection for the authenticated manager's physical heartbeat. */
    public static KubernetesWorkload kubernetes(Map<String, Object> value) {
        if (!value.keySet().equals(Set.of("protocolVersion", "cluster", "namespace", "podName", "podUid", "claimName", "claimUid",
                "volumeName", "ownershipGeneration", "phase", "requestedBytes", "enforcedBytes", "attestationDigest",
                "condition", "reason", "exitCode", "modelTurns", "toolCalls", "modelTokens")))
            throw new IllegalArgumentException("unknown or missing Kubernetes observation field");
        return new KubernetesWorkload(Math.toIntExact(number(value, "protocolVersion")), text(value, "cluster"), text(value, "namespace"),
                (String) value.get("podName"), value.get("podUid") == null ? null : UUID.fromString(text(value, "podUid")), text(value, "claimName"),
                UUID.fromString(text(value, "claimUid")), (String) value.get("volumeName"), number(value, "ownershipGeneration"),
                KubernetesWorkload.Phase.valueOf(text(value, "phase")), number(value, "requestedBytes"), number(value, "enforcedBytes"), (String) value.get("attestationDigest"),
                KubernetesWorkload.Condition.valueOf(text(value, "condition")), KubernetesWorkload.Reason.valueOf(text(value, "reason")),
                value.get("exitCode") == null ? null : Math.toIntExact(number(value, "exitCode")), Math.toIntExact(number(value, "modelTurns")),
                Math.toIntExact(number(value, "toolCalls")), number(value, "modelTokens"));
    }
    public static Map<String, Object> artifact(RunnerArtifact artifact) {
        return Map.of("artifactId", artifact.artifactId().toString(), "kind", artifact.kind().name(),
                "sha256", artifact.sha256(), "sizeBytes", artifact.sizeBytes());
    }
}
