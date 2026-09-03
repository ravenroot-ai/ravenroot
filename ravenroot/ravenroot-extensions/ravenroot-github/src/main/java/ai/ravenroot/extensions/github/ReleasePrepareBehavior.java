package ai.ravenroot.extensions.github;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Structurally read-only release metadata proposal; it has no merge, ref, release or dispatch operation. */
public final class ReleasePrepareBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "release-prepare";
    private final GithubRuntime runtime;
    ReleasePrepareBehavior(GithubRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return GithubBehaviorDescriptors.descriptor(BEHAVIOR,
            "Prepare release metadata", "Reads an exact commit and proposes bounded release metadata without mutation authority.",
            false, false, true); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = GithubBehaviorDescriptors.profile(configuration);
        return message -> invoke(message, services, profileName);
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services, String profileName) {
        final Input input; final GithubProfile profile;
        try {
            input = Input.parse(message.payload()); profile = runtime.requireProfile(message.tenantId(), profileName);
            if (!profile.release().allowedKinds().contains(input.kind)) throw new GithubException(GithubException.Code.FORBIDDEN);
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
        long deadline = System.currentTimeMillis() + profile.timeoutMs();
        String key = input.commit + ":" + input.kind + ":" + input.correlationId;
        try { return runtime.submit(message, services, profile, BEHAVIOR, key, input.canonical(), deadline,
                (api, operation, control) -> prepare(api, profile, input)); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
    }

    private static NodeResult prepare(GithubApi api, GithubProfile profile, Input input) {
        requireHead(api, profile, input.commit);
        Map<String, Object> commit = GithubProtocol.object(api.get(profile.repositoryPath() + "/commits/" + input.commit));
        if (!input.commit.equals(commit.get("sha"))) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        byte[] versionBytes = content(api, profile, profile.release().versionPath(), input.commit,
                profile.maxResponseBytes());
        Semver current = projectVersion(versionBytes);
        Semver next = current.bump(input.kind);
        List<Map<String, Object>> listing = GithubValues.objectList(GithubProtocol.list(api.get(profile.repositoryPath()
                + "/contents/" + encodePath(profile.release().fragmentsPath()) + "?ref=" + input.commit)),
                profile.release().maxFiles());
        List<Fragment> fragments = new ArrayList<>(); int bytes = 0;
        for (Map<String, Object> entry : listing) {
            if (!"file".equals(entry.get("type"))) continue;
            String name = GithubValues.string(entry.get("name"), 256);
            String kind = fragmentKind(name); if (kind.isEmpty()) continue;
            String path = listedPath(profile.release().fragmentsPath(), name,
                    GithubValues.string(entry.get("path"), 512));
            byte[] body = content(api, profile, path, input.commit,
                    Math.min(profile.maxResponseBytes(), 64 * 1024));
            bytes = Math.addExact(bytes, body.length); if (bytes > profile.maxResponseBytes()) throw GithubValues.invalid();
            String text = new String(body, StandardCharsets.UTF_8).strip();
            if (text.isEmpty() || text.length() > 8_192) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            fragments.add(new Fragment(name, kind, text, GithubValues.sha256(body)));
        }
        fragments.sort(Comparator.comparing(Fragment::name));
        String highest = highest(fragments);
        if (!covers(input.kind, highest)) throw new GithubException(GithubException.Code.INVALID_INPUT);
        List<Map<String, Object>> proposed = fragments.stream().map(fragment -> Map.<String, Object>of(
                "name", fragment.name, "kind", fragment.kind, "text", fragment.text, "sha256", fragment.sha256)).toList();
        Map<String, Object> output = new LinkedHashMap<>(); output.put("version", "github.release-prepare.result.v1");
        output.put("status", "prepared"); output.put("sourceCommit", input.commit); output.put("currentVersion", current.text());
        output.put("nextVersion", next.text()); output.put("tag", "v" + next.text()); output.put("releaseKind", input.kind);
        output.put("highestFragmentKind", highest); output.put("fragments", proposed);
        output.put("sourceVersionSha256", GithubValues.sha256(versionBytes));
        output.put("generation", 0L); output.put("attempts", 0L); output.put("remoteId", input.commit);
        requireHead(api, profile, input.commit);
        return NodeResult.continueWith(Map.copyOf(output));
    }

    private static void requireHead(GithubApi api, GithubProfile profile, String commit) {
        Map<String, Object> branch = GithubProtocol.object(api.get(profile.repositoryPath() + "/git/ref/heads/"
                + encodePath(profile.release().branch())));
        if (!commit.equals(GithubValues.object(branch.get("object")).get("sha")))
            throw new GithubException(GithubException.Code.FORBIDDEN);
    }

    private static Semver projectVersion(byte[] pom) {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            XMLStreamReader reader = factory.createXMLStreamReader(new java.io.ByteArrayInputStream(pom), "UTF-8");
            int depth = 0; boolean project = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1) project = "project".equals(reader.getLocalName());
                    else if (project && depth == 2 && "version".equals(reader.getLocalName())) {
                        String value = reader.getElementText(); reader.close(); return Semver.parse(value);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
            }
            reader.close(); throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        } catch (GithubException failure) { throw failure; }
        catch (Exception invalid) { throw new GithubException(GithubException.Code.RESPONSE_INVALID); }
    }

    private static byte[] content(GithubApi api, GithubProfile profile, String path, String ref, int maximum) {
        Map<String, Object> response = GithubProtocol.object(api.get(profile.repositoryPath() + "/contents/"
                + encodePath(path) + "?ref=" + ref));
        if (!"base64".equals(response.get("encoding"))) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        String encoded = GithubValues.string(response.get("content"), maximum * 2);
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(encoded); if (bytes.length > maximum) throw GithubValues.invalid(); return bytes;
        } catch (IllegalArgumentException invalid) { throw new GithubException(GithubException.Code.RESPONSE_INVALID); }
    }

    private static String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/")).map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")).collect(java.util.stream.Collectors.joining("/"));
    }
    private static String fragmentKind(String name) {
        for (String kind : List.of("breaking", "feature", "security", "fix", "other", "docs"))
            if (name.endsWith("." + kind + ".md")) return kind;
        return "";
    }
    private static String listedPath(String directory, String name, String value) {
        if (name.contains("/") || name.contains("\\") || value.contains("\\") || value.contains("//")
                || value.startsWith("/") || value.endsWith("/") || !value.equals(directory + "/" + name))
            throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        try {
            if (!java.nio.file.Path.of(value).normalize().toString().replace('\\', '/').equals(value))
                throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        }
        return value;
    }
    private static String highest(List<Fragment> fragments) {
        return fragments.stream().map(Fragment::kind).max(Comparator.comparingInt(ReleasePrepareBehavior::rank)).orElse("none");
    }
    private static boolean covers(String selected, String required) { return rank(selected) >= rank(required); }
    private static int rank(String kind) {
        return switch (kind) { case "major", "breaking" -> 4; case "minor", "feature" -> 3;
            case "patch", "security", "fix" -> 2; case "other", "docs", "none" -> 1; default -> 0; };
    }
    private static GithubException sanitize(RuntimeException failure) {
        return failure instanceof GithubException safe ? safe : new GithubException(GithubException.Code.INVALID_INPUT);
    }
    private record Fragment(String name, String kind, String text, String sha256) { }
    private record Semver(long major, long minor, long patch, String suffix) {
        static Semver parse(String value) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                    "([0-9]+)\\.([0-9]+)\\.([0-9]+)(-[0-9A-Za-z.-]+)?").matcher(value.strip());
            if (!match.matches()) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
            return new Semver(Long.parseLong(match.group(1)), Long.parseLong(match.group(2)),
                    Long.parseLong(match.group(3)), match.group(4) == null ? "" : match.group(4));
        }
        Semver bump(String kind) { return switch (kind) {
            case "major" -> new Semver(major + 1, 0, 0, suffix);
            case "minor" -> new Semver(major, minor + 1, 0, suffix);
            case "patch" -> new Semver(major, minor, patch + 1, suffix);
            case "none" -> this; default -> throw GithubValues.invalid(); };
        }
        String text() { return major + "." + minor + "." + patch + suffix; }
    }
    private record Input(String commit, String kind, String correlationId) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "commit", "releaseKind", "correlationId"));
            if (!"github.release-prepare.v1".equals(value.get("version"))) throw GithubValues.invalid();
            String commit = GithubValues.string(value.get("commit"), 40);
            if (!commit.matches("[0-9a-f]{40}")) throw GithubValues.invalid();
            String kind = GithubValues.string(value.get("releaseKind"), 16);
            if (!Set.of("none", "patch", "minor", "major").contains(kind)) throw GithubValues.invalid();
            return new Input(commit, kind, GithubValues.string(value.get("correlationId"), 128));
        }
        Map<String, Object> canonical() { return Map.of("version", "github.release-prepare.v1", "commit", commit,
                "releaseKind", kind, "correlationId", correlationId); }
    }
}
