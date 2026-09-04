package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.security.nodepackage.StorageManagedServicesHarness;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real TLS/SigV4 integration against a pinned S3-compatible MinIO server. */
class StorageMinioIntegrationTest {
    private static final String MINIO_IMAGE =
            "quay.io/minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e";
    private static final String MC_IMAGE =
            "quay.io/minio/mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3";
    private static final String TENANT = "tenant-a";
    private static final String RECOVERY_NODE = "recover-list";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    @TempDir Path directory;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realMinioPreservesVersionAndPaginationAuthorityAcrossDurableRestart() throws Exception {
        Assumptions.assumeTrue(command(Duration.ofSeconds(10), "docker", "info").exitCode == 0,
                "Docker is required for the pinned MinIO integration");
        Assumptions.assumeTrue(command(Duration.ofSeconds(10), "openssl", "version").exitCode == 0,
                "OpenSSL is required to mint the ephemeral test-only TLS identity");
        ReservedNetworkPolicy previousPolicy = EgressAddressGuard.policy();
        boolean cleanupInterruptionExercised = false;
        EgressAddressGuard.configure(ReservedNetworkPolicy.shippedDefault());
        try (MinioServer minio = MinioServer.start(directory.resolve("tls"))) {
            minio.initialize();
            StorageProfile profile = profile(minio.endpoint());
            NodePackageServices services = services(minio);

            put(profile, services, "folder/a.txt", "a");
            put(profile, services, "folder/b.txt", "b");
            String firstVersion = put(profile, services, "folder/versioned.txt", "version-one");
            String secondVersion = put(profile, services, "folder/versioned.txt", "version-two");
            assertNotEquals(firstVersion, secondVersion);
            assertTrue(minio.versionExists("tenant-data/folder/versioned.txt", firstVersion));
            assertTrue(minio.versionExists("tenant-data/folder/versioned.txt", secondVersion));

            MovableClock clock = new MovableClock();
            Path database = directory.resolve("execution.db");
            RecoveryFixture abandoned;
            Map<?, ?> pageOne;
            try (var store = new SqliteExecutionStore(database, clock)) {
                abandoned = ambiguousListAttempt(store);
                pageOne = list(profile, services, null, 2);
            }
            String cursor = (String) pageOne.get("cursor");
            assertNotNull(cursor);
            clock.advance(LEASE_TTL.plusSeconds(1));

            NodePackageServices restartedServices = services(minio);
            Map<?, ?> pageTwo;
            try (var reopened = new SqliteExecutionStore(database, clock)) {
                pageTwo = list(profile, restartedServices, cursor, 2);
                assertFalse(pageTwo.containsKey("cursor"));

                AtomicReference<Map<?, ?>> recoveredProviderResult = new AtomicReference<>();
                NodeAction recoveredList = listAction(profile, restartedServices, 100);
                RecoveryDispatcher dispatcher = new RecoveryDispatcher() {
                    @Override public boolean canDispatch(PendingWork item) { return true; }
                    @Override public void dispatch(PendingWork item, String idempotencyKey) {
                        NodeResult result = recoveredList.handle(StorageTestSupport.message(TENANT,
                                Map.of("version", "object.list.v1"))).toCompletableFuture().join();
                        recoveredProviderResult.set((Map<?, ?>) result.payload());
                    }
                };
                List<RecoveryOutcome> outcomes = new ExecutionRecoveryService(reopened, List.of(TENANT),
                        "replacement-runtime", 10, LEASE_TTL, declarations(profile, restartedServices), dispatcher)
                        .sweepOnce();
                assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcomes.stream()
                        .filter(outcome -> outcome.key().equals(abandoned.key)).findFirst().orElseThrow());
                assertFalse(((List<?>) recoveredProviderResult.get().get("objects")).isEmpty(),
                        "the real recovery dispatch must execute against MinIO, not just record a callback");
            }

            NodeAction delete = deleteAction(profile, restartedServices);
            Map<?, ?> deleted = (Map<?, ?>) delete.handle(StorageTestSupport.message(TENANT, Map.of(
                    "version", "object.delete.v1", "versionId", firstVersion)))
                    .toCompletableFuture().join().payload();
            assertEquals("DELETED", deleted.get("status"));
            assertFalse(minio.versionExists("tenant-data/folder/versioned.txt", firstVersion));
            assertTrue(minio.versionExists("tenant-data/folder/versioned.txt", secondVersion),
                    "deleting one exact version must preserve the other version");

            Map<?, ?> latest = (Map<?, ?>) get(profile, restartedServices, "folder/versioned.txt")
                    .handle(StorageTestSupport.message(TENANT, Map.of("version", "object.get.v1")))
                    .toCompletableFuture().join().payload();
            assertEquals(secondVersion, latest.get("versionId"));
            assertEquals("version-two", new String(java.util.Base64.getDecoder().decode(
                    (String) latest.get("base64")), StandardCharsets.UTF_8));

            assertDoesNotThrow(() -> delete.handle(StorageTestSupport.message(TENANT, Map.of(
                    "version", "object.delete.v1", "versionId", firstVersion))).toCompletableFuture().join(),
                    "repeating the same exact-version delete is idempotent");

            StorageProfile listOnly = new StorageProfile(profile.name(), profile.origin(), profile.region(),
                    profile.bucket(), profile.keyPrefix(), profile.addressingStyle(), profile.signingBindingId(),
                    Set.of(StorageProfile.Operation.LIST), profile.allowedContentTypes(), false, false,
                    profile.maxObjectBytes(), profile.timeoutMs(), profile.maxConcurrency(),
                    profile.maxRequestsPerSecond());
            assertEquals(StorageException.Code.CONFIGURATION, assertThrows(StorageException.class,
                    () -> deleteAction(listOnly, restartedServices)).code());
            assertTrue(minio.versionExists("tenant-data/folder/versioned.txt", secondVersion));
            cleanupInterruptionExercised = true;
            Thread.currentThread().interrupt();
        } finally {
            EgressAddressGuard.configure(previousPolicy);
        }
        assertTrue(cleanupInterruptionExercised);
        assertTrue(Thread.interrupted(), "container cleanup must restore the caller's interrupt status");
    }

    private static String put(StorageProfile profile, NodePackageServices services, String key, String value) {
        NodeAction action = new ObjectPutNodeBehavior(new StorageRuntime(name -> Optional.of(profile))).create(
                new NodeConfiguration("put", ObjectPutNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", key)), services);
        Map<?, ?> result = (Map<?, ?>) action.handle(StorageTestSupport.message(TENANT,
                Map.of("version", "object.put.v1", "text", value))).toCompletableFuture().join().payload();
        return (String) result.get("versionId");
    }

    private static Map<?, ?> list(StorageProfile profile, NodePackageServices services, String cursor, int maximum) {
        Map<String, Object> payload = cursor == null ? Map.of("version", "object.list.v1")
                : Map.of("version", "object.list.v1", "cursor", cursor);
        return (Map<?, ?>) listAction(profile, services, maximum).handle(StorageTestSupport.message(TENANT, payload))
                .toCompletableFuture().join().payload();
    }

    private static NodeAction listAction(StorageProfile profile, NodePackageServices services, int maximum) {
        return new ObjectListNodeBehavior(new StorageRuntime(name -> Optional.of(profile))).create(
                new NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "prefix", "folder",
                                "maxResults", Integer.toString(maximum), "projection", "size")), services);
    }

    private static NodeAction deleteAction(StorageProfile profile, NodePackageServices services) {
        return new ObjectDeleteNodeBehavior(new StorageRuntime(name -> Optional.of(profile))).create(
                new NodeConfiguration("delete", ObjectDeleteNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "folder/versioned.txt")), services);
    }

    private static NodeAction get(StorageProfile profile, NodePackageServices services, String key) {
        return new ObjectGetNodeBehavior(new StorageRuntime(name -> Optional.of(profile))).create(
                new NodeConfiguration("get", ObjectGetNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", key)), services);
    }

    private static StorageProfile profile(URI endpoint) {
        return new StorageProfile("assets", endpoint, "us-east-1", "bucket-a", "tenant-data",
                StorageProfile.AddressingStyle.PATH, "assets-s3",
                Set.of(StorageProfile.Operation.GET, StorageProfile.Operation.PUT, StorageProfile.Operation.LIST,
                        StorageProfile.Operation.DELETE, StorageProfile.Operation.DELETE_VERSION),
                Set.of("text/plain"), false, false, 1024 * 1024, 8_000, 4, 100);
    }

    private static NodePackageServices services(MinioServer minio) {
        return StorageManagedServicesHarness.realS3(
                minio.endpoint(), minio.client(), minio.accessKey(), minio.secretKey());
    }

    private static RepeatabilityDeclarations declarations(StorageProfile profile, NodePackageServices services) {
        var nodePackage = new StorageNodePackage(name -> Optional.of(profile));
        BehaviorRegistry catalog = NodePackages.register(new BehaviorRegistry(), nodePackage,
                NodePackageServiceRegistry.builder().grant("ai.ravenroot.extensions.storage", services).build());
        return RepeatabilityDeclarations.fromGraph(List.of(new GraphNode(RECOVERY_NODE, NodeKind.BEHAVIOR,
                        ObjectListNodeBehavior.BEHAVIOR, Map.of(RecoveryRepeatabilityProperty.NAME,
                        RecoveryRepeatabilityProperty.REPEATABLE))), catalog::descriptor);
    }

    private static RecoveryFixture ambiguousListAttempt(ExecutionStore store) {
        ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID(), invocationId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        ProcessInstance accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1"))).build()));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId, new NodeInvocation(invocationId,
                        RECOVERY_NODE, Set.of(), NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))).build()));
        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-runtime", 10, LEASE_TTL)).stream()
                .filter(item -> item.key().equals(key)).findFirst().orElseThrow();
        await(store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(scheduled.revision()))
                .fencedBy(claimed.fencingToken()).apply(new ExecutionTransition.AttemptTransitioned(
                        traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING)).build()));
        return new RecoveryFixture(key);
    }

    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private record RecoveryFixture(ExecutionKey key) { }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration amount) { now = now.plus(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class MinioServer implements AutoCloseable {
        private final String name;
        private final URI endpoint;
        private final HttpClient client;
        private final String accessKey;
        private final String secretKey;

        private MinioServer(String name, URI endpoint, HttpClient client, String accessKey, String secretKey) {
            this.name = name;
            this.endpoint = endpoint;
            this.client = client;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
        }

        static MinioServer start(Path tlsDirectory) throws Exception {
            Files.createDirectories(tlsDirectory);
            Path certificate = tlsDirectory.resolve("public.crt");
            Path privateKey = tlsDirectory.resolve("private.key");
            requireSuccess(command(Duration.ofSeconds(20), "openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-sha256", "-nodes", "-days", "2", "-subj", "/CN=localhost", "-addext",
                    "subjectAltName=DNS:localhost", "-keyout", privateKey.toString(), "-out",
                    certificate.toString()), "TLS fixture creation");
            String name = "ravenroot-minio-" + UUID.randomUUID().toString().replace("-", "");
            String accessKey = "rr" + UUID.randomUUID().toString().replace("-", "");
            String secretKey = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            try {
                CommandResult started = sensitiveCommand(Duration.ofSeconds(60), Map.of(
                                "MINIO_ROOT_USER", accessKey, "MINIO_ROOT_PASSWORD", secretKey),
                        "docker", "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::9000",
                        "-e", "MINIO_ROOT_USER", "-e", "MINIO_ROOT_PASSWORD", "-v",
                        tlsDirectory.toAbsolutePath() + ":/root/.minio/certs:ro", MINIO_IMAGE,
                        "server", "/data", "--address", ":9000");
                requireSuccess(started, "MinIO start");
                CommandResult port = requireSuccess(command(Duration.ofSeconds(10), "docker", "port", name,
                        "9000/tcp"), "MinIO port lookup");
                int exposed = Integer.parseInt(port.output.strip().substring(port.output.strip().lastIndexOf(':') + 1));
                HttpClient client = trustedClient(certificate);
                URI endpoint = URI.create("https://localhost:" + exposed);
                waitUntilReady(client, endpoint);
                return new MinioServer(name, endpoint, client, accessKey, secretKey);
            } catch (Exception failure) {
                try {
                    removeContainer(name);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void initialize() {
            requireSuccess(mc("mc --insecure mb --ignore-existing local/bucket-a"
                    + " && mc --insecure version enable local/bucket-a"), "MinIO bucket initialization");
        }

        boolean versionExists(String key, String versionId) {
            CommandResult result = sensitiveCommand(Duration.ofSeconds(30), credentialEnvironment(),
                    "docker", "run", "--rm", "-e", "RR_ACCESS_KEY", "-e", "RR_SECRET_KEY",
                    "--network", "container:" + name, "-e", "VERSION_ID=" + versionId, "-e", "OBJECT_KEY=" + key,
                    "--entrypoint", "/bin/sh", MC_IMAGE, "-c", alias()
                    + " && mc --insecure stat --version-id \"$VERSION_ID\" \"local/bucket-a/$OBJECT_KEY\"");
            return result.exitCode == 0;
        }

        private CommandResult mc(String operation) {
            return sensitiveCommand(Duration.ofSeconds(30), credentialEnvironment(), "docker", "run", "--rm",
                    "-e", "RR_ACCESS_KEY", "-e", "RR_SECRET_KEY", "--network", "container:" + name,
                    "--entrypoint", "/bin/sh", MC_IMAGE, "-c",
                    alias() + " && " + operation);
        }

        private static String alias() {
            return "mc --insecure alias set local https://localhost:9000 \"$RR_ACCESS_KEY\" \"$RR_SECRET_KEY\"";
        }

        private Map<String, String> credentialEnvironment() {
            return Map.of("RR_ACCESS_KEY", accessKey, "RR_SECRET_KEY", secretKey);
        }

        URI endpoint() { return endpoint; }
        HttpClient client() { return client; }
        String accessKey() { return accessKey; }
        String secretKey() { return secretKey; }

        @Override public void close() {
            removeContainer(name);
        }

        private static void removeContainer(String name) {
            boolean interrupted = Thread.interrupted();
            try {
                CleanupCommandResult removal = cleanupCommand(Duration.ofSeconds(10),
                        "docker", "rm", "-f", name);
                interrupted |= removal.interrupted();
                CleanupCommandResult remaining = cleanupCommand(Duration.ofSeconds(5), "docker", "container",
                        "ls", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}");
                interrupted |= remaining.interrupted();
                requireSuccess(remaining.result(), "MinIO cleanup verification");
                if (!remaining.result().output().isBlank()) {
                    throw new IllegalStateException("MinIO cleanup did not remove the test container");
                }
                if (removal.result().exitCode() != 0
                        && !removal.result().output().contains("No such container")) {
                    throw new IllegalStateException("MinIO cleanup failed: " + bounded(removal.result().output()));
                }
            } finally {
                interrupted |= Thread.interrupted();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static CleanupCommandResult cleanupCommand(Duration timeout, String... arguments) {
            boolean interrupted = false;
            CommandResult result = new CommandResult(-1, "cleanup command did not run");
            for (int attempt = 0; attempt < 3; attempt++) {
                interrupted |= Thread.interrupted();
                result = command(timeout, arguments);
                boolean attemptInterrupted = Thread.interrupted();
                interrupted |= attemptInterrupted;
                if (!attemptInterrupted && !"command interrupted".equals(result.output())) break;
            }
            return new CleanupCommandResult(result, interrupted);
        }

        private static HttpClient trustedClient(Path certificate) throws Exception {
            byte[] pem = Files.readAllBytes(certificate);
            var parsed = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem));
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null);
            trust.setCertificateEntry("minio", parsed);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        }

        private static void waitUntilReady(HttpClient client, URI endpoint) throws Exception {
            Instant deadline = Instant.now().plusSeconds(30);
            Exception last = null;
            while (Instant.now().isBefore(deadline)) {
                try {
                    HttpResponse<Void> response = client.send(HttpRequest.newBuilder(
                            endpoint.resolve("/minio/health/live")).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return;
                } catch (Exception unavailable) {
                    last = unavailable;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("MinIO did not become ready", last);
        }
    }

    private static CommandResult requireSuccess(CommandResult result, String operation) {
        if (result.exitCode != 0) {
            throw new IllegalStateException(operation + " failed: " + bounded(result.output));
        }
        return result;
    }

    private static CommandResult command(Duration timeout, String... arguments) {
        return command(timeout, false, arguments);
    }

    private static CommandResult sensitiveCommand(
            Duration timeout, Map<String, String> environment, String... arguments) {
        return command(timeout, true, environment, arguments);
    }

    private static CommandResult command(Duration timeout, boolean sensitive, String... arguments) {
        return command(timeout, sensitive, Map.of(), arguments);
    }

    private static CommandResult command(
            Duration timeout, boolean sensitive, Map<String, String> environment, String... arguments) {
        Process process = null;
        CompletableFuture<String> output = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
            builder.environment().putAll(environment);
            process = builder.start();
            process.getOutputStream().close();
            Process started = process;
            output = CompletableFuture.supplyAsync(() -> {
                try {
                    return readBounded(started.getInputStream(), !sensitive);
                } catch (IOException failure) {
                    return "output unavailable";
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return new CommandResult(-1, "command timed out");
            }
            return new CommandResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            terminate(process);
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "command interrupted");
        } catch (Exception failure) {
            terminate(process);
            return new CommandResult(-1, failure.getClass().getSimpleName());
        } finally {
            closeStreams(process);
            if (process != null && process.isAlive()) terminate(process);
            if (output != null && !output.isDone()) output.cancel(true);
        }
    }

    private static String readBounded(InputStream input, boolean retainOutput) throws IOException {
        ByteArrayOutputStream retained = retainOutput ? new ByteArrayOutputStream() : null;
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) >= 0) {
            if (retained != null) {
                int remaining = Math.max(0, 8192 - retained.size());
                retained.write(chunk, 0, Math.min(count, remaining));
            }
        }
        return retained == null ? "sensitive command output withheld" : retained.toString(StandardCharsets.UTF_8);
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        boolean interrupted = false;
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("command process could not be terminated");
                }
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void closeStreams(Process process) {
        if (process == null) return;
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private static String bounded(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private record CommandResult(int exitCode, String output) { }
    private record CleanupCommandResult(CommandResult result, boolean interrupted) { }
}
