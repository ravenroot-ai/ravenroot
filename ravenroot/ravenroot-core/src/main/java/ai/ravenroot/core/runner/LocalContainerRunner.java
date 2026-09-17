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
    private final RunnerWorkerConfiguration configuration;
    private final ConcurrentMap<UUID, Process> active = new ConcurrentHashMap<>();
    private final Set<String> quotaAttested = ConcurrentHashMap.newKeySet();
    private final Semaphore executionSlots;
    private final FileChannel supervisorChannel;
    private final java.nio.channels.FileLock supervisorLock;
    private final ConcurrentMap<UUID, java.util.concurrent.locks.ReentrantReadWriteLock> workspaceGates = new ConcurrentHashMap<>();
    private volatile RunnerAgentRuntime agentRuntime;
    private final Set<UUID> stopping = ConcurrentHashMap.newKeySet();
    private final Set<String> stoppedWorkspaces = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    public LocalContainerRunner withAgentRuntime(RunnerAgentRuntime runtime) {
        if (agentRuntime != null) throw new IllegalStateException("Agent runtime already configured");
        agentRuntime = Objects.requireNonNull(runtime); return this;
    }

    public LocalContainerRunner(RunnerRegistration registration, Path docker, Path state,
                                Map<String, String> runtimeImages, Clock clock) throws IOException {
        this(registration, docker, state, runtimeImages, clock, (assignment, kind, bytes) -> {
            throw new IllegalStateException("runner artifact publisher is not configured");
        });
    }
    public LocalContainerRunner(RunnerRegistration registration, Path docker, Path state,
                                Map<String, String> runtimeImages, Clock clock, ArtifactPublisher artifacts) throws IOException {
        this(registration, docker, state, runtimeImages, clock, artifacts, RunnerWorkerConfiguration.defaults());
    }
    public LocalContainerRunner(RunnerRegistration registration, Path docker, Path state,
                                Map<String, String> runtimeImages, Clock clock, ArtifactPublisher artifacts,
                                RunnerWorkerConfiguration configuration) throws IOException {
        this.executionSlots = new Semaphore(Objects.requireNonNull(configuration).maxConcurrentJobs());
        this.configuration = configuration;
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
        supervisorChannel = FileChannel.open(this.state.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            supervisorLock = supervisorChannel.tryLock();
            if (supervisorLock == null) throw new IllegalStateException("worker state directory already has a live supervisor");
        } catch (RuntimeException | IOException failed) { supervisorChannel.close(); throw failed; }
    }

    @Override public RunnerRegistration registration() { return registration; }
    @Override public Set<String> runtimeProfiles() { return runtimeImages.keySet(); }

    /** Detects daemons that accept storage options but silently ignore them. Never bypass this probe. */
    public synchronized void verifyWorkspaceQuota(String image) throws Exception {
        if (!runtimeImages.containsValue(image)) throw new IllegalArgumentException("quota probe requires an approved image");
        if (quotaAttested.contains(image)) return;
        String proof = new String(command(configuration.driverCommandTimeout(), 256, "run", "--rm", "--pull=never",
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
        if (assignment.workspace() != null) return executeGoverned(assignment);
        if (!executionSlots.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("runner execution capacity exhausted"));
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, false);
            Path workspace = workspace(assignment);
            try {
                String volumes = new String(command(configuration.driverCommandTimeout(), 4096, "image", "inspect", "--format={{json .Config.Volumes}}",
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
                        byte[] stdout = output.get(configuration.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
                        byte[] stderr = errors.get(configuration.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
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
        stopping.add(assignment.job().identity().runnerJobId());
        if (agentRuntime != null) agentRuntime.models().cancel(assignment);
        return CompletableFuture.runAsync(() -> {
            validate(assignment, true);
            try { stop(assignment); }
            catch (Exception failure) { throw new CompletionException("runner stop is unconfirmed", failure); }
        }, executor);
    }
    @Override public CompletionStage<Void> stopWorkspace(RunnerAssignment assignment) {
        if (assignment.workspace() == null || !assignment.workspace().stopRequested()
                || !registration.runnerId().equals(assignment.workspace().runnerId())
                || !registration.tenantId().equals(assignment.job().identity().execution().tenantId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Workspace stop authority is missing"));
        }
        stoppedWorkspaces.add(stopKey(assignment));
        if (agentRuntime != null) agentRuntime.models().cancelWorkspace(assignment);
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(workspace(assignment)); requireSafe(workspace(assignment));
                requireOwner(workspace(assignment), assignment, true);
                atomic(workspace(assignment).resolve("workspace.stopped"), stopKey(assignment).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.EPHEMERAL) {
                    try (var children = Files.newDirectoryStream(workspace(assignment), "*.child")) {
                        for (Path child : children) {
                            var invocation = RunnerCodec.assignment(read(child, RunnerCodec.MAX_BYTES));
                            if (Files.exists(workspace(invocation).resolve(invocation.job().identity().runnerJobId() + ".started"), LinkOption.NOFOLLOW_LINKS)) stop(invocation);
                        }
                    }
                    return;
                }
                boolean dispatched;
                try (var markers = Files.newDirectoryStream(workspace(assignment), "*.started")) { dispatched = markers.iterator().hasNext(); }
                if (!dispatched) return;
                if (assignment.workspace().profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE) stop(assignment);
                else try (var markers = Files.newDirectoryStream(workspace(assignment), "*.started")) {
                    for (Path marker : markers) {
                        var job = RunnerCodec.assignment(read(marker, RunnerCodec.MAX_BYTES));
                        if (job.lifecycleCommand() == null) stop(job);
                    }
                }
            } catch (Exception failure) { throw new CompletionException("Workspace stop remains unconfirmed", failure); }
        }, executor);
    }

    @Override public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) {
        if (assignment.workspace() != null) return reconcileGoverned(assignment);
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
                    if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) return RunnerCodec.result(read(result, RunnerCodec.MAX_BYTES));
                    if (assignment.job().stopReason() != RunnerJob.StopReason.NONE) {
                        // A killed process may have no application envelope. Proven quiescence
                        // still seals its partial workspace; the control plane owns stop precedence.
                        String outcome = assignment.job().command().outcomes().stream().sorted().findFirst().orElseThrow();
                        return seal(assignment, workspace, runtimeImages.get(assignment.job().definition().runtimeProfile()),
                                RunnerJson.write(Map.of("outcome", outcome, "payload", Map.of())));
                    }
                    // Reading logs and committing an already stopped filesystem does not re-execute work.
                    byte[] output = command(configuration.driverCommandTimeout(), assignment.job().authority().limits().payloadBytes(),
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
                : new String(command(configuration.driverCommandTimeout(), 256, "commit", "--pause=true",
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
            long delta = !writable ? 0 : Long.parseLong(new String(command(configuration.driverCommandTimeout(), 64,
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
            if (!release.physicalCleanup()) return;
            Path workspace = state.resolve(release.workspaceId().toString());
            if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                requireSafe(workspace);
                try (var channel = FileChannel.open(workspace.resolve("workspace.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    Path receipt = workspace.resolve("released.json");
                    if (release.workspaceScope() == WorkspaceProfile.Scope.NAMED && !Files.exists(workspace.resolve("owner.json"), LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS) && RunnerJson.read(read(receipt, 1024)).equals(Map.of(
                                "tenant", release.execution().tenantId(), "process", release.execution().processInstanceId().toString(), "generation", release.generation()))) return;
                        throw new IllegalStateException("named cleanup ownership evidence is unavailable");
                    }
                    Map<String, Object> expectedOwner = new LinkedHashMap<>(Map.of("tenant", release.execution().tenantId(),
                            "process", release.execution().processInstanceId().toString()));
                    if (release.workspaceScope() == WorkspaceProfile.Scope.NAMED) expectedOwner.put("generation", release.generation());
                    if (!RunnerJson.read(read(workspace.resolve("owner.json"), 1024)).equals(expectedOwner)) {
                        throw new IllegalArgumentException("workspace release owner mismatch");
                    }
                    try (var markers = Files.newDirectoryStream(workspace, "*.started")) {
                        for (Path marker : markers) {
                            var assignment = RunnerCodec.assignment(read(marker, RunnerCodec.MAX_BYTES));
                            boolean namedHistory = release.workspaceScope() == WorkspaceProfile.Scope.NAMED && assignment.workspace() != null
                                    && assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.NAMED
                                    && assignment.workspace().generation() <= release.generation()
                                    && assignment.job().identity().execution().tenantId().equals(release.execution().tenantId());
                            if (!assignment.workspaceId().equals(release.workspaceId()) || !namedHistory
                                    && (!assignment.job().identity().execution().equals(release.execution())
                                    || !release.jobIds().contains(assignment.job().identity().runnerJobId()))) {
                                throw new IllegalArgumentException("workspace release identity mismatch");
                            }
                            String found = new String(command(configuration.driverCommandTimeout(), 256, "ps", "--all", "--quiet", "--no-trunc",
                                    "--filter=name=^/" + container(assignment) + "$"), java.nio.charset.StandardCharsets.UTF_8).trim();
                            if (!found.isEmpty()) {
                                if (!found.matches("[0-9a-f]{64}")) throw new IllegalStateException("ambiguous runner container");
                                requireStopped(assignment);
                                command(configuration.driverCommandTimeout(), 256, "rm", found);
                            }
                        }
                    }
                    try (var children = Files.newDirectoryStream(workspace, "*.child")) {
                        for (Path child : children) {
                            var invocation = RunnerCodec.assignment(read(child, RunnerCodec.MAX_BYTES));
                            if (!release.jobIds().contains(invocation.job().identity().runnerJobId())
                                    || !release.workspaceId().equals(invocation.workspace().workspaceId())
                                    || !release.execution().equals(invocation.job().identity().execution()))
                                throw new IllegalStateException("ephemeral child release identity mismatch");
                            release(new RunnerWorkspaceRelease(release.protocolVersion(), release.execution(), invocation.workspaceId(), release.runnerId(),
                                    Set.of(invocation.job().identity().runnerJobId()), release.notBefore())).toCompletableFuture().join();
                        }
                    }
                    String images = new String(command(configuration.driverCommandTimeout(), configuration.maxSupervisorOutputBytes(), "images", "--quiet", "--no-trunc",
                            "--filter=label=ravenroot.workspace=" + release.workspaceId()), java.nio.charset.StandardCharsets.UTF_8);
                    // Preserve Docker's newest-first order for child-before-parent snapshot cleanup.
                    for (String image : new LinkedHashSet<>(images.lines().filter(value -> !value.isBlank()).toList())) {
                        if (!image.matches("sha256:[0-9a-f]{64}") || runtimeImages.containsValue(image)) {
                            throw new IllegalStateException("invalid workspace snapshot ownership");
                        }
                        command(configuration.driverCommandTimeout(), 4096, "image", "rm", "--no-prune", image);
                    }
                    try (var files = Files.newDirectoryStream(workspace)) {
                        for (Path file : files) {
                            requireSafe(file);
                            if (Set.of("workspace.lock", "owner.json", "workspace.stopped").contains(file.getFileName().toString())) continue;
                            String name = file.getFileName().toString();
                            if (!(name.equals("snapshot.json") || name.equals("released.json") || name.endsWith(".started") || name.endsWith(".result")
                                    || name.endsWith(".image") || name.endsWith(".runtime") || name.endsWith(".quota") || name.endsWith(".stderr") || name.endsWith(".pending") || name.endsWith(".child"))) {
                                throw new IllegalStateException("unexpected workspace metadata");
                            }
                            Files.delete(file);
                        }
                    }
                    if (release.workspaceScope() == WorkspaceProfile.Scope.NAMED) {
                        atomic(receipt, RunnerJson.write(expectedOwner));
                        Files.deleteIfExists(workspace.resolve("workspace.stopped"));
                        Files.delete(workspace.resolve("owner.json"));
                    }
                }
                // The stable lock inode remains. No job or snapshot data survives successful release.
            } catch (Exception failure) { throw new CompletionException("runner workspace cleanup is incomplete", failure); }
        }, executor).thenRun(() -> {
            if (agentRuntime != null) agentRuntime.models().released(release);
            stopping.removeAll(release.jobIds());
            stoppedWorkspaces.remove(release.workspaceId() + ":" + release.generation());
        });
    }
    private void validate(RunnerAssignment assignment, boolean reportOnly) {
        var job = assignment.job();
        if (!job.runner().equals(registration) || !runtimeImages.containsKey(job.definition().runtimeProfile())
                || !job.authority().equals(RunnerPolicy.effective(job.authority(), job.definition().policy(),
                        job.command(), registration.capabilities()))) {
            throw new IllegalArgumentException("runner assignment is not supported");
        }
        if (assignment.workspace() != null && !reportOnly) {
            var resource = assignment.workspace();
            if (resource.profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_INVOCATION
                    && resource.profile().workspaceScope() != WorkspaceProfile.Scope.EPHEMERAL && resource.profile().capacity().mutatingUsers() > 1)
                throw new IllegalArgumentException("snapshot driver cannot merge concurrent mutating per-invocation filesystems");
            if (persistent(assignment) && assignment.lifecycleCommand() == null) {
                var effective = job.authority().limits(); var physical = resource.profile().policy().limits();
                if (effective.memoryBytes() < physical.memoryBytes() || effective.processes() < physical.processes()
                        || effective.workspaceBytes() < physical.workspaceBytes())
                    throw new IllegalArgumentException("shared-container driver cannot silently widen a lower per-invocation physical resource ceiling");
            }
        }
        if ((!reportOnly && job.state() != RunnerJob.State.CLAIMED)
                || job.fence() < 1 || job.leaseUntil() == null || !clock.instant().isBefore(job.leaseUntil())) {
            throw new IllegalStateException("runner requires a live fenced claim");
        }
    }
    private Path workspace(RunnerAssignment assignment) { return state.resolve(assignment.workspaceId().toString()); }
    private void requireOwner(Path workspace, RunnerAssignment assignment, boolean create) throws IOException {
        Map<String, Object> owner = new LinkedHashMap<>(Map.of("tenant", assignment.job().identity().execution().tenantId(),
                "process", assignment.job().identity().execution().processInstanceId().toString()));
        boolean named = assignment.workspace() != null && assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.NAMED;
        if (named) owner.put("generation", assignment.workspace().generation());
        Path file = workspace.resolve("owner.json");
        if (create && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(java.nio.ByteBuffer.wrap(RunnerJson.write(owner))); channel.force(true);
            }
        }
        var prior = RunnerJson.read(read(file, 1024));
        if (!prior.equals(owner) && named && create && "open".equals(assignment.lifecycleCommand())
                && owner.get("tenant").equals(prior.get("tenant"))
                && RunnerJson.number(prior, "generation") < assignment.workspace().generation()) {
            // The store has fenced the new generation. Local evidence must independently prove the predecessor stopped.
            try (var markers = Files.newDirectoryStream(workspace, "*.started")) {
                for (Path marker : markers) {
                    var previous = RunnerCodec.assignment(read(marker, RunnerCodec.MAX_BYTES));
                    if (Files.exists(workspace.resolve(container(previous) + ".runtime"), LinkOption.NOFOLLOW_LINKS)) requireStopped(previous);
                }
            } catch (Exception unknown) { throw new IOException("named Workspace predecessor is not quiescent", unknown); }
            atomic(file, RunnerJson.write(owner)); prior = owner;
        }
        if (!prior.equals(owner)) throw new IllegalArgumentException("process workspace ownership mismatch");
    }
    private String container(RunnerAssignment assignment) {
        return assignment.workspace() != null && assignment.workspace().profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE
                ? "ravenroot-workspace-" + assignment.workspaceId() + (assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.NAMED
                    ? "-g" + assignment.workspace().generation() : "") : "ravenroot-job-" + assignment.job().identity().runnerJobId();
    }

    private CompletionStage<RunnerResult> executeGoverned(RunnerAssignment assignment) {
        if (!executionSlots.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("runner execution capacity exhausted"));
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, false);
            Path directory = workspace(assignment);
            var gate = workspaceGates.computeIfAbsent(assignment.workspaceId(), ignored -> new java.util.concurrent.locks.ReentrantReadWriteLock(true));
            var access = assignment.lifecycleCommand() == null && (persistent(assignment) || assignment.job().command().readOnly())
                    ? gate.readLock() : gate.writeLock();
            access.lock();
            boolean dispatchStarted = false;
            try {
                if (assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.EPHEMERAL && assignment.lifecycleCommand() == null) {
                    Path controller = state.resolve(assignment.workspace().workspaceId().toString()); requireSafe(controller);
                    if (stoppedOnDisk(assignment)) throw new IllegalStateException("Workspace has stopped");
                    atomic(controller.resolve(assignment.job().identity().runnerJobId() + ".child"), RunnerCodec.assignment(assignment));
                }
                Files.createDirectories(directory); requireSafe(directory);
                {
                    requireOwner(directory, assignment, true);
                    // Recovery and release share this stable inode. Initialize it before any
                    // dispatch receipt/effect; worker.lock fences other supervisors, while the
                    // local gate permits concurrent readers without overlapping JVM file locks.
                    try (var lockFile = FileChannel.open(directory.resolve("workspace.lock"), StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { lockFile.force(true); }
                    if (stoppedOnDisk(assignment) || stoppedWorkspaces.contains(stopKey(assignment))) throw new IllegalStateException("Workspace has stopped");
                    Path marker = directory.resolve(assignment.job().identity().runnerJobId() + ".started");
                    try (var started = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        started.write(java.nio.ByteBuffer.wrap(RunnerCodec.assignment(assignment))); started.force(true);
                    }
                    dispatchStarted = true;
                    String base = runtimeImages.get(assignment.workspace().profile().runtimeProfile());
                    Path pin = directory.resolve("base.image");
                    if (Files.exists(pin, LinkOption.NOFOLLOW_LINKS)) {
                        if (!base.equals(new String(read(pin, 256), java.nio.charset.StandardCharsets.UTF_8))) throw new IllegalStateException("workspace runtime image is pinned");
                    } else atomic(pin, base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String lifecycle = assignment.lifecycleCommand();
                    boolean persistent = persistent(assignment);
                    if (lifecycle != null) {
                        String outcome = switch (lifecycle) {
                            case "open" -> {
                                if (persistent) {
                                    if (assignment.workspace().runtimeId() == null) startGovernedContainer(assignment,
                                            Files.exists(directory.resolve("snapshot.json"), LinkOption.NOFOLLOW_LINKS)
                                                ? RunnerJson.text(RunnerJson.read(read(directory.resolve("snapshot.json"), 4096)), "image") : base);
                                    else requireRunning(assignment);
                                }
                                yield "ready";
                            }
                            case "inspect" -> { if (persistent) requireRunning(assignment); yield "inspected"; }
                            case "checkpoint" -> { if (persistent) checkpoint(assignment, directory, base); yield "checkpointed"; }
                            case "close", "abort" -> {
                                if (persistent) {
                                    stop(assignment);
                                    checkpoint(assignment, directory, base);
                                }
                                yield lifecycle.equals("close") ? "closed" : "aborted";
                            }
                            default -> throw new IllegalArgumentException("unsupported Workspace lifecycle command");
                        };
                        var observation = observation(assignment, directory, persistent);
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("workspaceId", assignment.workspaceId().toString()); payload.put("runtimeId", observation.runtimeId());
                        payload.put("result", "Workspace " + outcome);
                        var result = new RunnerResult(outcome, OpaquePayload.of(RunnerJson.write(payload), "application/json"),
                                List.of(), assignment.job().identity().runnerJobId(), observation);
                        atomic(directory.resolve(assignment.job().identity().runnerJobId() + ".result"), RunnerCodec.result(result));
                        return result;
                    }
                    if (persistent) requireRunning(assignment);
                    else {
                        String image = Files.exists(directory.resolve("snapshot.json"), LinkOption.NOFOLLOW_LINKS)
                                ? RunnerJson.text(RunnerJson.read(read(directory.resolve("snapshot.json"), 4096)), "image") : base;
                        startGovernedContainer(assignment, image);
                    }
                    RunnerResult result = runAgent(assignment);
                    if (!persistent) { stop(assignment); checkpoint(assignment, directory, base); }
                    result = new RunnerResult(result.outcome(), result.payload(), result.artifacts(), result.quiescenceId(),
                            observation(assignment, directory, true));
                    atomic(directory.resolve(assignment.job().identity().runnerJobId() + ".result"), RunnerCodec.result(result));
                    return result;
                }
            } catch (Exception failure) {
                // A refused replay has no new effect to stop. Killing here would destroy the
                // retained runtime belonging to the original completed dispatch or its successor.
                if (dispatchStarted) try { stop(assignment); } catch (Exception unconfirmed) { failure.addSuppressed(unconfirmed); }
                throw new CompletionException("Workspace effect requires fenced reconciliation; never redispatch", failure);
            } finally { access.unlock(); }
        }, executor).whenComplete((ignored, failure) -> executionSlots.release());
    }

    private void startGovernedContainer(RunnerAssignment assignment, String image) throws Exception {
        if (stopping.contains(assignment.job().identity().runnerJobId()) || stoppedWorkspaces.contains(stopKey(assignment)) || stoppedOnDisk(assignment))
            throw new IllegalStateException("Workspace has stopped");
        if (assignment.workspace().profile().policy().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE)) verifyWorkspaceQuota(runtimeImages.get(assignment.workspace().profile().runtimeProfile()));
        String volumes = new String(command(configuration.driverCommandTimeout(), 4096, "image", "inspect", "--format={{json .Config.Volumes}}", image), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!volumes.equals("null") && !volumes.equals("{}")) throw new IllegalArgumentException("runtime images must not declare volumes");
        var limits = assignment.lifecycleCommand() == null && !persistent(assignment)
                ? assignment.job().authority().limits() : assignment.workspace().profile().policy().limits();
        var args = new ArrayList<>(List.of("run", "--detach", "--pull=never", "--name", container(assignment),
                "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65532:65532",
                "--pids-limit=" + limits.processes(), "--memory=" + limits.memoryBytes(), "--memory-swap=" + limits.memoryBytes(),
                "--cpus=" + java.math.BigDecimal.valueOf(assignment.workspace().profile().cpuMillicores(), 3).toPlainString(),
                "--workdir=/workspace", "--no-healthcheck", "--log-driver=none", "--entrypoint=python3"));
        long used = 0;
        Path snapshot = workspace(assignment).resolve("snapshot.json");
        if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) used = RunnerJson.number(RunnerJson.read(read(snapshot, 4096)), "usedBytes");
        boolean writable = assignment.workspace().profile().policy().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE)
                && (persistent(assignment) || assignment.lifecycleCommand() != null || !assignment.job().command().readOnly());
        if (writable) {
            if (used < 0 || used >= limits.workspaceBytes()) throw new IllegalStateException("Workspace storage quota exhausted");
            args.add("--storage-opt=size=" + (limits.workspaceBytes() - used));
        }
        else args.add("--read-only");
        args.add(image); args.add("-c");
        args.add("import signal; signal.signal(signal.SIGCHLD,signal.SIG_IGN); signal.pause()");
        String acknowledged = new String(command(configuration.driverCommandTimeout(), 256, args.toArray(String[]::new)), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!acknowledged.matches("[0-9a-f]{64}")) throw new IllegalStateException("runtime creation unconfirmed");
        atomic(workspace(assignment).resolve(container(assignment) + ".runtime"), acknowledged.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        atomic(workspace(assignment).resolve(container(assignment) + ".quota"), Long.toString(used).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        requireRunning(assignment);
    }
    private String runtimeId(RunnerAssignment assignment) throws IOException {
        String id = new String(read(workspace(assignment).resolve(container(assignment) + ".runtime"), 256), java.nio.charset.StandardCharsets.UTF_8);
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalStateException("physical runtime identity unavailable");
        return id;
    }
    private static boolean persistent(RunnerAssignment assignment) {
        return assignment.workspace().profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE
                && assignment.workspace().profile().workspaceScope() != WorkspaceProfile.Scope.EPHEMERAL;
    }
    private static String stopKey(RunnerAssignment assignment) { return assignment.workspace().workspaceId() + ":" + assignment.workspace().generation(); }
    private boolean stoppedOnDisk(RunnerAssignment assignment) throws IOException {
        Path marker = state.resolve(assignment.workspace().workspaceId().toString()).resolve("workspace.stopped");
        return Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                && stopKey(assignment).equals(new String(read(marker, 256), java.nio.charset.StandardCharsets.UTF_8));
    }
    private RunnerResult.WorkspaceObservation observation(RunnerAssignment assignment, Path directory, boolean hasRuntime) throws IOException {
        String checkpoint = Files.exists(directory.resolve("snapshot.json"), LinkOption.NOFOLLOW_LINKS)
                ? RunnerJson.text(RunnerJson.read(read(directory.resolve("snapshot.json"), 4096)), "image") : null;
        return new RunnerResult.WorkspaceObservation(assignment.workspaceId(), hasRuntime ? runtimeId(assignment) : null, checkpoint);
    }
    private void requireRunning(RunnerAssignment assignment) throws Exception {
        String found = new String(command(configuration.driverCommandTimeout(), 256, "inspect", "--format={{.Id}} {{.State.Running}}", container(assignment)), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!found.equals(runtimeId(assignment) + " true")) throw new IllegalStateException("pinned Workspace runtime is lost or replaced; recovery is required");
    }
    private void checkpoint(RunnerAssignment assignment, Path directory, String base) throws Exception {
        long previousBytes = 0;
        Path snapshot = directory.resolve("snapshot.json");
        Path receipt = directory.resolve(assignment.job().identity().runnerJobId() + ".image");
        previousBytes = Long.parseLong(new String(read(directory.resolve(container(assignment) + ".quota"), 64), java.nio.charset.StandardCharsets.UTF_8));
        String image = Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)
                ? new String(read(receipt, 256), java.nio.charset.StandardCharsets.UTF_8)
                : new String(command(configuration.driverCommandTimeout(), 256, "commit", "--pause=true",
                "--change=LABEL ravenroot.workspace=" + assignment.workspaceId(), container(assignment)), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalStateException("checkpoint not acknowledged");
        atomic(receipt, image.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)
                && image.equals(RunnerJson.text(RunnerJson.read(read(snapshot, 4096)), "image"))) return;
        long delta = Long.parseLong(new String(command(configuration.driverCommandTimeout(), 64, "inspect", "--size", "--format={{.SizeRw}}",
                container(assignment)), java.nio.charset.StandardCharsets.UTF_8).trim());
        long limit = assignment.workspace().profile().policy().limits().workspaceBytes();
        if (previousBytes < 0 || delta < 0 || delta > limit - previousBytes) throw new IllegalStateException("Workspace layer exceeds remaining quota");
        atomic(snapshot, RunnerJson.write(Map.of("base", base, "image", image, "usedBytes", previousBytes + delta)));
    }
    private RunnerResult runAgent(RunnerAssignment assignment) throws Exception {
        RunnerAgentRuntime runtime = Objects.requireNonNull(agentRuntime, "real Agent runtime is not configured")
                .boundedBy(assignment.job().definition());
        var configuredSkills = new LinkedHashMap<String, String>();
        for (String name : assignment.job().definition().skills()) {
            String skill = runtime.skills().get(name);
            if (skill == null) throw new IllegalArgumentException("approved Agent skill is not installed");
            configuredSkills.put(name, skill);
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("protocolVersion", 2); envelope.put("definition", RunnerJson.definition(assignment.job().definition()));
        envelope.put("authority", RunnerJson.policy(assignment.job().authority())); envelope.put("command", assignment.job().command().name());
        envelope.put("input", ai.ravenroot.api.payload.PayloadJson.read(assignment.job().input().bytes(), RunnerJson.LIMITS).toJava());
        envelope.put("workspaceId", assignment.workspaceId().toString()); envelope.put("runtimeId", runtimeId(assignment));
        var id = assignment.job().identity();
        envelope.put("sessionId", assignment.agentSessionId().toString());
        envelope.put("skills", configuredSkills); envelope.put("testCommand", runtime.testCommand());
        envelope.put("outcomes", assignment.job().command().outcomes());
        envelope.put("budgets", runtime.budgets(Duration.between(clock.instant(), assignment.job().deadline())));
        Process process = new ProcessBuilder(docker.toString(), "exec", "--interactive", container(assignment),
                "python3", "/opt/agent_runtime.py", Integer.toString(runtime.protocolBytes())).start();
        active.put(id.runnerJobId(), process);
        var errors = executor.submit(() -> bounded(process.getErrorStream(), assignment.job().authority().limits().logBytes()));
        var watchdog = executor.submit(() -> {
            long remaining = Math.max(1, Duration.between(clock.instant(), assignment.job().deadline()).toMillis());
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                stopping.add(id.runnerJobId()); runtime.models().cancel(assignment); stop(assignment);
            }
            return null;
        });
        long tokens = 0;
        int modelTurns = 0, toolCalls = 0;
        try (var output = process.getOutputStream(); var input = process.getInputStream()) {
            output.write(RunnerJson.write(envelope)); output.write('\n'); output.flush();
            for (long exchange = 0; exchange <= (long) runtime.modelTurns() + runtime.toolCalls(); exchange++) {
                if (stopping.contains(id.runnerJobId()) || stoppedWorkspaces.contains(stopKey(assignment)) || !clock.instant().isBefore(assignment.job().deadline())) throw new IllegalStateException("Agent cancelled");
                Map<String, Object> line = RunnerJson.read(readLine(input, runtime.protocolBytes()));
                if ("tool-permit".equals(line.get("type"))) {
                    if (++toolCalls > runtime.toolCalls() || stopping.contains(id.runnerJobId())
                            || stoppedWorkspaces.contains(stopKey(assignment))) throw new IllegalStateException("Agent tool budget exhausted or cancelled");
                    String tool = RunnerJson.text(line, "name");
                    var capabilities = assignment.job().authority().capabilities();
                    boolean allowed = tool.equals("finish")
                            || Set.of("read_file", "list_files").contains(tool) && capabilities.contains(RunnerPolicy.Capability.WORKSPACE_READ)
                            || tool.equals("write_file") && capabilities.contains(RunnerPolicy.Capability.WORKSPACE_WRITE)
                            || tool.equals("test") && capabilities.contains(RunnerPolicy.Capability.PROCESS_EXECUTE);
                    if (!allowed) throw new IllegalArgumentException("Agent tool authority denied");
                    output.write(RunnerJson.write(Map.of("type", "tool-permitted"))); output.write('\n'); output.flush();
                    continue;
                }
                if ("result".equals(line.get("type"))) {
                    output.close();
                    if (!process.waitFor(Math.max(1, Duration.between(clock.instant(), assignment.job().deadline()).toMillis()), TimeUnit.MILLISECONDS)
                            || process.exitValue() != 0) throw new IllegalStateException("Agent quiescence unconfirmed");
                    byte[] logs = errors.get(configuration.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    var evidence = new ArrayList<RunnerArtifact>();
                    if (logs.length != 0) evidence.add(artifacts.publish(assignment, RunnerArtifact.Kind.STDERR, logs));
                    byte[] payload = RunnerJson.write(line.get("payload"));
                    if (payload.length > assignment.job().authority().limits().payloadBytes()) throw new IllegalArgumentException("Agent result quota exceeded");
                    return new RunnerResult(RunnerJson.text(line, "outcome"), OpaquePayload.of(payload, "application/json"), evidence,
                            id.runnerJobId(), observation(assignment, workspace(assignment), true));
                }
                if (!"model".equals(line.get("type")) || modelTurns++ >= runtime.modelTurns() || tokens >= runtime.modelTokens()) throw new IllegalStateException("Agent model budget exceeded");
                if (stopping.contains(id.runnerJobId()) || stoppedWorkspaces.contains(stopKey(assignment))) throw new IllegalStateException("Agent cancelled");
                var answer = runtime.models().complete(assignment, line, Math.toIntExact(Math.min(runtime.tokensPerTurn(), runtime.modelTokens() - tokens)),
                        Duration.between(clock.instant(), assignment.job().deadline()));
                tokens = Math.addExact(tokens, RunnerJson.number(answer, "tokens"));
                if (tokens > runtime.modelTokens() || stopping.contains(id.runnerJobId()) || stoppedWorkspaces.contains(stopKey(assignment))) throw new IllegalStateException("Agent budget exhausted or cancelled");
                output.write(RunnerJson.write(answer)); output.write('\n'); output.flush();
            }
            throw new IllegalStateException("Agent model budget exhausted");
        } finally {
            watchdog.cancel(true); errors.cancel(true); active.remove(id.runnerJobId(), process);
            if (process.isAlive()) { stop(assignment); process.destroyForcibly(); }
        }
    }
    private static byte[] readLine(InputStream input, int limit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i <= limit; i++) {
            int value = input.read();
            if (value == '\n') return bytes.toByteArray();
            if (value < 0) throw new EOFException("Agent exited without a result");
            bytes.write(value);
        }
        throw new IOException("Agent protocol limit exceeded");
    }
    private CompletionStage<RunnerResult> reconcileGoverned(RunnerAssignment assignment) {
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, true);
            try {
                Path directory = workspace(assignment); requireSafe(directory); requireOwner(directory, assignment, false);
                try (var channel = FileChannel.open(directory.resolve("workspace.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    var original = RunnerCodec.assignment(read(directory.resolve(assignment.job().identity().runnerJobId() + ".started"), RunnerCodec.MAX_BYTES));
                    if (!original.job().identity().equals(assignment.job().identity())) throw new IllegalArgumentException("reconciliation identity mismatch");
                    Path result = directory.resolve(assignment.job().identity().runnerJobId() + ".result");
                    if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
                        boolean persistent = persistent(assignment);
                        if (persistent && !Set.of("close", "abort").contains(Objects.toString(assignment.lifecycleCommand(), ""))
                                && assignment.job().stopReason() == RunnerJob.StopReason.NONE) requireRunning(assignment);
                        else if (persistent || assignment.lifecycleCommand() == null) requireStopped(assignment);
                        return RunnerCodec.result(read(result, RunnerCodec.MAX_BYTES));
                    }
                    if (assignment.job().stopReason() != RunnerJob.StopReason.NONE) {
                        requireStopped(assignment);
                        return new RunnerResult(assignment.job().command().outcomes().stream().sorted().findFirst().orElseThrow(),
                                OpaquePayload.of(RunnerJson.write(Map.of("result", "Agent stopped; partial effects retained")), "application/json"),
                                List.of(), assignment.job().identity().runnerJobId(), observation(assignment, directory, true));
                    }
                    throw new IllegalStateException("runtime has no durable result; effect remains unknown");
                }
            } catch (Exception failure) { throw new CompletionException("Workspace recovery required", failure); }
        }, executor);
    }
    private void stop(RunnerAssignment assignment) throws Exception {
        if (assignment.workspace() != null) requireRuntimeIdentity(assignment);
        try { requireStopped(assignment); return; }
        catch (IllegalStateException running) { /* Stop is needed; absence remains an unknown effect. */ }
        command(configuration.driverCommandTimeout(), 4096, "kill", container(assignment)); requireStopped(assignment);
    }
    private void requireStopped(RunnerAssignment assignment) throws Exception {
        if (assignment.workspace() != null) requireRuntimeIdentity(assignment);
        String state = new String(command(configuration.driverCommandTimeout(), 256, "inspect", "--format={{.State.Running}}", container(assignment)),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!state.equals("false")) throw new IllegalStateException("runner process tree is not quiescent");
    }
    private void requireRuntimeIdentity(RunnerAssignment assignment) throws Exception {
        String found = new String(command(configuration.driverCommandTimeout(), 256, "inspect", "--format={{.Id}}", container(assignment)),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!found.equals(runtimeId(assignment))) throw new IllegalStateException("physical Workspace runtime identity changed");
    }
    private byte[] command(Duration timeout, long limit, String... arguments) throws Exception {
        var args = new ArrayList<String>(); args.add(docker.toString()); args.addAll(List.of(arguments));
        Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = executor.submit(() -> bounded(process.getInputStream(), limit));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("runner supervisor timed out");
            byte[] bytes = output.get(configuration.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("runner supervisor refused operation");
            return bytes;
        } finally { output.cancel(true); if (process.isAlive()) process.destroyForcibly(); }
    }
    private static byte[] bounded(InputStream input, long limit) throws IOException {
        if (limit < 0 || limit >= Integer.MAX_VALUE) throw new IllegalArgumentException("invalid stream quota");
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
        if (!closed.compareAndSet(false, true)) return;
        // Do not claim quiescence merely because the control process is stopping.
        active.values().forEach(Process::destroy); executor.shutdownNow();
        try { supervisorLock.release(); supervisorChannel.close(); } catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
}
