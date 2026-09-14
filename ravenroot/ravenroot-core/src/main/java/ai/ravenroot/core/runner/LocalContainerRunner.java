package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.runner.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Reference Linux Docker runner. Workspace state is a stopped-container filesystem snapshot,
 * never a host bind mount. Requires a daemon storage driver enforcing per-container size quotas;
 * unsupported storage options fail closed. A persisted started marker forbids redispatch.
 */
public final class LocalContainerRunner implements RunnerDriver {
    @FunctionalInterface public interface ArtifactPublisher {
        RunnerArtifact publish(RunnerAssignment assignment, RunnerArtifact.Kind kind, byte[] bytes) throws Exception;
    }
    private final RunnerRegistration registration;
    private final Path docker;
    private final Path state;
    private final Map<String, String> runtimeImages;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Clock clock;
    private final ArtifactPublisher artifacts;
    private final ConcurrentMap<UUID, Process> active = new ConcurrentHashMap<>();
    private final Set<String> quotaAttested = ConcurrentHashMap.newKeySet();
    private final Semaphore executionSlots = new Semaphore(4);

    public LocalContainerRunner(RunnerRegistration registration, Path docker, Path state,
                                Map<String, String> runtimeImages, Clock clock) throws IOException {
        this(registration, docker, state, runtimeImages, clock, (assignment, kind, bytes) -> {
            throw new IllegalStateException("runner artifact publisher is not configured");
        });
    }
    public LocalContainerRunner(RunnerRegistration registration, Path docker, Path state,
                                Map<String, String> runtimeImages, Clock clock, ArtifactPublisher artifacts) throws IOException {
        this.registration = Objects.requireNonNull(registration); this.docker = docker.toRealPath();
        this.state = state.toAbsolutePath().normalize(); Files.createDirectories(this.state);
        if (!this.state.equals(this.state.toRealPath()) || !Files.isExecutable(this.docker)) {
            throw new IllegalArgumentException("runner paths must be operator-owned real paths");
        }
        this.runtimeImages = Map.copyOf(runtimeImages); this.clock = Objects.requireNonNull(clock);
        this.artifacts = Objects.requireNonNull(artifacts);
        if (runtimeImages.isEmpty() || runtimeImages.values().stream().anyMatch(value -> !value.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("runner runtime images must be local immutable image digests");
        }
        var capabilities = registration.capabilities().capabilities();
        if (!registration.trustProfile().equals("local-container-v1")
                || capabilities.contains(RunnerPolicy.Capability.NETWORK_EGRESS)
                || capabilities.contains(RunnerPolicy.Capability.SECRET_ACCESS)
                || capabilities.contains(RunnerPolicy.Capability.ADDITIONAL_MOUNTS)
                || capabilities.contains(RunnerPolicy.Capability.TOOL_CALL)) {
            throw new IllegalArgumentException("reference runner cannot enforce advertised external grants");
        }
    }

    @Override public RunnerRegistration registration() { return registration; }

    /** Detects daemons that accept storage options but silently ignore them. Never bypass this probe. */
    public synchronized void verifyWorkspaceQuota(String image) throws Exception {
        if (!runtimeImages.containsValue(image)) throw new IllegalArgumentException("quota probe requires an approved image");
        if (quotaAttested.contains(image)) return;
        String proof = new String(command(Duration.ofSeconds(30), 256, "run", "--rm", "--pull=never",
                "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65532:65532",
                "--memory=67108864", "--pids-limit=16", "--storage-opt=size=4194304", "--entrypoint=/bin/sh",
                image, "-c", "set -eu; command -v dd >/dev/null; "
                        + "dd if=/dev/zero of=/workspace/.ravenroot-quota-probe bs=1048576 count=1 >/dev/null 2>&1; "
                        + "if dd if=/dev/zero of=/workspace/.ravenroot-quota-probe bs=1048576 count=8 2>/dev/null; "
                        + "then exit 42; else test \"$?\" -eq 1; fi; test -s /workspace/.ravenroot-quota-probe; printf quota-enforced"),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!proof.equals("quota-enforced")) throw new IllegalStateException("runner workspace quota is not enforced");
        quotaAttested.add(image);
    }

    @Override public CompletionStage<RunnerResult> execute(RunnerAssignment assignment) {
        if (!executionSlots.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("runner execution capacity exhausted"));
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, false);
            Path workspace = workspace(assignment);
            try {
                String volumes = new String(command(Duration.ofSeconds(10), 4096, "image", "inspect", "--format={{json .Config.Volumes}}",
                        runtimeImages.get(assignment.job().definition().runtimeProfile())), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!volumes.equals("null") && !volumes.equals("{}")) {
                    throw new IllegalArgumentException("runtime images must not declare implicit volumes");
                }
                if (assignment.job().authority().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE)) {
                    verifyWorkspaceQuota(runtimeImages.get(assignment.job().definition().runtimeProfile()));
                }
                Files.createDirectories(workspace); requireSafe(workspace);
                try (var channel = FileChannel.open(workspace.resolve("workspace.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    requireOwner(workspace, assignment, true);
                    Path marker = workspace.resolve(assignment.job().identity().runnerJobId() + ".started");
                    // CREATE_NEW is the durable exactly-once dispatch guard, also after a JVM restart.
                    try (var started = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                        started.write(java.nio.ByteBuffer.wrap(RunnerCodec.assignment(assignment))); started.force(true);
                    }
                    String base = runtimeImages.get(assignment.job().definition().runtimeProfile());
                    Path snapshot = workspace.resolve("snapshot.json");
                    String image = base;
                    long used = 0;
                    if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
                        var saved = RunnerJson.read(read(snapshot, 4096));
                        if (!base.equals(RunnerJson.text(saved, "base"))) throw new IllegalStateException("process runtime image is pinned");
                        image = RunnerJson.text(saved, "image");
                        used = RunnerJson.number(saved, "usedBytes");
                        if (used < 0 || used >= assignment.job().authority().limits().workspaceBytes()) {
                            throw new IllegalStateException("process workspace quota exhausted");
                        }
                        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid workspace image");
                    }
                    var command = containerCommand(assignment, image, assignment.job().authority().limits().workspaceBytes() - used);
                    Process process = new ProcessBuilder(command).start();
                    active.put(assignment.job().identity().runnerJobId(), process);
                    var output = executor.submit(() -> bounded(process.getInputStream(), assignment.job().authority().limits().payloadBytes()));
                    var errors = executor.submit(() -> bounded(process.getErrorStream(), Math.min(65_536, assignment.job().authority().limits().logBytes())));
                    try {
                        try (var stdin = process.getOutputStream()) { stdin.write(request(assignment)); }
                        long remaining = Math.max(1, Duration.between(clock.instant(), assignment.job().deadline()).toMillis());
                        if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                            stop(assignment); process.destroyForcibly();
                            throw new IllegalStateException("runner deadline exceeded; reconcile stopped-container evidence");
                        }
                        byte[] stdout = output.get(10, TimeUnit.SECONDS);
                        byte[] stderr = errors.get(10, TimeUnit.SECONDS);
                        atomic(workspace.resolve(assignment.job().identity().runnerJobId() + ".stderr"), stderr);
                        if (process.exitValue() != 0) throw new IllegalStateException("runner exited without an accepted result");
                        return seal(assignment, workspace, base, stdout);
                    } catch (Exception uncertain) {
                        // A dead Docker CLI is not evidence that the daemon stopped its container.
                        try { stop(assignment); } catch (Exception stopFailure) { uncertain.addSuppressed(stopFailure); }
                        throw uncertain;
                    } finally {
                        active.remove(assignment.job().identity().runnerJobId(), process);
                        output.cancel(true); errors.cancel(true);
                        if (process.isAlive()) { stop(assignment); process.destroyForcibly(); }
                    }
                }
            } catch (Exception failure) { throw new CompletionException("runner effect is unknown; report-only reconciliation required", failure); }
        }, executor).whenComplete((ignored, failure) -> executionSlots.release());
    }

    /** Command construction is testable without a daemon. No graph strings become launcher options. */
    public List<String> containerCommand(RunnerAssignment assignment, String image) {
        return containerCommand(assignment, image, assignment.job().authority().limits().workspaceBytes());
    }
    private List<String> containerCommand(RunnerAssignment assignment, String image, long remainingWorkspaceBytes) {
        validate(assignment, false);
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("unpinned runner image");
        var limits = assignment.job().authority().limits();
        var args = new ArrayList<>(List.of(docker.toString(), "run", "--pull=never", "--name", container(assignment),
                "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65532:65532",
                "--pids-limit=" + (assignment.job().authority().capabilities().contains(RunnerPolicy.Capability.PROCESS_EXECUTE)
                    ? limits.processes() : 1), "--memory=" + limits.memoryBytes(), "--memory-swap=" + limits.memoryBytes(),
                "--cpus=1", "--no-healthcheck", "--log-driver=local", "--log-opt=max-size=1m", "--log-opt=max-file=1", "--log-opt=compress=false",
                "--ulimit=nofile=256:256", "--workdir=/workspace",
                "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=8388608", "--interactive"));
        if (!assignment.job().authority().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE)) args.add("--read-only");
        else args.add("--storage-opt=size=" + remainingWorkspaceBytes);
        args.add(image);
        return List.copyOf(args);
    }

    @Override public CompletionStage<Void> cancel(RunnerAssignment assignment) {
        return CompletableFuture.runAsync(() -> {
            validate(assignment, true);
            try { stop(assignment); }
            catch (Exception failure) { throw new CompletionException("runner stop is unconfirmed", failure); }
        }, executor);
    }

    @Override public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) {
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, true);
            Path workspace = workspace(assignment);
            try {
                requireSafe(workspace);
                try (var channel = FileChannel.open(workspace.resolve("workspace.lock"), StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    requireOwner(workspace, assignment, false);
                    Path marker = workspace.resolve(assignment.job().identity().runnerJobId() + ".started");
                    var original = RunnerCodec.assignment(read(marker, RunnerCodec.MAX_BYTES));
                    if (!original.job().identity().equals(assignment.job().identity())
                            || !original.workspaceId().equals(assignment.workspaceId())) throw new IllegalArgumentException("dispatch identity mismatch");
                    requireStopped(assignment);
                    Path result = workspace.resolve(assignment.job().identity().runnerJobId() + ".result");
                    if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) return RunnerCodec.result(read(result, 1_048_576));
                    if (assignment.job().stopReason() != RunnerJob.StopReason.NONE) {
                        // A killed process may have no application envelope. Proven quiescence
                        // still seals its partial workspace; the control plane owns stop precedence.
                        String outcome = assignment.job().command().outcomes().stream().sorted().findFirst().orElseThrow();
                        return seal(assignment, workspace, runtimeImages.get(assignment.job().definition().runtimeProfile()),
                                RunnerJson.write(Map.of("outcome", outcome, "payload", Map.of())));
                    }
                    // Reading logs and committing an already stopped filesystem does not re-execute work.
                    byte[] output = command(Duration.ofSeconds(15), assignment.job().authority().limits().payloadBytes(),
                            "logs", container(assignment));
                    return seal(assignment, workspace, runtimeImages.get(assignment.job().definition().runtimeProfile()), output);
                }
            } catch (Exception failure) { throw new CompletionException("runner outcome remains unknown", failure); }
        }, executor);
    }

    private RunnerResult seal(RunnerAssignment assignment, Path workspace, String base, byte[] output) throws Exception {
        requireStopped(assignment);
        var value = RunnerJson.read(output);
        if (!value.keySet().equals(Set.of("outcome", "payload"))) throw new IllegalArgumentException("invalid runner response envelope");
        String outcome = RunnerJson.text(value, "outcome");
        if (!assignment.job().command().outcomes().contains(outcome)) throw new IllegalArgumentException("undeclared runner outcome");
        byte[] payload = RunnerJson.write(value.get("payload"));
        if (payload.length > assignment.job().authority().limits().payloadBytes()) throw new IllegalArgumentException("runner result quota exceeded");
        Path imageRecord = workspace.resolve(assignment.job().identity().runnerJobId() + ".image");
        Path snapshot = workspace.resolve("snapshot.json");
        boolean writable = assignment.job().authority().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE);
        String image = Files.exists(imageRecord, LinkOption.NOFOLLOW_LINKS)
                ? new String(read(imageRecord, 256), java.nio.charset.StandardCharsets.UTF_8)
                : !writable ? (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)
                    ? RunnerJson.text(RunnerJson.read(read(snapshot, 4096)), "image") : base)
                : new String(command(Duration.ofSeconds(30), 256, "commit", "--pause=true",
                        "--change=LABEL ravenroot.workspace=" + assignment.workspaceId(), container(assignment)),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalStateException("workspace snapshot was not acknowledged");
        atomic(imageRecord, image.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long used = 0;
        if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            var previous = RunnerJson.read(read(snapshot, 4096));
            used = RunnerJson.number(previous, "usedBytes");
            // A lost acknowledgement must not account the same immutable layer twice.
            if (image.equals(RunnerJson.text(previous, "image"))) used = -1;
        }
        if (used >= 0) {
            long delta = !writable ? 0 : Long.parseLong(new String(command(Duration.ofSeconds(15), 64,
                    "inspect", "--size", "--format={{.SizeRw}}", container(assignment)),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
            if (delta < 0 || delta > assignment.job().authority().limits().workspaceBytes() - used) {
                throw new IllegalStateException("workspace layer exceeds its remaining quota");
            }
            atomic(snapshot, RunnerJson.write(Map.of("base", base, "image", image, "usedBytes", used + delta)));
        }
        var evidence = new ArrayList<RunnerArtifact>();
        Path stderr = workspace.resolve(assignment.job().identity().runnerJobId() + ".stderr");
        if (Files.exists(stderr, LinkOption.NOFOLLOW_LINKS)) {
            byte[] log = read(stderr, 65_536);
            if (log.length != 0) evidence.add(artifacts.publish(assignment, RunnerArtifact.Kind.STDERR, log));
        }
        evidence.add(artifacts.publish(assignment, RunnerArtifact.Kind.TEST_REPORT, payload));
        var result = new RunnerResult(outcome, OpaquePayload.of(payload, "application/json"), evidence,
                UUID.nameUUIDFromBytes(container(assignment).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        atomic(workspace.resolve(assignment.job().identity().runnerJobId() + ".result"), RunnerCodec.result(result));
        return result;
    }

    private byte[] request(RunnerAssignment assignment) {
        var value = new LinkedHashMap<String, Object>();
        value.put("protocolVersion", 1); value.put("runnerJobId", assignment.job().identity().runnerJobId().toString());
        value.put("workspaceId", assignment.workspaceId().toString()); value.put("fence", assignment.job().fence());
        value.put("definition", RunnerJson.definition(assignment.job().definition()));
        value.put("command", assignment.job().command().name()); value.put("authority", RunnerJson.policy(assignment.job().authority()));
        value.put("input", ai.ravenroot.api.payload.PayloadJson.read(assignment.job().input().bytes(), RunnerJson.LIMITS).toJava());
        return RunnerJson.write(value);
    }
    @Override public CompletionStage<Void> release(RunnerWorkspaceRelease release) {
        return CompletableFuture.runAsync(() -> {
            if (!registration.tenantId().equals(release.execution().tenantId()) || !registration.runnerId().equals(release.runnerId())
                    || clock.instant().isBefore(release.notBefore())) throw new IllegalArgumentException("invalid runner release authority");
            Path workspace = state.resolve(release.workspaceId().toString());
            if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                requireSafe(workspace);
                try (var channel = FileChannel.open(workspace.resolve("workspace.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    if (!RunnerJson.read(read(workspace.resolve("owner.json"), 1024)).equals(Map.of(
                            "tenant", release.execution().tenantId(), "process", release.execution().processInstanceId().toString()))) {
                        throw new IllegalArgumentException("workspace release owner mismatch");
                    }
                    try (var markers = Files.newDirectoryStream(workspace, "*.started")) {
                        for (Path marker : markers) {
                            var assignment = RunnerCodec.assignment(read(marker, RunnerCodec.MAX_BYTES));
                            if (!assignment.workspaceId().equals(release.workspaceId())
                                    || !assignment.job().identity().execution().equals(release.execution())
                                    || !release.jobIds().contains(assignment.job().identity().runnerJobId())) {
                                throw new IllegalArgumentException("workspace release identity mismatch");
                            }
                            String found = new String(command(Duration.ofSeconds(10), 256, "ps", "--all", "--quiet", "--no-trunc",
                                    "--filter=name=^/" + container(assignment) + "$"), java.nio.charset.StandardCharsets.UTF_8).trim();
                            if (!found.isEmpty()) {
                                if (!found.matches("[0-9a-f]{64}")) throw new IllegalStateException("ambiguous runner container");
                                requireStopped(assignment);
                                command(Duration.ofSeconds(15), 256, "rm", found);
                            }
                        }
                    }
                    String images = new String(command(Duration.ofSeconds(15), 65_536, "images", "--quiet", "--no-trunc",
                            "--filter=label=ravenroot.workspace=" + release.workspaceId()), java.nio.charset.StandardCharsets.UTF_8);
                    // Preserve Docker's newest-first order for child-before-parent snapshot cleanup.
                    for (String image : new LinkedHashSet<>(images.lines().filter(value -> !value.isBlank()).toList())) {
                        if (!image.matches("sha256:[0-9a-f]{64}") || runtimeImages.containsValue(image)) {
                            throw new IllegalStateException("invalid workspace snapshot ownership");
                        }
                        command(Duration.ofSeconds(15), 4096, "image", "rm", "--no-prune", image);
                    }
                    try (var files = Files.newDirectoryStream(workspace)) {
                        for (Path file : files) {
                            requireSafe(file);
                            if (Set.of("workspace.lock", "owner.json").contains(file.getFileName().toString())) continue;
                            String name = file.getFileName().toString();
                            if (!(name.equals("snapshot.json") || name.endsWith(".started") || name.endsWith(".result")
                                    || name.endsWith(".image") || name.endsWith(".stderr") || name.endsWith(".pending"))) {
                                throw new IllegalStateException("unexpected workspace metadata");
                            }
                            Files.delete(file);
                        }
                    }
                }
                // The stable lock inode remains. No job or snapshot data survives successful release.
            } catch (Exception failure) { throw new CompletionException("runner workspace cleanup is incomplete", failure); }
        }, executor);
    }
    private void validate(RunnerAssignment assignment, boolean reportOnly) {
        var job = assignment.job();
        if (!job.runner().equals(registration) || !runtimeImages.containsKey(job.definition().runtimeProfile())
                || !job.authority().equals(RunnerPolicy.effective(job.authority(), job.definition().policy(),
                        job.command(), registration.capabilities()))) {
            throw new IllegalArgumentException("runner assignment is not supported");
        }
        if ((!reportOnly && job.state() != RunnerJob.State.CLAIMED)
                || job.fence() < 1 || job.leaseUntil() == null || !clock.instant().isBefore(job.leaseUntil())) {
            throw new IllegalStateException("runner requires a live fenced claim");
        }
    }
    private Path workspace(RunnerAssignment assignment) { return state.resolve(assignment.workspaceId().toString()); }
    private void requireOwner(Path workspace, RunnerAssignment assignment, boolean create) throws IOException {
        var owner = Map.of("tenant", assignment.job().identity().execution().tenantId(),
                "process", assignment.job().identity().execution().processInstanceId().toString());
        Path file = workspace.resolve("owner.json");
        if (create && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(java.nio.ByteBuffer.wrap(RunnerJson.write(owner))); channel.force(true);
            }
        }
        if (!RunnerJson.read(read(file, 1024)).equals(owner)) throw new IllegalArgumentException("process workspace ownership mismatch");
    }
    private String container(RunnerAssignment assignment) { return "ravenroot-job-" + assignment.job().identity().runnerJobId(); }
    private void stop(RunnerAssignment assignment) throws Exception {
        try { requireStopped(assignment); return; }
        catch (IllegalStateException running) { /* Stop is needed; absence remains an unknown effect. */ }
        command(Duration.ofSeconds(15), 4096, "kill", container(assignment)); requireStopped(assignment);
    }
    private void requireStopped(RunnerAssignment assignment) throws Exception {
        String state = new String(command(Duration.ofSeconds(10), 256, "inspect", "--format={{.State.Running}}", container(assignment)),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!state.equals("false")) throw new IllegalStateException("runner process tree is not quiescent");
    }
    private byte[] command(Duration timeout, long limit, String... arguments) throws Exception {
        var args = new ArrayList<String>(); args.add(docker.toString()); args.addAll(List.of(arguments));
        Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = executor.submit(() -> bounded(process.getInputStream(), limit));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("runner supervisor timed out");
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("runner supervisor refused operation");
            return bytes;
        } finally { output.cancel(true); if (process.isAlive()) process.destroyForcibly(); }
    }
    private static byte[] bounded(InputStream input, long limit) throws IOException {
        if (limit > 1_048_576) limit = 1_048_576;
        byte[] bytes = input.readNBytes(Math.toIntExact(limit) + 1);
        if (bytes.length > limit) throw new IllegalArgumentException("runner stream quota exceeded");
        return bytes;
    }
    private void atomic(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "runner-", ".pending");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private byte[] read(Path path, int max) throws IOException {
        requireSafe(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { return bounded(input, max); }
    }
    private void requireSafe(Path path) throws IOException {
        if (!path.startsWith(state) || !path.equals(path.toRealPath())) throw new IllegalArgumentException("unsafe runner state path");
    }
    @Override public void close() {
        // Do not claim quiescence merely because the control process is stopping.
        active.values().forEach(Process::destroy); executor.shutdownNow();
    }
}
