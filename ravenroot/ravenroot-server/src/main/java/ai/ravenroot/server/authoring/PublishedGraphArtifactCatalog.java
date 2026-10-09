package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.security.OutboundHttpPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private static final PayloadLimits JSON_LIMITS = new PayloadLimits(2 * 1024 * 1024, 20, 10_000,
            100_000, 1024 * 1024, 512);
    private final GraphAuthoringConfiguration configuration;
    private final HttpClient client;
    private final OutboundHttpPolicy network;

    public record Artifact(String tenantId, String graphId, long releaseVersion, String graphMlSha256,
                           URI graphMlUrl, String manifestSha256, URI manifestUrl,
                           String sourceCommit, String compatibilityContract,
                           List<Map<String, String>> dependencies, byte[] graphMl) {
        public Artifact {
            dependencies = List.copyOf(dependencies); graphMl = graphMl.clone();
        }
        @Override public byte[] graphMl() { return graphMl.clone(); }
        public String token() { return manifestSha256 + ":" + graphMlSha256; }
    }

    public PublishedGraphArtifactCatalog(GraphAuthoringConfiguration configuration) {
        this(configuration, HttpClient.newBuilder().connectTimeout(configuration.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    PublishedGraphArtifactCatalog(GraphAuthoringConfiguration configuration, HttpClient client) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.client = Objects.requireNonNull(client, "client");
        this.network = new OutboundHttpPolicy(Set.of(configuration.artifactBase().getHost()),
                configuration.requestTimeout(), Set.of(port(configuration.artifactBase())),
                1024, Math.max(JSON_LIMITS.maxEncodedBytes(), configuration.maxDocumentBytes()));
    }

    @Override public String token(String tenantId, String graphId, long releaseVersion,
                                  String sourceRevision, byte[] graphMl) {
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(graphMl, "graphMl");
        return entries().stream().filter(entry -> entry.tenantId.equals(tenantId)
                        && entry.graphId.equals(graphId) && entry.releaseVersion == releaseVersion)
                .findFirst().map(this::resolve)
                .filter(artifact -> artifact.sourceCommit().equals(sourceRevision)
                        && MessageDigest.isEqual(artifact.graphMl(), graphMl))
                .map(Artifact::token).orElse("absent");
    }

    public List<Artifact> list(String tenantId) {
        var result = new ArrayList<Artifact>();
        for (Entry entry : entries()) if (entry.tenantId.equals(tenantId)) result.add(resolve(entry));
        return List.copyOf(result);
    }

    public Artifact resolve(String tenantId, String graphId, long releaseVersion) {
        Entry entry = entries().stream().filter(value -> value.tenantId.equals(tenantId)
                        && value.graphId.equals(graphId) && value.releaseVersion == releaseVersion)
                .findFirst().orElseThrow(() -> failure(GraphAuthoringException.Failure.NOT_FOUND));
        return resolve(entry);
    }

    private Artifact resolve(Entry entry) {
        byte[] manifestBytes = get(entry.manifestUrl, JSON_LIMITS.maxEncodedBytes());
        if (!digest(manifestBytes).equals(entry.manifestSha256)) throw invalid();
        Map<String, Object> manifest = object(PayloadJson.read(manifestBytes, JSON_LIMITS).toJava());
        if (!"ravenroot-graph-artifact-v1".equals(string(manifest, "contract"))
                || !entry.tenantId.equals(string(manifest, "tenantId"))
                || !entry.graphId.equals(string(manifest, "graphId"))
                || entry.releaseVersion != number(manifest, "releaseVersion")
                || !entry.sourceCommit.equals(sourceCommit(manifest, "sourceCommit"))
                || !entry.graphMlSha256.equals(string(manifest, "graphMlSha256"))) throw invalid();
        Map<String, Object> compatibility = object(manifest.get("compatibility"));
        if (!Boolean.TRUE.equals(compatibility.get("accepted"))
                || !"ravenroot-local-deployment-admission-v1".equals(string(compatibility, "contract"))
                || !"LOCAL_DEPLOYMENT".equals(string(compatibility, "purpose"))
                || !emptyList(compatibility.get("violations")) || !emptyList(compatibility.get("findings"))) throw invalid();
        count(compatibility, "nodes"); count(compatibility, "edges");
        count(compatibility, "startNodes"); count(compatibility, "endNodes");
        String compatibilityContract = string(compatibility, "contract");
        List<Map<String, String>> dependencies = dependencies(manifest.get("dependencies"));
        byte[] graph = get(entry.graphMlUrl, configuration.maxDocumentBytes());
        if (!digest(graph).equals(entry.graphMlSha256)) throw invalid();
        GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(graph);
        if (metadata == null || !metadata.graphId().equals(entry.graphId)
                || metadata.releaseVersion() != entry.releaseVersion) throw invalid();
        return new Artifact(entry.tenantId, entry.graphId, entry.releaseVersion, entry.graphMlSha256,
                entry.graphMlUrl, entry.manifestSha256, entry.manifestUrl, entry.sourceCommit, compatibilityContract,
                dependencies, graph);
    }

    private List<Entry> entries() {
        URI catalogUrl = confined(configuration.artifactBase().resolve(configuration.artifactCatalogPath()));
        Object decoded = PayloadJson.read(get(catalogUrl, JSON_LIMITS.maxEncodedBytes()), JSON_LIMITS).toJava();
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
            if (version < 1) throw invalid();
            String graphDigest = sha256(item, "graphMlSha256");
            String manifestDigest = sha256(item, "manifestSha256");
            String sourceCommit = sourceCommit(item, "sourceCommit");
            URI graphUrl = confined(configuration.artifactBase().resolve(string(item, "graphMlPath")));
            URI manifestUrl = confined(configuration.artifactBase().resolve(string(item, "manifestPath")));
            if (!identities.add(tenant + "\u0000" + graphId + "\u0000" + version)) throw invalid();
            result.add(new Entry(tenant, graphId, version, sourceCommit, graphDigest, graphUrl,
                    manifestDigest, manifestUrl));
        }
        return List.copyOf(result);
    }

    private byte[] get(URI uri, int limit) {
        network.requireAllowed(uri);
        try {
            var request = HttpRequest.newBuilder(uri).timeout(configuration.requestTimeout())
                    .header("Accept", "application/json, application/graphml+xml;q=0.9")
                    .GET().build();
            var response = client.send(request, BoundedHttpResponseBody.upTo(limit, configuration.requestTimeout()));
            byte[] body = response.body();
            if (response.statusCode() != 200) throw failure(response.statusCode() == 404
                    ? GraphAuthoringException.Failure.NOT_FOUND : GraphAuthoringException.Failure.UNAVAILABLE);
            if (body.length > limit) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            return body;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE, interrupted);
        } catch (IOException failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE, failure);
        }
    }

    private URI confined(URI uri) {
        URI base = configuration.artifactBase();
        String basePath = base.getPath().endsWith("/") ? base.getPath() : base.getPath() + "/";
        String decodedPath = uri.getPath();
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost())
                || port(base) != port(uri) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || java.util.Arrays.asList(decodedPath.split("/")).contains("..")
                || !uri.normalize().getPath().startsWith(basePath)) throw invalid();
        return uri;
    }

    private static String sha256(Map<String, Object> value, String key) {
        String digest = string(value, key).toLowerCase(java.util.Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) throw invalid(); return digest;
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(); return (Map<String, Object>) map;
    }
    private static String string(Map<String, Object> value, String key) {
        Object found = value.get(key); if (!(found instanceof String text) || text.isBlank()) throw invalid(); return text;
    }
    private static long number(Map<String, Object> value, String key) {
        Object found = value.get(key); if (!(found instanceof Number number)) throw invalid(); return number.longValue();
    }
    private static List<Map<String, String>> dependencies(Object value) {
        if (!(value instanceof List<?> list)) throw invalid();
        return list.stream().map(item -> {
            Map<String, Object> dependency = object(item);
            String kind = string(dependency, "kind");
            String id = string(dependency, "id");
            if (!("behavior".equals(kind) || "nodeType".equals(kind)) || id.length() > 512) throw invalid();
            return Map.of("kind", kind, "id", id);
        }).toList();
    }
    private static long count(Map<String, Object> value, String key) {
        long count = number(value, key); if (count < 0 || count > Integer.MAX_VALUE) throw invalid(); return count;
    }
    private static boolean emptyList(Object value) { return value instanceof List<?> list && list.isEmpty(); }
    private static String sourceCommit(Map<String, Object> value, String key) {
        String commit = string(value, key).toLowerCase(java.util.Locale.ROOT);
        if (!commit.matches("[0-9a-f]{40,64}")) throw invalid(); return commit;
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static GraphAuthoringException invalid() { return failure(GraphAuthoringException.Failure.INVALID_DOCUMENT); }
    private static GraphAuthoringException failure(GraphAuthoringException.Failure value) { return new GraphAuthoringException(value); }
    private record Entry(String tenantId, String graphId, long releaseVersion, String sourceCommit, String graphMlSha256,
                         URI graphMlUrl, String manifestSha256, URI manifestUrl) {
        String token() { return manifestSha256 + ":" + graphMlSha256; }
    }
}
