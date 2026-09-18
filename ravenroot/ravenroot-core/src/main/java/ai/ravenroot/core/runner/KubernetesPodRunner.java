package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.runner.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Native Pod/PVC driver. Kubernetes observations never authorize replay of an uncertain effect. */
public final class KubernetesPodRunner implements RunnerDriver {
    private final RunnerRegistration registration;
    private final KubernetesRunnerConfiguration configuration;
    private final RunnerWorkerConfiguration worker;
    private final KubernetesTransport api;
    private final Path state;
    private final Map<String, String> images;
    private final Clock clock;
    private final LocalContainerRunner.ArtifactPublisher artifacts;
    private final FileChannel managerChannel;
    private final java.nio.channels.FileLock managerLock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, ReentrantLock> gates = new ConcurrentHashMap<>();
    private final Map<UUID, Process> active = new ConcurrentHashMap<>();
    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> stopped = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean preflightCompleted = new AtomicBoolean();
    private final AtomicBoolean admissionBlocked = new AtomicBoolean();
    private final Semaphore slots;
    private final Object stopGate = new Object();
    private RunnerAgentRuntime runtime;
    private final RunnerTelemetry.Relay telemetry = new RunnerTelemetry.Relay();

    public KubernetesPodRunner withTelemetry(RunnerTelemetry sink) { telemetry.install(sink); return this; }

    public KubernetesPodRunner(RunnerRegistration registration, KubernetesRunnerConfiguration configuration,
            Path state, Map<String, String> images, Clock clock, LocalContainerRunner.ArtifactPublisher artifacts,
            RunnerWorkerConfiguration worker) throws IOException {
        this(registration, configuration, state, images, clock, artifacts, worker, null);
    }

    KubernetesPodRunner(RunnerRegistration registration, KubernetesRunnerConfiguration configuration,
            Path state, Map<String, String> images, Clock clock, LocalContainerRunner.ArtifactPublisher artifacts,
            RunnerWorkerConfiguration worker, KubernetesTransport transport) throws IOException {
        this.registration = Objects.requireNonNull(registration); this.configuration = Objects.requireNonNull(configuration);
        this.worker = Objects.requireNonNull(worker); this.clock = Objects.requireNonNull(clock);
        this.artifacts = Objects.requireNonNull(artifacts); this.images = Map.copyOf(images);
        this.slots = new Semaphore(worker.maxConcurrentJobs());
        if (!registration.trustProfile().equals("kubernetes-pod-v1") || images.isEmpty()
                || images.values().stream().anyMatch(image -> !image.matches("[a-zA-Z0-9][a-zA-Z0-9./:_-]{0,253}@sha256:[0-9a-f]{64}"))
                || registration.capabilities().capabilities().stream().anyMatch(capability -> Set.of(
                        RunnerPolicy.Capability.NETWORK_EGRESS, RunnerPolicy.Capability.SECRET_ACCESS,
                        RunnerPolicy.Capability.ADDITIONAL_MOUNTS, RunnerPolicy.Capability.TOOL_CALL).contains(capability)))
            throw new IllegalArgumentException("Kubernetes driver requires digest-pinned runtimes and supported confinement authority");
        this.state = state.toAbsolutePath().normalize(); Files.createDirectories(this.state);
        if (!this.state.equals(this.state.toRealPath())) throw new IllegalArgumentException("real operator-owned manager state required");
        api = transport == null ? new KubernetesApi(configuration.kubectl(), configuration.kubeconfig(), configuration.namespace(),
                worker.driverCommandTimeout(), worker.maxSupervisorOutputBytes()) : transport;
        managerChannel = FileChannel.open(this.state.resolve("kubernetes-manager.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            managerLock = managerChannel.tryLock();
            if (managerLock == null) throw new IllegalStateException("another manager owns this durable state");
        } catch (IOException | RuntimeException failure) { managerChannel.close(); throw failure; }
    }

    public KubernetesPodRunner withAgentRuntime(RunnerAgentRuntime value) {
        if (runtime != null) throw new IllegalStateException("Agent runtime is already configured");
        runtime = Objects.requireNonNull(value); return this;
    }
    @Override public RunnerRegistration registration() { return registration; }
    @Override public Set<String> runtimeProfiles() { return images.keySet(); }
    /** Positive startup probe, before worker registration or any provider/model turn. */
    public void preflight() throws Exception {
        for (String runtimeProfile : images.keySet()) {
            Path pending = state.resolve("kubernetes-preflight-" + encoded(runtimeProfile));
            RunnerAssignment previous = Files.exists(pending) ? RunnerCodec.assignment(read(pending)) : null;
            if (previous != null) {
                if (!previous.job().runner().equals(registration) || !previous.workspace().nodeId().equals("kubernetes-preflight"))
                    throw new IllegalStateException("preflight recovery ownership differs");
                stopAll(previous);
                if (!Files.exists(receipt(previous, ".result")))
                    throw new IllegalStateException("prior preflight evidence is unknown; operator recovery is required");
            }
            var capacity = new WorkspaceProfile.Capacity(1, 1, 1, registration.capabilities().limits().workspaceBytes(), 1, 2, WorkspaceProfile.Admission.REJECT);
            var profile = new WorkspaceProfile(new AgentDefinition.Reference(registration.tenantId(), "kubernetes-preflight", 1),
                    WorkspaceProfile.Scope.NAMED, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, "preflight", runtimeProfile,
                    registration.capabilities(), capacity, Duration.ZERO, WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED,
                    Set.of("preflight"), RunnerFleetLimits.from(capacity), 500, WorkspaceProfile.Driver.KUBERNETES);
            var resource = new WorkspaceResource("kubernetes-preflight", previous == null ? UUID.randomUUID() : previous.workspaceId(), profile, registration.runnerId(),
                    WorkspaceResource.State.OPENING, null, null, false, clock.instant(), previous == null ? 1 : Math.incrementExact(previous.workspace().generation()));
            var definition = new AgentDefinition(new AgentDefinition.Reference(registration.tenantId(), "kubernetes-preflight", 1),
                    "Operator-only startup enforcement probe", runtimeProfile, "none", Map.of("open",
                    new AgentCommand("open", false, registration.capabilities(), Set.of("ready"))), Set.of(), Set.of(),
                    registration.capabilities(), Duration.ZERO, "workspace-state");
            var identity = new RunnerJobIdentity(new ai.ravenroot.api.persistence.ExecutionKey(registration.tenantId(), UUID.randomUUID()),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var job = RunnerJob.accept(identity, definition, "open", registration.capabilities(), registration,
                    OpaquePayload.of("{}".getBytes(StandardCharsets.UTF_8), "application/json"), clock.instant(),
                    clock.instant().plus(registration.capabilities().limits().wallTime()))
                    .claim(registration.runnerId(), clock.instant(), registration.capabilities().limits().wallTime());
            var assignment = new RunnerAssignment(1, resource.workspaceId(), job, resource, "open");
            atomic(pending, RunnerCodec.assignment(assignment));
            execute(assignment).toCompletableFuture().get(configuration.creationTimeout().plus(worker.driverCommandTimeout()).toMillis(), TimeUnit.MILLISECONDS);
            stopAll(assignment);
            // One explicitly retained probe PVC per runtime profile, included in namespace quota.
            // Reuse is generation-fenced and avoids consuming a new static volume on every restart.
        }
        admissionBlocked.set(false); preflightCompleted.set(true);
    }
    @Override public void verifyAvailability() {
        if (!preflightCompleted.get() || admissionBlocked.get()) throw new IllegalStateException("Kubernetes pool requires positive preflight");
        try {
            for (String runtimeProfile : images.keySet()) {
                var probe = RunnerCodec.assignment(read(state.resolve("kubernetes-preflight-" + encoded(runtimeProfile))));
                var pvc = api.get("pvc", claimName(probe)); requireOwned(pvc, probe);
                requireUid(pvc, RunnerJson.text(RunnerJson.read(read(directory(probe).resolve(claimName(probe) + ".volume"))), "uid"));
                if (!"Bound".equals(RunnerJson.map(pvc.getOrDefault("status", Map.of())).get("phase")))
                    throw new IllegalStateException("preflight Workspace volume is no longer Bound");
            }
        } catch (Exception unavailable) {
            admissionBlocked.set(true);
            throw new CompletionException("Kubernetes pool observation is unavailable; positive preflight is required", unavailable);
        }
    }
    @Override public RunnerAssignment observe(RunnerAssignment assignment) {
        if (!Files.exists(receipt(assignment, ".intent")) || !Files.exists(directory(assignment).resolve(podName(assignment) + ".pod"))) {
            var resource = assignment.workspace();
            // Do not attribute the previous invocation's Pod/usage to a new per-invocation job.
            // This is a transient report projection, not a mutation of persisted Workspace evidence.
            var unobserved = new WorkspaceResource(resource.nodeId(), resource.workspaceId(), resource.profile(), resource.runnerId(), resource.state(),
                    resource.runtimeId(), resource.checkpoint(), resource.stopRequested(), resource.updatedAt(), resource.generation(), null);
            return new RunnerAssignment(assignment.protocolVersion(), assignment.workspaceId(), assignment.job(), unobserved, assignment.lifecycleCommand());
        }
        try {
            var physical = observation(assignment);
            var resource = assignment.workspace().observed("inspect", physical.runtimeId(), physical.checkpoint(),
                    clock.instant(), physical.kubernetes());
            return new RunnerAssignment(assignment.protocolVersion(), assignment.workspaceId(), assignment.job(), resource, assignment.lifecycleCommand());
        } catch (Exception failure) { throw new CompletionException("Kubernetes observation is unknown", failure); }
    }

    private static boolean persistent(RunnerAssignment assignment) {
        return assignment.workspace().profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE
                && assignment.workspace().profile().workspaceScope() != WorkspaceProfile.Scope.EPHEMERAL;
    }
    private static String scope(RunnerAssignment assignment) {
        return assignment.workspace().workspaceId() + "-" + assignment.workspace().generation();
    }
    private Path directory(RunnerAssignment assignment) { return state.resolve(scope(assignment)); }
    private Path receipt(RunnerAssignment assignment, String extension) {
        return directory(assignment).resolve(assignment.job().identity().runnerJobId() + extension);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String encoded(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String name(String kind, String identity) { return "rr-" + kind + "-" + encoded(identity).substring(0, 48); }
    private static String podName(RunnerAssignment assignment) {
        return name("pod", persistent(assignment) ? scope(assignment) : assignment.job().identity().runnerJobId().toString());
    }
    private static String claimName(RunnerAssignment assignment) { return name("volume", assignment.workspaceId().toString()); }
    private static Map<String, Object> metadata(Map<String, Object> object) { return RunnerJson.map(object.get("metadata")); }
    private static String uid(Map<String, Object> object) { return RunnerJson.text(metadata(object), "uid"); }
    private static String revision(Map<String, Object> object) { return RunnerJson.text(metadata(object), "resourceVersion"); }

    private Map<String, String> ownership(RunnerAssignment assignment) {
        var id = assignment.job().identity();
        return Map.of("ravenroot.ai/tenant", encoded(id.execution().tenantId()),
                "ravenroot.ai/process", id.execution().processInstanceId().toString(),
                "ravenroot.ai/workspace", assignment.workspaceId().toString(),
                "ravenroot.ai/node", encoded(assignment.workspace().nodeId()),
                "ravenroot.ai/generation", Long.toString(assignment.workspace().generation()),
                "ravenroot.ai/runner", registration.runnerId(), "ravenroot.ai/cluster", configuration.cluster(),
                "ravenroot.ai/protocol", "1");
    }
    private Map<String, String> annotations(RunnerAssignment assignment) {
        var result = new LinkedHashMap<>(ownership(assignment)); var id = assignment.job().identity();
        result.put("ravenroot.ai/job", id.runnerJobId().toString()); result.put("ravenroot.ai/fence", Long.toString(assignment.job().fence()));
        result.put("ravenroot.ai/traversal", id.traversalId().toString()); result.put("ravenroot.ai/invocation", id.invocationId().toString());
        result.put("ravenroot.ai/attempt", id.attemptId().toString()); result.put("ravenroot.ai/definition", encoded(assignment.job().definition().reference().name()));
        result.put("ravenroot.ai/definition-version", Long.toString(assignment.job().definition().reference().version()));
        result.put("ravenroot.ai/profile", assignment.workspace().profile().runtimeProfile());
        result.put("ravenroot.ai/pool", assignment.workspace().profile().runnerPool()); return result;
    }
    private void requireOwned(Map<String, Object> object, RunnerAssignment assignment) {
        if (object == null) throw new IllegalStateException("owned Kubernetes resource is absent");
        var observed = RunnerJson.map(metadata(object).get("annotations"));
        if (!configuration.namespace().equals(metadata(object).get("namespace"))
                || !ownership(assignment).entrySet().stream().allMatch(entry -> entry.getValue().equals(observed.get(entry.getKey()))))
            throw new IllegalStateException("complete Kubernetes ownership does not match");
    }
    private void validate(RunnerAssignment assignment, boolean reportOnly) {
        var job = assignment.job();
        if (assignment.workspace() == null || assignment.workspace().profile().driver() != WorkspaceProfile.Driver.KUBERNETES
                || !job.runner().equals(registration) || !images.containsKey(job.definition().runtimeProfile())
                || !job.authority().equals(RunnerPolicy.effective(job.authority(), job.definition().policy(), job.command(), registration.capabilities())))
            throw new IllegalArgumentException("assignment is not approved for this Kubernetes driver");
        if ((!reportOnly && job.state() != RunnerJob.State.CLAIMED) || job.fence() < 1 || job.leaseUntil() == null
                || !clock.instant().isBefore(job.leaseUntil())) throw new IllegalStateException("live fenced claim required");
        if (!reportOnly && persistent(assignment) && assignment.lifecycleCommand() == null) {
            var effective = job.authority().limits(); var physical = assignment.workspace().profile().policy().limits();
            if (effective.memoryBytes() < physical.memoryBytes() || effective.workspaceBytes() < physical.workspaceBytes()
                    || job.authority().capabilities().contains(RunnerPolicy.Capability.PROCESS_EXECUTE) && effective.processes() < physical.processes())
                throw new IllegalArgumentException("retained Pod cannot widen a lower invocation resource ceiling");
        }
    }
    private void requireAdmitted(RunnerAssignment assignment) {
        if (closed.get() || admissionBlocked.get() || cancelled.contains(assignment.job().identity().runnerJobId()) || stopped.contains(scope(assignment))
                || Files.exists(directory(assignment).resolve("stopped"), LinkOption.NOFOLLOW_LINKS)
                || !clock.instant().isBefore(assignment.job().deadline())) throw new IllegalStateException("Kubernetes admission is stopped");
        try {
            if (currentFence(assignment) > assignment.job().fence())
                throw new IllegalStateException("a newer report-only fence revoked Agent execution");
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    /** Pure construction: no graph value can introduce Pod spec fields, mounts or credentials. */
    public Map<String, Object> pod(RunnerAssignment assignment) {
        var profile = assignment.workspace().profile();
        var limits = persistent(assignment) || assignment.lifecycleCommand() != null ? profile.policy().limits() : assignment.job().authority().limits();
        var container = new LinkedHashMap<String, Object>();
        container.put("name", "agent"); container.put("image", images.get(profile.runtimeProfile())); container.put("imagePullPolicy", "IfNotPresent");
        container.put("command", List.of("python3", "-c", "import signal; signal.signal(signal.SIGCHLD,signal.SIG_IGN); signal.pause()"));
        container.put("workingDir", "/workspace");
        container.put("env", List.of(Map.of("name", "RAVENROOT_POD_UID", "valueFrom", Map.of("fieldRef", Map.of("fieldPath", "metadata.uid"))),
                Map.of("name", "RAVENROOT_NETWORK_CONTROL_HOST", "value", configuration.networkControlAddress()),
                Map.of("name", "RAVENROOT_WORKSPACE_LIMIT_BYTES", "value", Long.toString(limits.workspaceBytes())),
                Map.of("name", "RAVENROOT_MEMORY_LIMIT_BYTES", "value", Long.toString(limits.memoryBytes())),
                Map.of("name", "RAVENROOT_CPU_MILLICORES", "value", Integer.toString(profile.cpuMillicores())),
                Map.of("name", "RAVENROOT_PROCESS_LIMIT", "value", Integer.toString(limits.processes()))));
        container.put("securityContext", Map.of("runAsNonRoot", true, "runAsUser", 65532, "runAsGroup", 65532,
                "allowPrivilegeEscalation", false, "readOnlyRootFilesystem", true, "capabilities", Map.of("drop", List.of("ALL")),
                "seccompProfile", Map.of("type", "RuntimeDefault")));
        var resources = Map.of("cpu", profile.cpuMillicores() + "m", "memory", Long.toString(limits.memoryBytes()));
        container.put("resources", Map.of("requests", resources, "limits", resources));
        container.put("volumeMounts", List.of(Map.of("name", "workspace", "mountPath", "/workspace")));
        var spec = new LinkedHashMap<String, Object>();
        spec.put("restartPolicy", "Never"); spec.put("automountServiceAccountToken", false);
        spec.put("serviceAccountName", configuration.serviceAccount()); spec.put("enableServiceLinks", false);
        spec.put("terminationGracePeriodSeconds", configuration.terminationGraceSeconds());
        spec.put("securityContext", Map.of("runAsNonRoot", true, "runAsUser", 65532, "runAsGroup", 65532,
                "fsGroup", 65532, "fsGroupChangePolicy", "OnRootMismatch", "seccompProfile", Map.of("type", "RuntimeDefault")));
        spec.put("containers", List.of(container));
        spec.put("volumes", List.of(Map.of("name", "workspace", "persistentVolumeClaim", Map.of("claimName", claimName(assignment)))));
        if (!configuration.runtimeClass().isEmpty()) spec.put("runtimeClassName", configuration.runtimeClass());
        if (!configuration.nodeSelector().isEmpty()) spec.put("nodeSelector", configuration.nodeSelector());
        configuration.placement().apply(spec);
        return Map.of("apiVersion", "v1", "kind", "Pod", "metadata", Map.of("name", podName(assignment),
                "namespace", configuration.namespace(), "labels", Map.of("app.kubernetes.io/component", "governed-agent", "ravenroot.ai/attesting", "true"),
                "annotations", annotations(assignment)), "spec", spec);
    }

    private Map<String, Object> claim(RunnerAssignment assignment) {
        return Map.of("apiVersion", "v1", "kind", "PersistentVolumeClaim", "metadata", Map.of("name", claimName(assignment),
                "namespace", configuration.namespace(), "annotations", ownership(assignment)), "spec", Map.of(
                "accessModes", List.of("ReadWriteOnce"), "volumeMode", "Filesystem", "storageClassName", configuration.storageClass(),
                "resources", Map.of("requests", Map.of("storage", Long.toString(assignment.workspace().profile().policy().limits().workspaceBytes())))));
    }

    @Override public CompletionStage<RunnerResult> execute(RunnerAssignment assignment) {
        if (!slots.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("Kubernetes manager capacity exhausted"));
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, false); requireAdmitted(assignment);
            var gate = gates.computeIfAbsent(assignment.workspace().workspaceId(), ignored -> new ReentrantLock(true)); gate.lock();
            try {
                requireAdmitted(assignment);
                Files.createDirectories(directory(assignment)); safe(directory(assignment));
                // CREATE_NEW plus fsync is the durable intent-before-effect barrier. A restart,
                // uncertain Kubernetes response or failed model turn can only reconcile this job.
                create(receipt(assignment, ".intent"), RunnerCodec.assignment(assignment));
                recordFence(assignment);
                String operation = assignment.lifecycleCommand();
                if ("checkpoint".equals(operation)) throw new UnsupportedOperationException("Kubernetes checkpoint is unsupported by the fixed-filesystem profile");
                if (operation == null || operation.equals("open") && assignment.workspace().profile().workspaceScope() != WorkspaceProfile.Scope.EPHEMERAL)
                    materialize(assignment);
                else if (operation.equals("open")) { /* Fresh storage is created only for each actual invocation. */ }
                else if (Set.of("close", "abort").contains(operation)) stopAll(assignment);
                else if (!operation.equals("inspect")) throw new IllegalArgumentException("unsupported Workspace operation");
                RunnerResult result;
                if (operation == null) result = runAgent(assignment);
                else {
                    String outcome = Map.of("open", "ready", "inspect", "inspected", "close", "closed", "abort", "aborted").get(operation);
                    result = new RunnerResult(outcome, OpaquePayload.of(RunnerJson.write(Map.of("result", "Workspace " + outcome)), "application/json"),
                            List.of(), assignment.job().identity().runnerJobId(), observation(assignment));
                }
                if (!persistent(assignment) && (operation == null || "open".equals(operation))) terminate(assignment);
                result = new RunnerResult(result.outcome(), result.payload(), result.artifacts(), result.quiescenceId(), observation(assignment));
                atomic(receipt(assignment, ".result"), RunnerCodec.result(result)); return result;
            } catch (Exception failure) {
                telemetry.increment(RunnerTelemetry.Counter.KUBERNETES_UNKNOWN);
                if (failure instanceof KubernetesApi.OperationFailure apiFailure)
                    telemetry.increment(RunnerTelemetry.Counter.valueOf("KUBERNETES_API_" + apiFailure.kind.name()));
                if (preflightCompleted.get()) admissionBlocked.set(true);
                throw new CompletionException("Kubernetes effect is unknown; report-only reconciliation required", failure);
            }
            finally { gate.unlock(); }
        }, executor).whenComplete((ignored, failure) -> slots.release());
    }

    private void materialize(RunnerAssignment assignment) throws Exception {
        long started = System.nanoTime();
        requireAdmitted(assignment);
        Path imagePin = directory(assignment).resolve("runtime-image");
        byte[] image = images.get(assignment.workspace().profile().runtimeProfile()).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(imagePin)) {
            if (!Arrays.equals(read(imagePin), image)) throw new IllegalStateException("Workspace runtime image is pinned");
        } else create(imagePin, image);
        Map<String, Object> pvc = api.get("pvc", claimName(assignment));
        Path volume = directory(assignment).resolve(claimName(assignment) + ".volume");
        if (pvc == null) {
            if (Files.exists(volume) || assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.NAMED && assignment.workspace().generation() > 1)
                throw new IllegalStateException("owned Workspace volume was lost; fresh storage cannot replace it");
            pvc = api.create(claim(assignment));
        }
        else if (assignment.workspace().profile().workspaceScope() == WorkspaceProfile.Scope.NAMED
                && "open".equals(assignment.lifecycleCommand())) pvc = acquireNamed(assignment, pvc);
        requireOwned(pvc, assignment);
        if (Files.exists(volume)) requireUid(pvc, RunnerJson.text(RunnerJson.read(read(volume)), "uid"));
        else atomic(volume, RunnerJson.write(Map.of("uid", uid(pvc))));
        Path saved = directory(assignment).resolve(podName(assignment) + ".pod");
        Map<String, Object> pod;
        if (Files.exists(saved)) {
            pod = api.get("pod", podName(assignment)); requireOwned(pod, assignment);
            requireUid(pod, RunnerJson.text(RunnerJson.read(read(saved)), "uid"));
            requireRunning(pod);
            var prior = RunnerJson.map(metadata(pod).get("annotations"));
            if (!assignment.job().identity().runnerJobId().toString().equals(prior.get("ravenroot.ai/job"))) {
                UUID precedingJob = UUID.fromString(RunnerJson.text(prior, "ravenroot.ai/job"));
                if (!Files.exists(directory(assignment).resolve(precedingJob + ".result")))
                    throw new IllegalStateException("preceding Pod invocation has no durable terminal result");
                pod = api.patch("pod", podName(assignment), uid(pod), revision(pod), annotations(assignment));
            }
            requireInvocation(pod, assignment);
        } else {
            // The assignment intent above already exists. Never repeat CREATE after uncertainty.
            pod = api.create(pod(assignment)); requireOwned(pod, assignment);
            telemetry.increment(RunnerTelemetry.Counter.KUBERNETES_CREATE);
            atomic(saved, RunnerJson.write(Map.of("uid", uid(pod))));
        }
        long deadline = System.nanoTime() + configuration.creationTimeout().toNanos();
        while (!"Running".equals(RunnerJson.map(pod.getOrDefault("status", Map.of())).get("phase"))) {
            requireAdmitted(assignment);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Kubernetes scheduling deadline exceeded; ownership retained");
            Thread.sleep(100);
            var next = api.get("pod", podName(assignment)); requireOwned(next, assignment); requireUid(next, uid(pod)); pod = next;
        }
        pvc = api.get("pvc", claimName(assignment)); requireOwned(pvc, assignment);
        requireUid(pvc, RunnerJson.text(RunnerJson.read(read(volume)), "uid"));
        if (!"Bound".equals(RunnerJson.map(pvc.getOrDefault("status", Map.of())).get("phase")))
            throw new IllegalStateException("Workspace PVC is not bound");
        Path network = directory(assignment).resolve(podName(assignment) + ".network");
        if (!Files.exists(network)) {
            if (!Boolean.TRUE.equals(api.networkControl(podName(assignment)).get("networkReachable")))
                throw new IllegalStateException("positive network control failed before isolation");
            pod = api.get("pod", podName(assignment)); requireInvocation(pod, assignment);
            requireUid(pod, RunnerJson.text(RunnerJson.read(read(saved)), "uid"));
            api.isolate(podName(assignment), uid(pod), revision(pod));
            atomic(network, RunnerJson.write(Map.of("uid", uid(pod), "positiveControl", true)));
        } else if (!uid(pod).equals(RunnerJson.text(RunnerJson.read(read(network)), "uid"))) {
            throw new IllegalStateException("network attestation Pod identity changed");
        }
        var proof = api.attest(podName(assignment));
        var limits = persistent(assignment) || assignment.lifecycleCommand() != null
                ? assignment.workspace().profile().policy().limits() : assignment.job().authority().limits();
        if (!proof.keySet().equals(Set.of("requestedBytes", "enforcedBytes", "withinQuotaWrite", "beyondQuotaRejected", "backend",
                "processLimit", "processBoundaryRejected", "memoryLimitBytes", "cpuMillicores", "withinMemoryAllocation",
                "cpuThrottlingObserved", "protocolVersion", "nonRoot", "capabilitiesDropped", "noNewPrivileges", "readOnlyRoot",
                "landlockAbi", "serviceAccountAbsent", "networkBlocked"))
                || !Set.of("withinQuotaWrite", "beyondQuotaRejected", "networkBlocked", "nonRoot", "capabilitiesDropped",
                    "noNewPrivileges", "readOnlyRoot", "serviceAccountAbsent", "withinMemoryAllocation", "processBoundaryRejected")
                    .stream().allMatch(key -> Boolean.TRUE.equals(proof.get(key)))
                || !"fixed-filesystem-v1".equals(proof.get("backend")) || RunnerJson.number(proof, "protocolVersion") != 1
                || RunnerJson.number(proof, "landlockAbi") < 3 || RunnerJson.number(proof, "enforcedBytes") < 1
                || RunnerJson.number(proof, "enforcedBytes") > limits.workspaceBytes()
                || RunnerJson.number(proof, "memoryLimitBytes") < 1 || RunnerJson.number(proof, "cpuMillicores") < 1
                || RunnerJson.number(proof, "memoryLimitBytes") > limits.memoryBytes()
                || RunnerJson.number(proof, "cpuMillicores") > assignment.workspace().profile().cpuMillicores()
                || RunnerJson.number(proof, "cpuMillicores") < 1000 && !Boolean.TRUE.equals(proof.get("cpuThrottlingObserved"))
                || RunnerJson.number(proof, "processLimit") < 1 || RunnerJson.number(proof, "processLimit") > limits.processes()
                || RunnerJson.number(proof, "requestedBytes") != limits.workspaceBytes())
            throw new IllegalStateException("Kubernetes driver enforcement attestation is insufficient");
        atomic(directory(assignment).resolve(podName(assignment) + ".proof"), RunnerJson.write(proof));
        atomic(directory(assignment).resolve(claimName(assignment) + ".bound"), RunnerJson.write(Map.of(
                "uid", uid(pvc), "volume", RunnerJson.text(RunnerJson.map(pvc.get("spec")), "volumeName"))));
        atomic(directory(assignment).resolve("latest-materialized"), RunnerCodec.assignment(assignment));
        telemetry.kubernetesLatency(RunnerTelemetry.KubernetesOperation.CREATE,
                Math.min(3_600_000, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    private Map<String, Object> acquireNamed(RunnerAssignment assignment, Map<String, Object> pvc) throws Exception {
        var previous = RunnerJson.map(metadata(pvc).get("annotations"));
        long generation = Long.parseLong(RunnerJson.text(previous, "ravenroot.ai/generation"));
        if (generation == assignment.workspace().generation()) return pvc;
        if (generation < 1 || generation >= assignment.workspace().generation()
                || !encoded(registration.tenantId()).equals(previous.get("ravenroot.ai/tenant"))
                || !assignment.workspaceId().toString().equals(previous.get("ravenroot.ai/workspace")))
            throw new IllegalStateException("named Workspace predecessor identity mismatch");
        Path predecessor = state.resolve(assignment.workspaceId() + "-" + generation); safe(predecessor);
        int observed = 0;
        try (var files = Files.newDirectoryStream(predecessor, "*.intent")) {
            for (Path file : files) {
                var original = RunnerCodec.assignment(read(file)); requireOwned(pvc, original);
                if (!Files.exists(receipt(original, ".result")) || api.get("pod", podName(original)) != null)
                    throw new IllegalStateException("named Workspace predecessor is not quiescent with retained results");
                observed++;
            }
        }
        if (observed == 0) throw new IllegalStateException("named Workspace predecessor evidence is missing");
        return api.patch("pvc", claimName(assignment), uid(pvc), revision(pvc), ownership(assignment));
    }

    private static void requireUid(Map<String, Object> object, String expected) {
        if (object == null || !expected.equals(uid(object))) throw new IllegalStateException("Kubernetes physical UID changed");
    }
    private static void requireRunning(Map<String, Object> pod) {
        if (metadata(pod).containsKey("deletionTimestamp") || !"Running".equals(RunnerJson.map(pod.getOrDefault("status", Map.of())).get("phase")))
            throw new IllegalStateException("owned Kubernetes Pod is not running");
    }
    private void requireInvocation(Map<String, Object> pod, RunnerAssignment assignment) {
        requireOwned(pod, assignment);
        var observed = RunnerJson.map(metadata(pod).get("annotations"));
        if (!annotations(assignment).entrySet().stream().allMatch(entry -> entry.getValue().equals(observed.get(entry.getKey()))))
            throw new IllegalStateException("Kubernetes invocation ownership fence changed");
        var actual = RunnerJson.map(pod.get("spec"));
        var expected = RunnerJson.read(RunnerJson.write(pod(assignment).get("spec")));
        // API defaulting is permitted, authority-affecting fields are not.
        for (String field : List.of("serviceAccountName", "automountServiceAccountToken", "restartPolicy", "enableServiceLinks"))
            if (!expected.get(field).equals(actual.get(field))) throw new IllegalStateException("Kubernetes Pod authority changed");
        var containers = (List<?>) actual.get("containers");
        if (containers.size() != 1 || actual.containsKey("initContainers") || actual.containsKey("ephemeralContainers"))
            throw new IllegalStateException("Kubernetes Pod container authority changed");
        var container = RunnerJson.map(containers.getFirst());
        var wanted = RunnerJson.map(((List<?>) expected.get("containers")).getFirst());
        for (String field : List.of("image", "command", "workingDir", "securityContext"))
            if (!wanted.get(field).equals(container.get(field))) throw new IllegalStateException("Kubernetes Agent authority changed");
    }
    private RunnerResult.WorkspaceObservation observation(RunnerAssignment assignment) throws Exception {
        Path saved = directory(assignment).resolve(podName(assignment) + ".pod");
        if (!Files.exists(saved)) {
            // Lifecycle commands refer to the latest durable assignment in this Workspace.
            var previous = latestMaterialized(assignment);
            if (previous == null) return new RunnerResult.WorkspaceObservation(assignment.workspaceId(), null, null);
            var observed = observation(previous);
            return new RunnerResult.WorkspaceObservation(assignment.workspaceId(), observed.runtimeId(), observed.checkpoint(), observed.kubernetes());
        }
        String expected = RunnerJson.text(RunnerJson.read(read(saved)), "uid");
        var pod = api.get("pod", podName(assignment));
        KubernetesWorkload.Phase phase = KubernetesWorkload.Phase.ABSENT;
        if (pod != null) {
            requireOwned(pod, assignment); requireUid(pod, expected);
            phase = metadata(pod).containsKey("deletionTimestamp") ? KubernetesWorkload.Phase.TERMINATING : switch (
                    Objects.toString(RunnerJson.map(pod.getOrDefault("status", Map.of())).get("phase"), "Unknown")) {
                case "Running" -> KubernetesWorkload.Phase.RUNNING; case "Pending" -> KubernetesWorkload.Phase.PENDING;
                case "Succeeded" -> KubernetesWorkload.Phase.SUCCEEDED; case "Failed" -> KubernetesWorkload.Phase.FAILED;
                default -> KubernetesWorkload.Phase.UNKNOWN;
            };
        }
        var pvc = api.get("pvc", claimName(assignment)); requireOwned(pvc, assignment);
        boolean volumeBound = "Bound".equals(RunnerJson.map(pvc.getOrDefault("status", Map.of())).get("phase"));
        if (pod != null && !volumeBound && Files.exists(directory(assignment).resolve(claimName(assignment) + ".bound")))
            throw new IllegalStateException("Workspace volume binding is unknown or lost");
        String claimUid = RunnerJson.text(RunnerJson.read(read(directory(assignment).resolve(claimName(assignment) + ".volume"))), "uid");
        requireUid(pvc, claimUid);
        Path attestation = directory(assignment).resolve(podName(assignment) + ".proof");
        byte[] bytes = Files.exists(attestation) ? read(attestation) : null;
        var proof = bytes == null ? null : RunnerJson.read(bytes);
        var detail = volumeBound ? details(pod) : new Details(KubernetesWorkload.Condition.UNHEALTHY, KubernetesWorkload.Reason.VOLUME, null);
        Path usageFile = receipt(assignment, ".usage");
        var usage = Files.exists(usageFile) ? RunnerJson.read(read(usageFile)) : Map.<String, Object>of("turns", 0L, "calls", 0L, "tokens", 0L);
        return new RunnerResult.WorkspaceObservation(assignment.workspaceId(), expected, null, new KubernetesWorkload(1,
                configuration.cluster(), configuration.namespace(), podName(assignment), UUID.fromString(expected), claimName(assignment),
                UUID.fromString(claimUid), (String) RunnerJson.map(pvc.get("spec")).get("volumeName"), assignment.workspace().generation(),
                phase, assignment.workspace().profile().policy().limits().workspaceBytes(), proof == null ? 0 : RunnerJson.number(proof, "enforcedBytes"),
                bytes == null ? null : "sha256:" + digest(bytes), detail.condition(), detail.reason(), detail.exitCode(),
                Math.toIntExact(RunnerJson.number(usage, "turns")), Math.toIntExact(RunnerJson.number(usage, "calls")), RunnerJson.number(usage, "tokens")));
    }

    private record Details(KubernetesWorkload.Condition condition, KubernetesWorkload.Reason reason, Integer exitCode) { }
    private static Details details(Map<String, Object> pod) {
        if (pod == null) return new Details(KubernetesWorkload.Condition.UNKNOWN, KubernetesWorkload.Reason.NONE, null);
        var status = RunnerJson.map(pod.getOrDefault("status", Map.of()));
        var condition = KubernetesWorkload.Condition.UNHEALTHY;
        var reason = KubernetesWorkload.Reason.NONE;
        if (metadata(pod).containsKey("deletionTimestamp")) condition = KubernetesWorkload.Condition.TERMINATING;
        else for (var raw : (List<?>) status.getOrDefault("conditions", List.of())) {
            var value = RunnerJson.map(raw);
            if ("Ready".equals(value.get("type")) && "True".equals(value.get("status"))) condition = KubernetesWorkload.Condition.READY;
            if ("PodScheduled".equals(value.get("type")) && "False".equals(value.get("status"))) {
                condition = KubernetesWorkload.Condition.UNSCHEDULED; reason = KubernetesWorkload.Reason.PLACEMENT;
            }
        }
        Integer exit = null;
        for (var raw : (List<?>) status.getOrDefault("containerStatuses", List.of())) {
            var container = RunnerJson.map(raw); if (!"agent".equals(container.get("name"))) continue;
            var state = RunnerJson.map(container.getOrDefault("state", Map.of()));
            var value = RunnerJson.map(state.getOrDefault("terminated", state.getOrDefault("waiting", Map.of())));
            if (value.containsKey("exitCode")) exit = Math.toIntExact(RunnerJson.number(value, "exitCode"));
            reason = boundedReason(Objects.toString(value.get("reason"), ""), reason);
        }
        reason = boundedReason(Objects.toString(status.get("reason"), ""), reason);
        return new Details(condition, reason, exit);
    }
    private static KubernetesWorkload.Reason boundedReason(String value, KubernetesWorkload.Reason fallback) {
        return switch (value) {
            case "" -> fallback;
            case "OOMKilled" -> KubernetesWorkload.Reason.OOM_KILLED;
            case "ErrImagePull", "ImagePullBackOff", "InvalidImageName" -> KubernetesWorkload.Reason.IMAGE_PULL;
            case "Evicted" -> KubernetesWorkload.Reason.EVICTED;
            case "NodeLost", "NodeNotReady" -> KubernetesWorkload.Reason.NODE_LOST;
            case "Completed" -> KubernetesWorkload.Reason.COMPLETED;
            case "Error", "CrashLoopBackOff" -> KubernetesWorkload.Reason.ERROR;
            case "ContainerCreating" -> KubernetesWorkload.Reason.NONE;
            default -> KubernetesWorkload.Reason.UNKNOWN;
        };
    }

    private RunnerAssignment latestMaterialized(RunnerAssignment assignment) throws Exception {
        Path path = directory(assignment).resolve("latest-materialized");
        if (!Files.exists(path)) return null;
        var saved = RunnerCodec.assignment(read(path));
        if (!saved.job().identity().execution().equals(assignment.job().identity().execution()) || !scope(saved).equals(scope(assignment)))
            throw new IllegalStateException("latest Workspace observation identity mismatch");
        return saved;
    }
    private List<RunnerAssignment> intents(RunnerAssignment assignment) throws Exception {
        var result = new ArrayList<RunnerAssignment>();
        if (!Files.exists(directory(assignment))) return result;
        try (var entries = Files.newDirectoryStream(directory(assignment), "*.intent")) {
            for (Path file : entries) {
                var saved = RunnerCodec.assignment(read(file));
                if (!saved.job().identity().execution().equals(assignment.job().identity().execution())
                        || !scope(saved).equals(scope(assignment))) throw new IllegalStateException("Workspace intent ownership mismatch");
                result.add(saved);
            }
        }
        return result;
    }

    @Override public CompletionStage<Void> cancel(RunnerAssignment assignment) {
        synchronized (stopGate) {
            cancelled.add(assignment.job().identity().runnerJobId()); if (runtime != null) runtime.models().cancel(assignment);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                var original = RunnerCodec.assignment(read(receipt(assignment, ".intent")));
                if (!original.job().runner().equals(registration) || !original.job().identity().equals(assignment.job().identity())
                        || original.job().fence() > assignment.job().fence())
                    throw new IllegalStateException("cancellation fence does not own the effect");
                recordFence(assignment);
                // An expired lease forbids new effects, not stopping this exact UID/fence.
                terminate(original, assignment.job().fence());
            }
            catch (Exception failure) { throw new CompletionException("Kubernetes termination is unconfirmed", failure); }
        }, executor);
    }
    @Override public CompletionStage<Void> stopWorkspace(RunnerAssignment assignment) {
        if (assignment.workspace() == null || !assignment.workspace().stopRequested())
            return CompletableFuture.failedFuture(new IllegalArgumentException("sticky Workspace stop required"));
        if (!assignment.job().runner().equals(registration))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Workspace stop runner ownership mismatch"));
        synchronized (stopGate) {
            stopped.add(scope(assignment)); if (runtime != null) runtime.models().cancelWorkspace(assignment);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(directory(assignment)); atomic(directory(assignment).resolve("stopped"), RunnerCodec.assignment(assignment));
                stopAll(assignment);
            } catch (Exception failure) { throw new CompletionException("Workspace stop remains unknown", failure); }
        }, executor);
    }
    private void stopAll(RunnerAssignment assignment) throws Exception {
        for (var original : intents(assignment)) {
            var pod = api.get("pod", podName(original));
            if (pod == null) continue;
            requireOwned(pod, original);
            // A retained Pod has one current invocation owner. Old intents are not delete authority.
            if (original.job().identity().runnerJobId().toString().equals(
                    RunnerJson.map(metadata(pod).get("annotations")).get("ravenroot.ai/job"))) terminate(original);
        }
        for (var original : intents(assignment))
            if (api.get("pod", podName(original)) != null) throw new IllegalStateException("Workspace Pod quiescence is unconfirmed");
    }
    private void terminate(RunnerAssignment assignment) throws Exception {
        terminate(assignment, assignment.job().fence());
    }
    private void terminate(RunnerAssignment assignment, long authorityFence) throws Exception {
        long started = System.nanoTime();
        var pod = api.get("pod", podName(assignment));
        if (pod == null) return;
        requireInvocation(pod, assignment);
        var saved = directory(assignment).resolve(podName(assignment) + ".pod");
        if (!Files.exists(saved)) {
            // Recover an uncertain CREATE only from its already durable complete intent. This
            // records identity for observation/termination; it never replays Agent execution.
            var intent = RunnerCodec.assignment(read(receipt(assignment, ".intent")));
            if (!java.util.Arrays.equals(RunnerCodec.assignment(intent), RunnerCodec.assignment(assignment)))
                throw new IllegalStateException("Pod create intent differs");
            atomic(saved, RunnerJson.write(Map.of("uid", uid(pod))));
        }
        String expected = RunnerJson.text(RunnerJson.read(read(saved)), "uid"); requireUid(pod, expected);
        synchronized (stopGate) {
            if (currentFence(assignment) > authorityFence)
                throw new IllegalStateException("a newer fence revoked this Pod termination authority");
            api.delete("pod", podName(assignment), expected, revision(pod), configuration.terminationGraceSeconds());
        }
        telemetry.increment(RunnerTelemetry.Counter.KUBERNETES_TERMINATE);
        long deadline = System.nanoTime() + worker.cleanupTimeout().toNanos();
        while (true) {
            var remaining = api.get("pod", podName(assignment));
            if (remaining == null) {
                telemetry.kubernetesLatency(RunnerTelemetry.KubernetesOperation.TERMINATE,
                        Math.min(3_600_000, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))); return;
            }
            requireOwned(remaining, assignment); requireUid(remaining, expected);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Pod UID absence has not been proven");
            Thread.sleep(100);
        }
    }

    @Override public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) {
        return CompletableFuture.supplyAsync(() -> {
            validate(assignment, true);
            try {
                var original = RunnerCodec.assignment(read(receipt(assignment, ".intent")));
                if (!original.job().identity().equals(assignment.job().identity()) || original.job().fence() > assignment.job().fence())
                    throw new IllegalStateException("stale reconciliation fence");
                recordFence(assignment);
                var saved = receipt(assignment, ".result");
                if (!Files.exists(saved)) {
                    recoverIdentity(original);
                    if (assignment.job().stopReason() == RunnerJob.StopReason.NONE)
                        throw new IllegalStateException("result is unknown; execution must not be repeated");
                    terminate(original, assignment.job().fence());
                    var evidence = new ArrayList<RunnerArtifact>();
                    Path partialLog = receipt(original, ".stderr");
                    if (Files.exists(partialLog)) evidence.add(artifacts.publish(assignment, RunnerArtifact.Kind.STDERR, read(partialLog)));
                    var result = new RunnerResult(assignment.job().command().outcomes().stream().sorted().findFirst().orElseThrow(),
                            OpaquePayload.of(RunnerJson.write(Map.of("result", "Agent stopped; partial effects retained")), "application/json"),
                            evidence, assignment.job().identity().runnerJobId(), observation(original));
                    atomic(saved, RunnerCodec.result(result)); return result;
                }
                var result = RunnerCodec.result(read(saved));
                var physical = observation(original);
                if (persistent(original) && !Set.of("close", "abort").contains(Objects.toString(original.lifecycleCommand(), ""))
                        && assignment.job().stopReason() == RunnerJob.StopReason.NONE && physical.kubernetes().phase() != KubernetesWorkload.Phase.RUNNING)
                    throw new IllegalStateException("retained Workspace Pod was lost");
                return result;
            } catch (Exception failure) {
                telemetry.increment(RunnerTelemetry.Counter.KUBERNETES_UNKNOWN);
                throw new CompletionException("Kubernetes recovery remains report-only", failure);
            }
        }, executor);
    }

    private void recoverIdentity(RunnerAssignment original) throws Exception {
        var pvc = api.get("pvc", claimName(original));
        if (pvc != null) {
            requireOwned(pvc, original);
            Path volume = directory(original).resolve(claimName(original) + ".volume");
            if (Files.exists(volume)) requireUid(pvc, RunnerJson.text(RunnerJson.read(read(volume)), "uid"));
            else atomic(volume, RunnerJson.write(Map.of("uid", uid(pvc))));
        }
        var pod = api.get("pod", podName(original));
        if (pod != null) {
            requireInvocation(pod, original);
            Path saved = directory(original).resolve(podName(original) + ".pod");
            if (Files.exists(saved)) requireUid(pod, RunnerJson.text(RunnerJson.read(read(saved)), "uid"));
            else atomic(saved, RunnerJson.write(Map.of("uid", uid(pod))));
        }
    }

    private long currentFence(RunnerAssignment assignment) throws IOException {
        Path path = receipt(assignment, ".fence");
        return Files.exists(path) ? RunnerJson.number(RunnerJson.read(read(path)), "fence") : 0;
    }
    private void recordFence(RunnerAssignment assignment) throws Exception {
        synchronized (stopGate) {
            Path path = receipt(assignment, ".fence");
            long current = currentFence(assignment);
            if (assignment.job().fence() < current) {
                telemetry.increment(RunnerTelemetry.Counter.KUBERNETES_FENCE_CONFLICT);
                throw new IllegalStateException("a newer manager fence owns this report or cleanup");
            }
            if (assignment.job().fence() > current) {
                // The model gateway's dispatch gate revokes pending and future provider turns
                // before a newer report-only owner becomes durable. Old execution cannot race
                // its last local admission check into a new external request after this fence.
                if (current > 0 && runtime != null) runtime.models().cancel(assignment);
                atomic(path, RunnerJson.write(Map.of("fence", assignment.job().fence())));
            }
        }
    }

    @Override public CompletionStage<Void> release(RunnerWorkspaceRelease release) {
        return CompletableFuture.runAsync(() -> {
            if (!registration.tenantId().equals(release.execution().tenantId()) || !registration.runnerId().equals(release.runnerId())
                    || clock.instant().isBefore(release.notBefore())) throw new IllegalArgumentException("invalid Kubernetes release authority");
            if (!release.physicalCleanup()) return;
            Path directory = state.resolve(release.workspaceId() + "-" + release.generation());
            if (!Files.exists(directory)) return;
            try {
                var claims = new LinkedHashMap<String, RunnerAssignment>();
                try (var files = Files.newDirectoryStream(directory, "*.intent")) {
                    for (Path file : files) {
                        var assignment = RunnerCodec.assignment(read(file));
                        if (!assignment.job().identity().execution().equals(release.execution()) || !release.jobIds().contains(assignment.job().identity().runnerJobId())
                                || assignment.workspace().profile().workspaceScope() != release.workspaceScope()
                                || assignment.workspace().generation() != release.generation() || !assignment.workspace().workspaceId().equals(release.workspaceId()))
                            throw new IllegalStateException("release does not cover complete Workspace ownership");
                        claims.putIfAbsent(claimName(assignment), assignment);
                    }
                }
                // Establish absence of every owned Pod before deleting any retained volume.
                for (var assignment : claims.values()) stopAll(assignment);
                for (var assignment : claims.values()) {
                            var pvc = api.get("pvc", claimName(assignment));
                            if (pvc != null) {
                                requireOwned(pvc, assignment);
                                String expected = RunnerJson.text(RunnerJson.read(read(directory.resolve(claimName(assignment) + ".volume"))), "uid");
                                requireUid(pvc, expected); api.delete("pvc", claimName(assignment), expected, revision(pvc), 0);
                                long deadline = System.nanoTime() + worker.cleanupTimeout().toNanos();
                                while ((pvc = api.get("pvc", claimName(assignment))) != null) {
                                    requireOwned(pvc, assignment); requireUid(pvc, expected);
                                    if (System.nanoTime() >= deadline) throw new IllegalStateException("PVC UID absence has not been proven");
                                    Thread.sleep(100);
                                }
                            }
                }
                atomic(directory.resolve("released"), RunnerJson.write(Map.of("generation", release.generation())));
                if (runtime != null) runtime.models().released(release);
            } catch (Exception failure) { throw new CompletionException("Kubernetes cleanup is incomplete", failure); }
        }, executor);
    }

    private RunnerResult runAgent(RunnerAssignment assignment) throws Exception {
        var configured = Objects.requireNonNull(runtime, "Agent model runtime is not configured").boundedBy(assignment.job().definition());
        var observation = observation(assignment); requireAdmitted(assignment);
        if (observation.kubernetes().phase() != KubernetesWorkload.Phase.RUNNING) throw new IllegalStateException("Agent Pod is not running");
        var skills = new LinkedHashMap<String, String>();
        for (String name : assignment.job().definition().skills()) {
            String value = configured.skills().get(name); if (value == null) throw new IllegalArgumentException("approved skill is not installed"); skills.put(name, value);
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("protocolVersion", 2); envelope.put("definition", RunnerJson.definition(assignment.job().definition()));
        envelope.put("authority", RunnerJson.policy(assignment.job().authority())); envelope.put("command", assignment.job().command().name());
        envelope.put("input", ai.ravenroot.api.payload.PayloadJson.read(assignment.job().input().bytes(), RunnerJson.LIMITS).toJava());
        envelope.put("workspaceId", assignment.workspaceId().toString()); envelope.put("runtimeId", observation.runtimeId());
        envelope.put("sessionId", assignment.agentSessionId().toString()); envelope.put("skills", skills); envelope.put("testCommand", configured.testCommand());
        envelope.put("outcomes", assignment.job().command().outcomes()); envelope.put("budgets", configured.budgets(Duration.between(clock.instant(), assignment.job().deadline())));
        Process process = api.agent(podName(assignment), configured.protocolBytes()); active.put(assignment.job().identity().runnerJobId(), process);
        var errors = executor.submit(() -> KubernetesApi.bounded(process.getErrorStream(), Math.toIntExact(assignment.job().authority().limits().logBytes())));
        var watchdog = executor.submit(() -> {
            if (!process.waitFor(Math.max(1, Duration.between(clock.instant(), assignment.job().deadline()).toMillis()), TimeUnit.MILLISECONDS)) cancel(assignment);
            return null;
        });
        try (var output = process.getOutputStream(); var input = process.getInputStream()) {
            send(output, envelope); long tokens = 0; int turns = 0, calls = 0;
            for (long exchange = 0; exchange <= (long) configured.modelTurns() + configured.toolCalls(); exchange++) {
                requireAdmitted(assignment); var line = RunnerJson.read(line(input, configured.protocolBytes()));
                if ("tool-permit".equals(line.get("type"))) {
                    if (++calls > configured.toolCalls()) throw new IllegalStateException("Agent tool budget exhausted");
                    atomic(receipt(assignment, ".usage"), RunnerJson.write(Map.of("turns", turns, "calls", calls, "tokens", tokens)));
                    String tool = RunnerJson.text(line, "name"); var capabilities = assignment.job().authority().capabilities();
                    boolean permitted = tool.equals("finish") || Set.of("read_file", "list_files").contains(tool) && capabilities.contains(RunnerPolicy.Capability.WORKSPACE_READ)
                            || tool.equals("write_file") && capabilities.contains(RunnerPolicy.Capability.WORKSPACE_WRITE)
                            || tool.equals("test") && capabilities.contains(RunnerPolicy.Capability.PROCESS_EXECUTE);
                    if (!permitted) throw new IllegalArgumentException("Agent tool authority denied");
                    synchronized (stopGate) { requireAdmitted(assignment); send(output, Map.of("type", "tool-permitted")); }
                    continue;
                }
                if ("result".equals(line.get("type"))) {
                    output.close();
                    if (!process.waitFor(Math.max(1, Duration.between(clock.instant(), assignment.job().deadline()).toMillis()), TimeUnit.MILLISECONDS) || process.exitValue() != 0)
                        throw new IllegalStateException("Agent process quiescence is unconfirmed");
                    byte[] payload = RunnerJson.write(line.get("payload"));
                    if (payload.length > assignment.job().authority().limits().payloadBytes()) throw new IllegalStateException("Agent payload limit exceeded");
                    var evidence = new ArrayList<RunnerArtifact>(); byte[] log = errors.get(worker.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    if (log.length != 0) evidence.add(artifacts.publish(assignment, RunnerArtifact.Kind.STDERR, log));
                    return new RunnerResult(RunnerJson.text(line, "outcome"), OpaquePayload.of(payload, "application/json"), evidence,
                            assignment.job().identity().runnerJobId(), observation);
                }
                if (!"model".equals(line.get("type")) || turns++ >= configured.modelTurns() || tokens >= configured.modelTokens())
                    throw new IllegalStateException("Agent model budget exceeded");
                atomic(receipt(assignment, ".usage"), RunnerJson.write(Map.of("turns", turns, "calls", calls, "tokens", tokens)));
                var answer = configured.models().complete(assignment, line, Math.toIntExact(Math.min(configured.tokensPerTurn(), configured.modelTokens() - tokens)),
                        Duration.between(clock.instant(), assignment.job().deadline()));
                tokens = Math.addExact(tokens, RunnerJson.number(answer, "tokens")); requireAdmitted(assignment);
                atomic(receipt(assignment, ".usage"), RunnerJson.write(Map.of("turns", turns, "calls", calls, "tokens", tokens)));
                if (tokens > configured.modelTokens()) throw new IllegalStateException("Agent token budget exceeded"); send(output, answer);
            }
            throw new IllegalStateException("Agent protocol budget exceeded");
        } finally {
            watchdog.cancel(true); active.remove(assignment.job().identity().runnerJobId(), process);
            try {
                if (process.isAlive()) {
                    try { terminate(assignment); } finally { process.destroyForcibly(); }
                }
                // Preserve bounded partial stderr before a report-only recovery. It is not a
                // terminal artifact until an authorized current fence uploads and accepts it.
                byte[] partial = errors.get(worker.driverOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (partial.length != 0) atomic(receipt(assignment, ".stderr"), partial);
            } finally { errors.cancel(true); }
        }
    }
    private static void send(OutputStream output, Object value) throws IOException { output.write(RunnerJson.write(value)); output.write('\n'); output.flush(); }
    private static byte[] line(InputStream input, int limit) throws IOException {
        var value = new ByteArrayOutputStream();
        for (int i = 0; i <= limit; i++) {
            int next = input.read(); if (next == '\n') return value.toByteArray(); if (next < 0) throw new EOFException("Agent exited without result"); value.write(next);
        }
        throw new IOException("Agent protocol byte ceiling exceeded");
    }
    private void safe(Path path) throws IOException {
        if (!path.startsWith(state) || !path.equals(path.toRealPath())) throw new IOException("unsafe Kubernetes manager state path");
    }
    private byte[] read(Path path) throws IOException { safe(path); try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { return KubernetesApi.bounded(input, RunnerCodec.MAX_BYTES); } }
    private void create(Path path, byte[] bytes) throws IOException {
        safe(path.getParent());
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        sync(path.getParent());
    }
    private void atomic(Path path, byte[] bytes) throws IOException {
        safe(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), "kubernetes-", ".pending");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); sync(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void sync(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.values().forEach(Process::destroy); executor.shutdownNow();
        try {
            if (!executor.awaitTermination(worker.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("manager tasks remain uncertain; durable-state ownership lock retained");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("manager shutdown interrupted; ownership lock retained", interrupted);
        }
        try { managerLock.release(); managerChannel.close(); } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
