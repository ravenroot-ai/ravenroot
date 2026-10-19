package ai.ravenroot.server.authoring;

import ai.ravenroot.api.application.GraphArtifactDependencies;
import ai.ravenroot.api.application.GraphProgramDependency;
import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.core.security.OutboundHttpPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads a CI-published immutable catalog and verifies its external digest manifest before use. */
public final class PublishedGraphArtifactCatalog implements PublicationEvidence {
    private static final PayloadLimits JSON_LIMITS =
            new PayloadLimits(2 * 1024 * 1024, 20, 10_000, 100_000, 1024 * 1024, 512);
    private final GraphAuthoringConfiguration configuration;
    private final HttpClient client;
    private final OutboundHttpPolicy network;
    private final ReadResults<Artifact> verified =
            new ReadResults<>(artifact -> artifact.graphMl.length, 200, 64 * 1024 * 1024L);
    private final java.util.concurrent.locks.ReentrantLock catalogLock =
            new java.util.concurrent.locks.ReentrantLock();
    private byte[] catalogBytes;
    private String catalogEtag;
    private final java.util.concurrent.atomic.LongAdder providerCalls =
            new java.util.concurrent.atomic.LongAdder();

    /**
     * Fresh independent catalog observation; immutable verified artifacts are reused by their
     * digests.
     */
    @Override
    public PublicationEvidence snapshot() {
        List<Entry> observed = entries();
        String version = digest(observed.toString().getBytes(StandardCharsets.UTF_8));
        return new PublicationEvidence() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public String token(
                    String tenant, String graph, long release, String source, byte[] bytes) {
                return tokenByDigest(tenant, graph, release, source, digest(bytes), () -> bytes);
            }

            @Override
            public String tokenByDigest(
                    String tenant,
                    String graph,
                    long release,
                    String source,
                    String digest,
                    java.util.function.Supplier<byte[]> unused) {
                return observed.stream()
                        .filter(
                                entry ->
                                        entry.tenantId.equals(tenant)
                                                && entry.graphId.equals(graph)
                                                && entry.releaseVersion == release)
                        .findFirst()
                        .filter(
                                entry ->
                                        entry.sourceCommit.equals(source)
                                                && entry.graphMlSha256.equals(digest))
                        .map(PublishedGraphArtifactCatalog.this::resolve)
                        .map(Artifact::token)
                        .orElse("absent");
            }
        };
    }

    /** Bounded-cardinality count for authoring/catalog diagnostics. */
    public long providerCalls() {
        return providerCalls.sum();
    }

    public synchronized long retainedBytes() {
        return verified.retainedBytes();
    }

    public record Artifact(
            String tenantId,
            String graphId,
            long releaseVersion,
            String graphMlSha256,
            URI graphMlUrl,
            String manifestSha256,
            URI manifestUrl,
            String sourceCommit,
            String compatibilityContract,
            GraphArtifactDependencies dependencies,
            byte[] graphMl) {
        public Artifact {
            Objects.requireNonNull(dependencies, "dependencies");
            graphMl = graphMl.clone();
        }

        @Override
        public byte[] graphMl() {
            return graphMl.clone();
        }

        public String token() {
            return manifestSha256 + ":" + graphMlSha256;
        }
    }

    public PublishedGraphArtifactCatalog(GraphAuthoringConfiguration configuration) {
        this(
                configuration,
                HttpClient.newBuilder()
                        .connectTimeout(configuration.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    PublishedGraphArtifactCatalog(GraphAuthoringConfiguration configuration, HttpClient client) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.client = Objects.requireNonNull(client, "client");
        this.network =
                new OutboundHttpPolicy(
                        Set.of(configuration.artifactBase().getHost()),
                        configuration.requestTimeout(),
                        Set.of(port(configuration.artifactBase())),
                        1024,
                        Math.max(JSON_LIMITS.maxEncodedBytes(), configuration.maxDocumentBytes()));
    }

    @Override
    public String token(
            String tenantId,
            String graphId,
            long releaseVersion,
            String sourceRevision,
            byte[] graphMl) {
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(graphMl, "graphMl");
        return entries().stream()
                .filter(
                        entry ->
                                entry.tenantId.equals(tenantId)
                                        && entry.graphId.equals(graphId)
                                        && entry.releaseVersion == releaseVersion)
                .findFirst()
                .map(this::resolve)
                .filter(
                        artifact ->
                                artifact.sourceCommit().equals(sourceRevision)
                                        && MessageDigest.isEqual(artifact.graphMl(), graphMl))
                .map(Artifact::token)
                .orElse("absent");
    }

    public List<Artifact> list(String tenantId) {
        var result = new ArrayList<Artifact>();
        for (Entry entry : entries())
            if (entry.tenantId.equals(tenantId)) result.add(resolve(entry));
        return List.copyOf(result);
    }

    public Artifact resolve(String tenantId, String graphId, long releaseVersion) {
        Entry entry =
                entries().stream()
                        .filter(
                                value ->
                                        value.tenantId.equals(tenantId)
                                                && value.graphId.equals(graphId)
                                                && value.releaseVersion == releaseVersion)
                        .findFirst()
                        .orElseThrow(() -> failure(GraphAuthoringException.Failure.NOT_FOUND));
        return resolve(entry);
    }

    private Artifact resolve(Entry entry) {
        return verified.get(List.of(entry.toString()), () -> resolveUncached(entry));
    }

    private Artifact resolveUncached(Entry entry) {
        byte[] manifestBytes = get(entry.manifestUrl, JSON_LIMITS.maxEncodedBytes());
        if (!digest(manifestBytes).equals(entry.manifestSha256)) throw invalid();
        Map<String, Object> manifest =
                object(PayloadJson.read(manifestBytes, JSON_LIMITS).toJava());
        if (!"ravenroot-graph-artifact-v1".equals(string(manifest, "contract"))
                || !entry.tenantId.equals(string(manifest, "tenantId"))
                || !entry.graphId.equals(string(manifest, "graphId"))
                || entry.releaseVersion != number(manifest, "releaseVersion")
                || !entry.sourceCommit.equals(sourceCommit(manifest, "sourceCommit"))
                || !entry.graphMlSha256.equals(string(manifest, "graphMlSha256"))) throw invalid();
        Map<String, Object> compatibility = object(manifest.get("compatibility"));
        if (!Boolean.TRUE.equals(compatibility.get("accepted"))
                || !"ravenroot-local-deployment-admission-v1"
                        .equals(string(compatibility, "contract"))
                || !"LOCAL_DEPLOYMENT".equals(string(compatibility, "purpose"))
                || !emptyList(compatibility.get("violations"))
                || !emptyList(compatibility.get("findings"))) throw invalid();
        count(compatibility, "nodes");
        count(compatibility, "edges");
        count(compatibility, "startNodes");
        count(compatibility, "endNodes");
        String compatibilityContract = string(compatibility, "contract");
        GraphArtifactDependencies dependencies = dependencies(manifest.get("dependencies"));
        byte[] graph = get(entry.graphMlUrl, configuration.maxDocumentBytes());
        if (!digest(graph).equals(entry.graphMlSha256)) throw invalid();
        GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(graph);
        if (metadata == null
                || !metadata.graphId().equals(entry.graphId)
                || metadata.releaseVersion() != entry.releaseVersion) throw invalid();
        return new Artifact(
                entry.tenantId,
                entry.graphId,
                entry.releaseVersion,
                entry.graphMlSha256,
                entry.graphMlUrl,
                entry.manifestSha256,
                entry.manifestUrl,
                entry.sourceCommit,
                compatibilityContract,
                dependencies,
                graph);
    }

    private List<Entry> entries() {
        URI catalogUrl =
                confined(configuration.artifactBase().resolve(configuration.artifactCatalogPath()));
        Object decoded =
                PayloadJson.read(get(catalogUrl, JSON_LIMITS.maxEncodedBytes()), JSON_LIMITS)
                        .toJava();
        Map<String, Object> catalog = object(decoded);
        if (!"ravenroot-graph-catalog-v1".equals(string(catalog, "contract"))) throw invalid();
        if (!(catalog.get("artifacts") instanceof List<?> values)) throw invalid();
        if (values.size() > 200) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        var result = new ArrayList<Entry>();
        var identities = new java.util.HashSet<String>();
        for (Object value : values) {
            Map<String, Object> item = object(value);
            String tenant = string(item, "tenantId");
            String graphId = string(item, "graphId");
            long version = number(item, "releaseVersion");
            if (version < 1 || version > GraphReleaseMetadata.MAX_RELEASE_VERSION) throw invalid();
            String graphDigest = sha256(item, "graphMlSha256");
            String manifestDigest = sha256(item, "manifestSha256");
            String sourceCommit = sourceCommit(item, "sourceCommit");
            URI graphUrl =
                    confined(configuration.artifactBase().resolve(string(item, "graphMlPath")));
            URI manifestUrl =
                    confined(configuration.artifactBase().resolve(string(item, "manifestPath")));
            if (!identities.add(tenant + "\u0000" + graphId + "\u0000" + version)) throw invalid();
            result.add(
                    new Entry(
                            tenant,
                            graphId,
                            version,
                            sourceCommit,
                            graphDigest,
                            graphUrl,
                            manifestDigest,
                            manifestUrl));
        }
        return List.copyOf(result);
    }

    private byte[] get(URI uri, int limit) {
        boolean catalog =
                uri.equals(
                        configuration.artifactBase().resolve(configuration.artifactCatalogPath()));
        if (catalog) {
            try {
                catalogLock.lockInterruptibly();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw failure(GraphAuthoringException.Failure.UNAVAILABLE);
            }
            try {
                return getObserved(uri, limit, true);
            } finally {
                catalogLock.unlock();
            }
        }
        return getObserved(uri, limit, false);
    }

    private byte[] getObserved(URI uri, int limit, boolean catalog) {
        network.requireAllowed(uri);
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(configuration.requestTimeout());
            if (catalog && catalogEtag != null) builder.header("If-None-Match", catalogEtag);
            var request =
                    builder.header("Accept", "application/json, application/graphml+xml;q=0.9")
                            .GET()
                            .build();
            providerCalls.increment();
            var response =
                    client.send(
                            request,
                            BoundedHttpResponseBody.upTo(limit, configuration.requestTimeout()));
            for (int attempt = 0;
                    attempt < configuration.retryLimit()
                            && AuthoringRetry.retryable(response.statusCode(), response.headers());
                    attempt++) {
                AuthoringRetry.pause(response.headers(), attempt, configuration.requestTimeout());
                providerCalls.increment();
                response =
                        client.send(
                                request,
                                BoundedHttpResponseBody.upTo(
                                        limit, configuration.requestTimeout()));
            }
            if (catalog && response.statusCode() == 304) {
                if (catalogBytes == null) throw invalid();
                return catalogBytes.clone();
            }
            byte[] body = response.body();
            if (response.statusCode() != 200)
                throw failure(
                        response.statusCode() == 404
                                ? GraphAuthoringException.Failure.NOT_FOUND
                                : GraphAuthoringException.Failure.UNAVAILABLE);
            if (body.length > limit) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            if (catalog) {
                catalogBytes = body.clone();
                catalogEtag = response.headers().firstValue("ETag").orElse(null);
            }
            return body;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAuthoringException(
                    GraphAuthoringException.Failure.UNAVAILABLE, interrupted);
        } catch (IOException failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE, failure);
        }
    }

    private URI confined(URI uri) {
        URI base = configuration.artifactBase();
        String basePath = base.getPath().endsWith("/") ? base.getPath() : base.getPath() + "/";
        String decodedPath = uri.getPath();
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
                || !base.getHost().equalsIgnoreCase(uri.getHost())
                || port(base) != port(uri)
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || java.util.Arrays.asList(decodedPath.split("/")).contains("..")
                || !uri.normalize().getPath().startsWith(basePath)) throw invalid();
        return uri;
    }

    private static String sha256(Map<String, Object> value, String key) {
        String digest = string(value, key).toLowerCase(java.util.Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) throw invalid();
        return digest;
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> value, String key) {
        Object found = value.get(key);
        if (!(found instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }

    private static long number(Map<String, Object> value, String key) {
        Object found = value.get(key);
        if (!(found instanceof Long number)) throw invalid();
        return number;
    }

    private static GraphArtifactDependencies dependencies(Object value) {
        Map<String, Object> dependency = object(value);
        exactKeys(dependency, Set.of("contract", "nodePackages", "programs"));
        if (!GraphArtifactDependencies.CONTRACT.equals(string(dependency, "contract")))
            throw invalid();
        if (!(dependency.get("nodePackages") instanceof List<?> packageValues)
                || !(dependency.get("programs") instanceof List<?> programValues)
                || packageValues.size() > 256
                || programValues.size() > 256) throw invalid();
        try {
            var packages =
                    packageValues.stream()
                            .map(
                                    item -> {
                                        Map<String, Object> entry = object(item);
                                        exactKeys(entry, Set.of("packageId", "identityDigest"));
                                        return new PinnedNodePackage(
                                                string(entry, "packageId"),
                                                sha256(entry, "identityDigest"));
                                    })
                            .toList();
            var programs =
                    programValues.stream()
                            .map(
                                    item -> {
                                        Map<String, Object> entry = object(item);
                                        exactKeys(
                                                entry,
                                                Set.of(
                                                        "nodeId",
                                                        "language",
                                                        "sourceSha256",
                                                        "artifactId",
                                                        "artifactSha256",
                                                        "artifactRevision",
                                                        "runtimeCompatibilitySha256"));
                                        return new GraphProgramDependency(
                                                string(entry, "nodeId"),
                                                string(entry, "language"),
                                                sha256(entry, "sourceSha256"),
                                                string(entry, "artifactId"),
                                                sha256(entry, "artifactSha256"),
                                                nonnegative(entry, "artifactRevision"),
                                                sha256(entry, "runtimeCompatibilitySha256"));
                                    })
                            .toList();
            return new GraphArtifactDependencies(packages, programs);
        } catch (IllegalArgumentException rejected) {
            throw invalid();
        }
    }

    private static long nonnegative(Map<String, Object> value, String key) {
        long result = number(value, key);
        if (result < 0) throw invalid();
        return result;
    }

    private static void exactKeys(Map<String, Object> value, Set<String> keys) {
        if (!value.keySet().equals(keys)) throw invalid();
    }

    private static long count(Map<String, Object> value, String key) {
        long count = number(value, key);
        if (count < 0 || count > Integer.MAX_VALUE) throw invalid();
        return count;
    }

    private static boolean emptyList(Object value) {
        return value instanceof List<?> list && list.isEmpty();
    }

    private static String sourceCommit(Map<String, Object> value, String key) {
        String commit = string(value, key).toLowerCase(java.util.Locale.ROOT);
        if (!commit.matches("[0-9a-f]{40,64}")) throw invalid();
        return commit;
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static GraphAuthoringException invalid() {
        return failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
    }

    private static GraphAuthoringException failure(GraphAuthoringException.Failure value) {
        return new GraphAuthoringException(value);
    }

    private record Entry(
            String tenantId,
            String graphId,
            long releaseVersion,
            String sourceCommit,
            String graphMlSha256,
            URI graphMlUrl,
            String manifestSha256,
            URI manifestUrl) {
        String token() {
            return manifestSha256 + ":" + graphMlSha256;
        }
    }
}
