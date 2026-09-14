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
    /** Maximum encoded document size, including its corruption-detection digest. */
    public static final int MAX_BYTES = 16_777_216;
    private static final int MAGIC = 0x52524a31;
    private RunnerCodec() { }

    /**
     * Encodes a bounded trusted process workspace using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] workspace(RunnerWorkspaceState value) {
        return encode(out -> {
            key(out, value.execution()); uuid(out, value.workspaceId()); string(out, value.runnerId());
            out.writeInt(value.jobs().size());
            for (var entry : value.jobs().values()) { job(out, entry.job()); payload(out, entry.continuation()); out.writeBoolean(entry.continuationUncertain()); }
        });
    }

    /**
     * Decodes a trusted process workspace, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerWorkspaceState workspace(byte[] bytes) {
        return decode(bytes, in -> {
            ExecutionKey execution = key(in); UUID workspace = uuid(in); String runner = string(in);
            var jobs = new LinkedHashMap<UUID, RunnerWorkspaceState.Entry>();
            int size = count(in, RunnerWorkspaceState.MAX_JOBS);
            for (int i = 0; i < size; i++) {
                RunnerJob job = job(in);
                if (jobs.put(job.identity().runnerJobId(), new RunnerWorkspaceState.Entry(job, payload(in), in.readBoolean())) != null) {
                    throw new IllegalArgumentException("duplicate stored runner job");
                }
            }
            return new RunnerWorkspaceState(execution, workspace, runner, jobs);
        });
    }

    /**
     * Encodes a bounded immutable agent definition using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] definition(AgentDefinition value) { return encode(out -> definition(out, value)); }
    /**
     * Decodes a immutable agent definition, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static AgentDefinition definition(byte[] bytes) { return decode(bytes, RunnerCodec::definition); }
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
    public static byte[] result(RunnerResult value) { return encode(out -> result(out, value)); }
    /**
     * Decodes a sealed terminal report, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerResult result(byte[] bytes) { return decode(bytes, RunnerCodec::result); }
    /**
     * Encodes a bounded runner dispatch view without graph checkpoints using protocol version one.
     * @param value validated immutable value
     * @return independent encoded bytes with an integrity digest
     */
    public static byte[] assignment(RunnerAssignment value) {
        return encode(out -> { out.writeInt(value.protocolVersion()); uuid(out, value.workspaceId()); job(out, value.job()); });
    }
    /**
     * Decodes a runner dispatch view without graph checkpoints, rejecting corruption, trailing bytes and invalid bounds.
     * @param bytes complete version-one document
     * @return validated immutable value
     */
    public static RunnerAssignment assignment(byte[] bytes) {
        return decode(bytes, in -> new RunnerAssignment(in.readInt(), uuid(in), job(in)));
    }

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

    private static RunnerJob job(DataInputStream in) throws IOException {
        RunnerJobIdentity identity = identity(in); AgentDefinition definition = definition(in); String command = string(in);
        RunnerRegistration runner = registration(in); RunnerPolicy authority = policy(in); OpaquePayload input = payload(in);
        Instant deadline = instant(in); Instant updated = instant(in); long revision = in.readLong(); long fence = in.readLong();
        RunnerJob.State state = RunnerJob.State.valueOf(string(in));
        RunnerJob.StopReason reason = RunnerJob.StopReason.valueOf(string(in));
        Instant lease = in.readBoolean() ? instant(in) : null;
        RunnerResult result = in.readBoolean() ? result(in) : null;
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
    }

    private static AgentDefinition definition(DataInputStream in) throws IOException {
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
        return new AgentDefinition(reference, instructions, runtime, model, commands, strings(in), strings(in),
                policy(in), Duration.parse(string(in)), string(in));
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
    }

    private static RunnerResult result(DataInputStream in) throws IOException {
        String outcome = string(in); OpaquePayload payload = payload(in); UUID quiescence = uuid(in);
        var artifacts = new ArrayList<RunnerArtifact>(); int count = count(in, 128);
        for (int i = 0; i < count; i++) artifacts.add(new RunnerArtifact(identity(in), uuid(in),
                RunnerArtifact.Kind.valueOf(string(in)), string(in), in.readLong()));
        return new RunnerResult(outcome, payload, artifacts, quiescence);
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
            out.writeInt(MAGIC); writer.write(out); out.flush();
            if (bytes.size() > MAX_BYTES - 32) throw new IllegalArgumentException("runner state quota exceeded");
            byte[] body = bytes.toByteArray(); out.write(digest(body)); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> T decode(byte[] bytes, Reader<T> reader) {
        if (bytes == null || bytes.length < 36 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("invalid runner state size");
        byte[] body = java.util.Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), java.util.Arrays.copyOfRange(bytes, body.length, bytes.length))) {
            throw new IllegalArgumentException("runner state digest mismatch");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("unsupported runner state version");
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
