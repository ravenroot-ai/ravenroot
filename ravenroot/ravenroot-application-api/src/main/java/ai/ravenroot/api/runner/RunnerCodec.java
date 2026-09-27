package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Vendor-free version-one storage codec. Explicit bounded fields, no Java serialization, reflection,
 * runtime class names, or runner-controlled deserializers. The enclosing digest detects corruption.
 */
public final class RunnerCodec {
    /** Exact native-workload reader capabilities required before remote delegation. */
    public static final String NATIVE_CAPABILITIES = "workspace=4,assignment=3,result=3,profile=2";
    /** Maximum encoded document size, including its corruption-detection digest. */
    public static final int MAX_BYTES = 16_777_216;
    /**
     * Payload/catalog field bound of the existing runner protocol envelopes. This is an
     * interoperability bound, not a job-capacity default; widening it requires a versioned reader
     * migration. The effective operator policy normally selects a smaller payload budget.
     */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int MAGIC = 0x52524a31;
    private static final int WORKSPACE_V2 = 0x52524a32;
    private static final int WORKSPACE_V3 = 0x52524a33;
    private static final int WORKSPACE_V4 = 0x52524a34;
    private static final int ASSIGNMENT_V2 = 0x52524132;
    private static final int ASSIGNMENT_V3 = 0x52524133;
    private static final int RESULT_V2 = 0x52525232;
    private static final int RESULT_V3 = 0x52525233;
    private static final int PROFILE_V2 = 0x52525032;
    private static final int DEFINITION_V2 = 0x52414432;
    private RunnerCodec() { }
    /**
     * Encodes a worker's incarnation lease with corruption detection.
     * @param value store-clock advertisement
     * @return immutable bounded wire bytes
     */
    public static byte[] availability(RunnerAvailability value) {
        return encode(0x52525631, out -> {
            string(out, value.tenantId()); string(out, value.runnerId()); uuid(out, value.sessionId());
            out.writeInt(value.capacity()); out.writeInt(value.activeJobs()); strings(out, value.runtimeProfiles());
            instant(out, value.observedAt()); instant(out, value.leaseUntil());
        });
    }
    /**
     * Decodes and validates a persisted worker incarnation lease.
     * @param bytes complete advertisement envelope
     * @return validated capacity and liveness evidence, not approval
     */
    public static RunnerAvailability availability(byte[] bytes) {
        return decode(bytes, 0x52525631, in -> new RunnerAvailability(string(in), string(in), uuid(in), in.readInt(), in.readInt(),
                strings(in), instant(in), instant(in)));
    }

    /**
     * Encodes trusted workspace storage version three, including explicit graph resources.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] workspace(RunnerWorkspaceState value) {
        return encode(WORKSPACE_V4, out -> {
            key(out, value.execution()); uuid(out, value.workspaceId()); string(out, value.runnerId());
            out.writeInt(value.jobs().size());
            for (var entry : value.jobs().values()) {
                job(out, entry.job()); payload(out, entry.continuation()); out.writeBoolean(entry.continuationUncertain());
                nullableString(out, entry.workspaceNodeId()); nullableString(out, entry.lifecycleCommand());
                kubernetes(out, entry.kubernetes());
            }
            out.writeBoolean(value.processTerminalAt() != null);
            if (value.processTerminalAt() != null) instant(out, value.processTerminalAt());
            out.writeInt(value.workspaces().size());
            for (String node : new TreeSet<>(value.workspaces().keySet())) workspaceResource(out, value.workspaces().get(node));
        });
    }

    /**
     * Decodes a trusted process workspace, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete workspace storage version-one, version-two or version-three document
     * @return validated immutable value
     */
    public static RunnerWorkspaceState workspace(byte[] bytes) {
        boolean versionTwo = bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == WORKSPACE_V2;
        boolean versionFour = bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == WORKSPACE_V4;
        boolean versionThree = versionFour || bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == WORKSPACE_V3;
        return decode(bytes, versionFour ? WORKSPACE_V4 : versionThree ? WORKSPACE_V3 : versionTwo ? WORKSPACE_V2 : MAGIC, in -> {
            ExecutionKey execution = key(in); UUID workspace = uuid(in); String runner = string(in);
            var jobs = new LinkedHashMap<UUID, RunnerWorkspaceState.Entry>();
            int size = count(in, versionThree ? MAX_BYTES : RunnerWorkspaceState.MAX_JOBS);
            for (int i = 0; i < size; i++) {
                RunnerJob job = job(in, versionThree, versionFour);
                var continuation = payload(in); boolean uncertain = in.readBoolean();
                String node = versionThree ? nullableString(in) : null;
                String command = versionThree ? nullableString(in) : null;
                var physical = versionFour ? kubernetes(in) : null;
                if (jobs.put(job.identity().runnerJobId(), new RunnerWorkspaceState.Entry(job, continuation, uncertain, node, command, physical)) != null) {
                    throw new IllegalArgumentException("duplicate stored runner job");
                }
            }
            Instant terminalAt = (versionTwo || versionThree) && in.readBoolean() ? instant(in) : null;
            var resources = new LinkedHashMap<String, WorkspaceResource>();
            int resourceCount = versionThree ? count(in, MAX_BYTES) : 0;
            for (int i = 0; i < resourceCount; i++) {
                var resource = workspaceResource(in, versionFour);
                if (resources.put(resource.nodeId(), resource) != null) throw new IllegalArgumentException("duplicate workspace node");
            }
            return new RunnerWorkspaceState(execution, workspace, runner, jobs, terminalAt, resources);
        });
    }

    /**
     * Encodes a bounded immutable agent definition using storage version two, including budgets.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] definition(AgentDefinition value) { return encode(DEFINITION_V2, out -> definition(out, value)); }
    /**
     * Decodes a immutable agent definition, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one or version-two definition document
     * @return validated immutable value
     */
    public static AgentDefinition definition(byte[] bytes) {
        boolean extended = bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == DEFINITION_V2;
        return decode(bytes, extended ? DEFINITION_V2 : MAGIC, in -> definition(in, extended));
    }
    /**
     * Encodes a bounded runner capability advertisement using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] registration(RunnerRegistration value) { return encode(out -> registration(out, value)); }
    /**
     * Decodes a runner capability advertisement, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerRegistration registration(byte[] bytes) { return decode(bytes, RunnerCodec::registration); }
    /**
     * Encodes a bounded sealed terminal report using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] result(RunnerResult value) { return encode(RESULT_V3, out -> result(out, value)); }
    /**
     * Decodes a sealed terminal report, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerResult result(byte[] bytes) {
        boolean nativeIdentity = bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == RESULT_V3;
        boolean extended = nativeIdentity || bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == RESULT_V2;
        return decode(bytes, nativeIdentity ? RESULT_V3 : extended ? RESULT_V2 : MAGIC, in -> result(in, extended, nativeIdentity));
    }
    /**
     * Encodes a bounded runner dispatch view without graph checkpoints using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] assignment(RunnerAssignment value) {
        return encode(ASSIGNMENT_V3, out -> {
            out.writeInt(value.protocolVersion()); uuid(out, value.workspaceId()); job(out, value.job());
            out.writeBoolean(value.workspace() != null);
            if (value.workspace() != null) workspaceResource(out, value.workspace());
            nullableString(out, value.lifecycleCommand());
        });
    }
    /**
     * Decodes a runner dispatch view without graph checkpoints, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerAssignment assignment(byte[] bytes) {
        boolean nativeIdentity = bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == ASSIGNMENT_V3;
        boolean extended = nativeIdentity || bytes != null && bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == ASSIGNMENT_V2;
        return decode(bytes, nativeIdentity ? ASSIGNMENT_V3 : extended ? ASSIGNMENT_V2 : MAGIC, in -> new RunnerAssignment(in.readInt(), uuid(in), job(in, extended, nativeIdentity),
                extended && in.readBoolean() ? workspaceResource(in, nativeIdentity) : null, extended ? nullableString(in) : null));
    }

    /**
     * Encodes an immutable approved resource profile and both independent lifecycle axes.
     * @param value validated profile, including all scoped capacity ceilings
     * @return integrity-protected storage document
     */
    public static byte[] workspaceProfile(WorkspaceProfile value) { return encode(PROFILE_V2, out -> workspaceProfile(out, value)); }
    /**
     * Restores the exact approved profile without consulting mutable deployment defaults.
     * @param value complete integrity-protected profile bytes
     * @return validated immutable profile
     */
    public static WorkspaceProfile workspaceProfile(byte[] value) {
        boolean extended = value != null && value.length >= 4 && java.nio.ByteBuffer.wrap(value).getInt() == PROFILE_V2;
        return decode(value, extended ? PROFILE_V2 : MAGIC, in -> workspaceProfile(in, extended));
    }
    private static void workspaceProfile(DataOutputStream out, WorkspaceProfile value) throws IOException {
        string(out, value.reference().tenantId()); string(out, value.reference().name()); out.writeLong(value.reference().version());
        string(out, value.workspaceScope().name()); string(out, value.runtimeLifecycle().name());
        string(out, value.runnerPool()); string(out, value.runtimeProfile()); policy(out, value.policy());
        var capacity = value.capacity();
        out.writeInt(capacity.mutatingUsers()); out.writeInt(capacity.readOnlyUsers()); out.writeInt(capacity.materializedWorkspaces());
        out.writeLong(capacity.aggregateStorageBytes()); out.writeInt(capacity.queuedJobs()); out.writeInt(capacity.retainedJobs());
        string(out, capacity.admission().name()); string(out, value.retention().toString()); string(out, value.completionPolicy().name());
        strings(out, value.allowedAgents());
        for (var scope : RunnerFleetLimits.Scope.values()) {
            var ceiling = value.fleetLimits().scopes().get(scope);
            out.writeInt(ceiling.claimedJobs()); out.writeInt(ceiling.queuedJobs());
            out.writeInt(ceiling.retainedWorkspaces()); out.writeLong(ceiling.storageBytes());
        }
        out.writeInt(value.cpuMillicores());
        string(out, value.driver().name());
    }
    private static WorkspaceProfile workspaceProfile(DataInputStream in, boolean extended) throws IOException {
        return new WorkspaceProfile(new AgentDefinition.Reference(string(in), string(in), in.readLong()),
                WorkspaceProfile.Scope.valueOf(string(in)), WorkspaceProfile.RuntimeLifecycle.valueOf(string(in)),
                string(in), string(in), policy(in), new WorkspaceProfile.Capacity(in.readInt(), in.readInt(), in.readInt(),
                in.readLong(), in.readInt(), in.readInt(), WorkspaceProfile.Admission.valueOf(string(in))),
                Duration.parse(string(in)), WorkspaceProfile.CompletionPolicy.valueOf(string(in)), strings(in), fleetLimits(in), in.readInt(),
                extended ? WorkspaceProfile.Driver.valueOf(string(in)) : WorkspaceProfile.Driver.DOCKER);
    }
    private static RunnerFleetLimits fleetLimits(DataInputStream in) throws IOException {
        var scopes = new java.util.EnumMap<RunnerFleetLimits.Scope, RunnerFleetLimits.Ceiling>(RunnerFleetLimits.Scope.class);
        for (var scope : RunnerFleetLimits.Scope.values()) scopes.put(scope,
                new RunnerFleetLimits.Ceiling(in.readInt(), in.readInt(), in.readInt(), in.readLong()));
        return new RunnerFleetLimits(scopes);
    }
    private static void workspaceResource(DataOutputStream out, WorkspaceResource value) throws IOException {
        string(out, value.nodeId()); uuid(out, value.workspaceId()); workspaceProfile(out, value.profile());
        string(out, value.runnerId()); string(out, value.state().name());
        nullableString(out, value.runtimeId());
        nullableString(out, value.checkpoint()); out.writeBoolean(value.stopRequested()); instant(out, value.updatedAt()); out.writeLong(value.generation());
        kubernetes(out, value.kubernetes());
    }
    private static WorkspaceResource workspaceResource(DataInputStream in, boolean nativeIdentity) throws IOException {
        return new WorkspaceResource(string(in), uuid(in), workspaceProfile(in, nativeIdentity), string(in), WorkspaceResource.State.valueOf(string(in)),
                nullableString(in), nullableString(in), in.readBoolean(), instant(in), in.readLong(), nativeIdentity ? kubernetes(in) : null);
    }
    private static void nullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null); if (value != null) string(out, value);
    }
    private static String nullableString(DataInputStream in) throws IOException { return in.readBoolean() ? string(in) : null; }

    /**
     * Encodes a bounded revisioned governed catalog document using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] resource(GovernedRunnerResource value) {
        return encode(out -> {
            string(out, value.kind().name()); string(out, value.tenantId()); string(out, value.name());
            out.writeLong(value.version()); out.writeBoolean(value.approved()); payload(out, value.document());
            out.writeLong(value.revision()); string(out, value.actor()); instant(out, value.updatedAt());
        });
    }
    /**
     * Decodes a revisioned governed catalog document, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static GovernedRunnerResource resource(byte[] bytes) {
        return decode(bytes, in -> new GovernedRunnerResource(GovernedRunnerResource.Kind.valueOf(string(in)),
                string(in), string(in), in.readLong(), in.readBoolean(), payload(in), in.readLong(),
                string(in), instant(in)));
    }

    private static void job(DataOutputStream out, RunnerJob value) throws IOException {
        identity(out, value.identity()); definition(out, value.definition()); string(out, value.command().name());
        registration(out, value.runner()); policy(out, value.authority()); payload(out, value.input());
        instant(out, value.deadline()); instant(out, value.updatedAt()); out.writeLong(value.revision());
        out.writeLong(value.fence()); string(out, value.state().name()); string(out, value.stopReason().name());
        out.writeBoolean(value.leaseUntil() != null);
        if (value.leaseUntil() != null) instant(out, value.leaseUntil());
        out.writeBoolean(value.result() != null);
        if (value.result() != null) result(out, value.result());
    }

    private static RunnerJob job(DataInputStream in, boolean extended, boolean nativeIdentity) throws IOException {
        RunnerJobIdentity identity = identity(in); AgentDefinition definition = definition(in, extended); String command = string(in);
        RunnerRegistration runner = registration(in); RunnerPolicy authority = policy(in); OpaquePayload input = payload(in);
        Instant deadline = instant(in); Instant updated = instant(in); long revision = in.readLong(); long fence = in.readLong();
        RunnerJob.State state = RunnerJob.State.valueOf(string(in));
        RunnerJob.StopReason reason = RunnerJob.StopReason.valueOf(string(in));
        Instant lease = in.readBoolean() ? instant(in) : null;
        RunnerResult result = in.readBoolean() ? result(in, extended, nativeIdentity) : null;
        return RunnerJob.restore(identity, definition, command, runner, authority, input, deadline, updated,
                revision, fence, state, reason, lease, result);
    }

    private static void definition(DataOutputStream out, AgentDefinition value) throws IOException {
        string(out, value.reference().tenantId()); string(out, value.reference().name()); out.writeLong(value.reference().version());
        string(out, value.instructions()); string(out, value.runtimeProfile()); string(out, value.modelProfile());
        out.writeInt(value.commands().size());
        for (String name : new TreeSet<>(value.commands().keySet())) {
            AgentCommand command = value.commands().get(name);
            string(out, name); out.writeBoolean(command.readOnly()); policy(out, command.policy()); strings(out, command.outcomes());
        }
        strings(out, value.skills()); strings(out, value.runnerRequirements()); policy(out, value.policy());
        string(out, value.workspaceRetention().toString()); string(out, value.outputSchema());
        out.writeInt(value.budgets().modelTurns()); out.writeInt(value.budgets().toolCalls());
        out.writeLong(value.budgets().modelTokens()); out.writeInt(value.budgets().tokensPerTurn());
        out.writeInt(value.skillInstructions().size());
        for (String name : new TreeSet<>(value.skillInstructions().keySet())) {
            string(out, name); string(out, value.skillInstructions().get(name));
        }
    }

    private static AgentDefinition definition(DataInputStream in, boolean extended) throws IOException {
        var reference = new AgentDefinition.Reference(string(in), string(in), in.readLong());
        String instructions = string(in), runtime = string(in), model = string(in);
        var commands = new LinkedHashMap<String, AgentCommand>();
        int count = count(in, 64);
        for (int i = 0; i < count; i++) {
            String name = string(in);
            if (commands.put(name, new AgentCommand(name, in.readBoolean(), policy(in), strings(in))) != null) {
                throw new IllegalArgumentException("duplicate agent command");
            }
        }
        var skills = strings(in); var requirements = strings(in); var policy = policy(in);
        var retention = Duration.parse(string(in)); var schema = string(in);
        var budgets = extended ? new AgentDefinition.Budgets(in.readInt(), in.readInt(), in.readLong(), in.readInt())
                : AgentDefinition.Budgets.LEGACY;
        var bodies = new LinkedHashMap<String, String>();
        int bodyCount = extended ? count(in, 128) : 0;
        for (int i = 0; i < bodyCount; i++) {
            if (bodies.put(string(in), string(in)) != null) throw new IllegalArgumentException("duplicate skill body");
        }
        return new AgentDefinition(reference, instructions, runtime, model, commands, skills, requirements,
                policy, retention, schema, budgets, bodies);
    }

    private static void policy(DataOutputStream out, RunnerPolicy value) throws IOException {
        strings(out, value.capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        strings(out, value.tools()); strings(out, value.egress()); strings(out, value.secrets()); strings(out, value.mounts());
        var limits = value.limits(); string(out, limits.wallTime().toString()); out.writeLong(limits.memoryBytes());
        out.writeInt(limits.processes()); out.writeLong(limits.workspaceBytes()); out.writeLong(limits.artifactBytes());
        out.writeLong(limits.logBytes()); out.writeInt(limits.payloadBytes());
    }

    private static RunnerPolicy policy(DataInputStream in) throws IOException {
        Set<RunnerPolicy.Capability> capabilities = strings(in).stream().map(RunnerPolicy.Capability::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> tools = strings(in), egress = strings(in), secrets = strings(in), mounts = strings(in);
        return new RunnerPolicy(capabilities, tools, egress, secrets, mounts,
                new RunnerPolicy.Limits(Duration.parse(string(in)), in.readLong(), in.readInt(), in.readLong(),
                        in.readLong(), in.readLong(), in.readInt()));
    }

    private static void registration(DataOutputStream out, RunnerRegistration value) throws IOException {
        out.writeInt(value.protocolVersion()); string(out, value.tenantId()); string(out, value.runnerId());
        string(out, value.trustProfile()); strings(out, value.labels()); policy(out, value.capabilities());
    }

    private static RunnerRegistration registration(DataInputStream in) throws IOException {
        return new RunnerRegistration(in.readInt(), string(in), string(in), string(in), strings(in), policy(in));
    }

    private static void result(DataOutputStream out, RunnerResult value) throws IOException {
        string(out, value.outcome()); payload(out, value.payload()); uuid(out, value.quiescenceId());
        out.writeInt(value.artifacts().size());
        for (RunnerArtifact artifact : value.artifacts()) {
            identity(out, artifact.job()); uuid(out, artifact.artifactId()); string(out, artifact.kind().name());
            string(out, artifact.sha256()); out.writeLong(artifact.sizeBytes());
        }
        out.writeBoolean(value.workspace() != null);
        if (value.workspace() != null) {
            uuid(out, value.workspace().workspaceId()); nullableString(out, value.workspace().runtimeId());
            nullableString(out, value.workspace().checkpoint());
            kubernetes(out, value.workspace().kubernetes());
        }
    }

    private static RunnerResult result(DataInputStream in, boolean extended, boolean nativeIdentity) throws IOException {
        String outcome = string(in); OpaquePayload payload = payload(in); UUID quiescence = uuid(in);
        var artifacts = new ArrayList<RunnerArtifact>(); int count = count(in, 128);
        for (int i = 0; i < count; i++) artifacts.add(new RunnerArtifact(identity(in), uuid(in),
                RunnerArtifact.Kind.valueOf(string(in)), string(in), in.readLong()));
        var workspace = extended && in.readBoolean()
                ? new RunnerResult.WorkspaceObservation(uuid(in), nullableString(in), nullableString(in), nativeIdentity ? kubernetes(in) : null) : null;
        return new RunnerResult(outcome, payload, artifacts, quiescence, workspace);
    }

    private static void kubernetes(DataOutputStream out, KubernetesWorkload value) throws IOException {
        out.writeBoolean(value != null);
        if (value == null) return;
        out.writeInt(value.protocolVersion()); string(out, value.cluster()); string(out, value.namespace());
        nullableString(out, value.podName()); out.writeBoolean(value.podUid() != null);
        if (value.podUid() != null) uuid(out, value.podUid());
        string(out, value.claimName()); uuid(out, value.claimUid()); nullableString(out, value.volumeName());
        out.writeLong(value.generation()); string(out, value.phase().name()); out.writeLong(value.requestedBytes());
        out.writeLong(value.enforcedBytes()); nullableString(out, value.attestationDigest());
        string(out, value.condition().name()); string(out, value.reason().name());
        out.writeBoolean(value.exitCode() != null); if (value.exitCode() != null) out.writeInt(value.exitCode());
        out.writeInt(value.modelTurns()); out.writeInt(value.toolCalls()); out.writeLong(value.modelTokens());
    }

    private static KubernetesWorkload kubernetes(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        return new KubernetesWorkload(in.readInt(), string(in), string(in), nullableString(in),
                in.readBoolean() ? uuid(in) : null, string(in), uuid(in), nullableString(in), in.readLong(),
                KubernetesWorkload.Phase.valueOf(string(in)), in.readLong(), in.readLong(), nullableString(in),
                KubernetesWorkload.Condition.valueOf(string(in)), KubernetesWorkload.Reason.valueOf(string(in)),
                in.readBoolean() ? in.readInt() : null, in.readInt(), in.readInt(), in.readLong());
    }

    private static void identity(DataOutputStream out, RunnerJobIdentity value) throws IOException {
        key(out, value.execution()); uuid(out, value.traversalId()); uuid(out, value.invocationId());
        uuid(out, value.attemptId()); uuid(out, value.runnerJobId());
    }
    private static RunnerJobIdentity identity(DataInputStream in) throws IOException {
        return new RunnerJobIdentity(key(in), uuid(in), uuid(in), uuid(in), uuid(in));
    }
    private static void key(DataOutputStream out, ExecutionKey value) throws IOException {
        string(out, value.tenantId()); uuid(out, value.processInstanceId());
    }
    private static ExecutionKey key(DataInputStream in) throws IOException { return new ExecutionKey(string(in), uuid(in)); }
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void instant(DataOutputStream out, Instant value) throws IOException { string(out, value.toString()); }
    private static Instant instant(DataInputStream in) throws IOException { return Instant.parse(string(in)); }
    private static void payload(DataOutputStream out, OpaquePayload value) throws IOException {
        string(out, value.contentType()); bytes(out, value.bytes());
    }
    private static OpaquePayload payload(DataInputStream in) throws IOException {
        String type = string(in); return OpaquePayload.of(bytes(in, 4_194_304), type);
    }
    private static void strings(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : new TreeSet<>(values)) string(out, value);
    }
    private static Set<String> strings(DataInputStream in) throws IOException {
        int count = count(in, 128); var values = new TreeSet<String>();
        for (int i = 0; i < count; i++) if (!values.add(string(in))) throw new IllegalArgumentException("duplicate value");
        return Set.copyOf(values);
    }
    private static void string(DataOutputStream out, String value) throws IOException { bytes(out, value.getBytes(StandardCharsets.UTF_8)); }
    private static String string(DataInputStream in) throws IOException {
        byte[] bytes = bytes(in, 65_536);
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("invalid runner UTF-8", invalid); }
    }
    private static void bytes(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] bytes(DataInputStream in, int max) throws IOException {
        int size = count(in, max);
        if (size > in.available()) throw new IllegalArgumentException("truncated runner field");
        return in.readNBytes(size);
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > max) throw new IllegalArgumentException("runner field exceeds bound");
        return size;
    }
    private static byte[] encode(Writer writer) {
        return encode(MAGIC, writer);
    }
    private static byte[] encode(int magic, Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream() {
                @Override public synchronized void write(int value) {
                    if (count == MAX_BYTES) throw new IllegalArgumentException("runner state quota exceeded");
                    super.write(value);
                }
                @Override public synchronized void write(byte[] value, int offset, int length) {
                    if (length > MAX_BYTES - count) throw new IllegalArgumentException("runner state quota exceeded");
                    super.write(value, offset, length);
                }
            };
            var out = new DataOutputStream(bytes);
            out.writeInt(magic); writer.write(out); out.flush();
            if (bytes.size() > MAX_BYTES - 32) throw new IllegalArgumentException("runner state quota exceeded");
            byte[] body = bytes.toByteArray(); out.write(digest(body)); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> T decode(byte[] bytes, Reader<T> reader) {
        return decode(bytes, MAGIC, reader);
    }
    private static <T> T decode(byte[] bytes, int magic, Reader<T> reader) {
        if (bytes == null || bytes.length < 36 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("invalid runner state size");
        byte[] body = java.util.Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), java.util.Arrays.copyOfRange(bytes, body.length, bytes.length))) {
            throw new IllegalArgumentException("runner state digest mismatch");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != magic) throw new IllegalArgumentException("unsupported runner state version");
            T value = reader.read(in);
            if (in.available() != 0) throw new IllegalArgumentException("trailing runner state");
            return value;
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid runner state", invalid); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
}
