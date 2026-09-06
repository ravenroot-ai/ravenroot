package ai.ravenroot.extensions.all;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.runtime.BehaviorPropertySchema;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.api.publication.PublicationAuditSink;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.extensions.ai.AiNodePackage;
import ai.ravenroot.extensions.amqp091.AmqpNodePackage;
import ai.ravenroot.extensions.discord.DiscordNodePackage;
import ai.ravenroot.extensions.filesystem.FilesystemNodePackage;
import ai.ravenroot.extensions.gitworkspace.GitWorkspaceNodePackage;
import ai.ravenroot.extensions.github.GithubNodePackage;
import ai.ravenroot.extensions.jdbc.JdbcNodePackage;
import ai.ravenroot.extensions.kafka.KafkaNodePackage;
import ai.ravenroot.extensions.mail.MailNodePackage;
import ai.ravenroot.extensions.ocr.OcrNodePackage;
import ai.ravenroot.extensions.openapi.client.OpenApiClientNodePackage;
import ai.ravenroot.extensions.openapi.server.OpenApiServerNodePackage;
import ai.ravenroot.extensions.slack.SlackNodePackage;
import ai.ravenroot.extensions.spel.SpelNodePackage;
import ai.ravenroot.extensions.storage.StorageNodePackage;
import ai.ravenroot.extensions.telegram.TelegramNodePackage;
import ai.ravenroot.extensions.websocket.WebSocketNodePackage;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-backed publication gate for the public 53-node catalog and its admission-ready examples. */
final class PublishedNodeContractTest {
    private static final String UPDATE_PROPERTY = "ravenroot.docs.update";
    private static final Path REPOSITORY = repositoryRoot();
    private static final Path SNAPSHOT = REPOSITORY.resolve("docs/reference/node-descriptor-contracts.tsv");
    private static final Path EXAMPLES = REPOSITORY.resolve("docs/examples/nodes");

    @Test
    void publishedDescriptorSnapshotMatchesRuntimeCatalog() throws Exception {
        List<NodeTypeDescriptor> descriptors = descriptors();
        assertEquals(53, descriptors.size(), "the documented baseline must classify every supported node");
        String actual = snapshot(descriptors);
        update(SNAPSHOT, actual);
        assertEquals(Files.readString(SNAPSHOT), actual,
                "descriptor contract drifted; review it, then regenerate with -D" + UPDATE_PROPERTY + "=true");
    }

    @Test
    void everyPublishedExampleIsParsedAndSchemaCheckedAgainstItsRuntimeDescriptor() throws Exception {
        BehaviorRegistry registry = registry();
        Set<String> expected = registry.descriptors().stream().map(NodeTypeDescriptor::behavior)
                .collect(java.util.stream.Collectors.toSet());
        for (NodeTypeDescriptor descriptor : registry.descriptors()) {
            Path example = EXAMPLES.resolve(fileName(descriptor.behavior()));
            update(example, graphMl(descriptor));
            try (var input = Files.newInputStream(example); var graph = GraphManager.readGraphMl(input)) {
                new BehaviorPropertySchema(registry).validate(graph.definition());
                assertEquals(descriptor.behavior(), graph.definition().node("action").behavior());
                assertEquals(1, graph.definition().failureEdges("action").size(),
                        descriptor.behavior() + " must demonstrate one failure route");
                Set<String> routed = graph.definition().edges().stream()
                        .filter(edge -> edge.source().equals("action"))
                        .filter(edge -> !graph.definition().failureEdges("action").contains(edge))
                        .map(edge -> edge.outcome()).collect(java.util.stream.Collectors.toSet());
                assertEquals(exampleOutcomes(descriptor), routed,
                        descriptor.behavior() + " must route every descriptor-declared outcome");
            }
        }
        try (var files = Files.list(EXAMPLES)) {
            Set<String> published = files.filter(path -> path.getFileName().toString().endsWith(".graphml"))
                    .map(path -> path.getFileName().toString().replace(".graphml", ""))
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(expected, published, "examples must be exact: no missing or orphan node files");
        }
    }

    @Test
    void descriptorMutationChangesTheComparedContract() {
        List<NodeTypeDescriptor> descriptors = descriptors();
        NodeTypeDescriptor first = descriptors.get(0);
        var extra = NodePropertyDescriptor.optional("reviewMutation", "Review mutation",
                NodePropertyType.STRING, "test-only mutation", "");
        var properties = new ArrayList<>(first.properties());
        properties.add(extra);
        var mutated = new NodeTypeDescriptor(first.behavior(), first.displayName(), first.category(),
                first.description(), first.visualType(), first.agentic(), properties, first.capabilities(),
                first.defaultNature(), first.allowedNatures(), first.commands(), first.outcomes(),
                first.runtimeConcurrency());
        var changed = new ArrayList<>(descriptors);
        changed.set(0, mutated);
        assertTrue(snapshot(changed).contains("reviewMutation"));
        assertTrue(!snapshot(descriptors).equals(snapshot(changed)),
                "an added property must fail the same snapshot comparison used by the publication gate");
    }

    @Test
    void sensitiveOpenApiAndTelegramFieldShapesArePartOfTheRuntimeComparison() {
        List<NodeTypeDescriptor> descriptors = descriptors();
        NodeTypeDescriptor openapi = descriptors.stream()
                .filter(value -> value.behavior().equals("openapi.request-reply")).findFirst().orElseThrow();
        assertTrue(openapi.properties().stream().anyMatch(value -> value.name().equals("maxResponseBytes")));
        NodeTypeDescriptor telegramDelete = descriptors.stream()
                .filter(value -> value.behavior().equals("telegram.delete.message")).findFirst().orElseThrow();
        assertTrue(telegramDelete.properties().stream().noneMatch(value -> value.name().equals("maxButtons")));

        var reduced = new ArrayList<>(openapi.properties());
        reduced.removeIf(value -> value.name().equals("maxResponseBytes"));
        var mutated = copyWithProperties(openapi, reduced);
        var changed = new ArrayList<>(descriptors);
        changed.set(changed.indexOf(openapi), mutated);
        assertTrue(!snapshot(descriptors).equals(snapshot(changed)),
                "removing the OpenAPI response ceiling must fail the compiled-descriptor snapshot gate");
    }

    @Test
    void malformedPublishedGraphMutationIsRefusedByTheRealParser() {
        String valid = graphMl(descriptors().get(0));
        String dangling = valid.replace("target=\"end\"", "target=\"missing\"");
        assertThrows(RuntimeException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(dangling.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void humanTaskDescriptorClassifiesConfirmationAdmissionVariants() {
        NodeTypeDescriptor incapable = BehaviorRegistry.standard().descriptor("human-task").orElseThrow();
        NodeTypeDescriptor capable = registry().descriptor("human-task").orElseThrow();
        assertTrue(incapable.properties().stream().noneMatch(p -> p.name().startsWith("confirmation")));
        assertTrue(capable.properties().stream().anyMatch(
                p -> p.name().equals("confirmationPresentationVersion")));
        assertTrue(capable.capabilities().contains("embedded-confirmation-v1"));
    }

    private static BehaviorRegistry registry() {
        List<NodePackage> packages = packages();
        NodePackageServices unavailable = NodePackageServices.unavailable();
        NodePackageServices declared = new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() {
                return Set.of(NodePackageCapability.values());
            }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return unavailable.credentials();
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
                return unavailable.outboundHttp();
            }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return unavailable.outboundWebSocket();
            }
        };
        var grants = NodePackageServiceRegistry.builder();
        packages.forEach(nodePackage -> grants.grant(nodePackage.id(), declared));
        try {
            Path storeFile = Files.createTempFile("ravenroot-doc-catalog-", ".db");
            storeFile.toFile().deleteOnExit();
            try (var store = new SqliteExecutionStore(storeFile, Clock.systemUTC())) {
                var tasks = new HumanTaskService(store, Clock.systemUTC());
                BehaviorRegistry core = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                        PublicationPolicyResolver.none(), PublicationAuditSink.noop(), tasks);
                return NodePackages.registerAll(core, packages, grants.build());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("cannot assemble production descriptor variants", failure);
        }
    }

    private static List<NodeTypeDescriptor> descriptors() {
        return registry().descriptors().stream().sorted(Comparator.comparing(NodeTypeDescriptor::behavior)).toList();
    }

    private static List<NodePackage> packages() {
        return List.of(new AiNodePackage(), new AmqpNodePackage(), new DiscordNodePackage(),
                new FilesystemNodePackage(), new GitWorkspaceNodePackage(), new GithubNodePackage(),
                new JdbcNodePackage(), new KafkaNodePackage(), new MailNodePackage(), new StorageNodePackage(),
                new OcrNodePackage(), new OpenApiClientNodePackage(), new OpenApiServerNodePackage(),
                new SlackNodePackage(), new SpelNodePackage(), new TelegramNodePackage(),
                new WebSocketNodePackage());
    }

    private static String snapshot(List<NodeTypeDescriptor> descriptors) {
        StringBuilder out = new StringBuilder("behavior\tproperty\ttype\trequired\tdefault\tallowed\tadapterBinding\tvisibleWhen\trequiredWhen\tminimum\tmaximum\tmaximumUtf8Bytes\tmaximumItems\tmaximumItemUtf8Bytes\tdescriptorOutcomes\n");
        for (NodeTypeDescriptor descriptor : descriptors.stream()
                .sorted(Comparator.comparing(NodeTypeDescriptor::behavior)).toList()) {
            String outcomes = descriptor.outcomes().stream()
                    .map(value -> value.parameterized() ? "$" + value.fromProperty() : value.name())
                    .collect(java.util.stream.Collectors.joining(","));
            List<NodePropertyDescriptor> properties = descriptor.properties().isEmpty()
                    ? List.of((NodePropertyDescriptor) null) : descriptor.properties();
            for (NodePropertyDescriptor property : properties) {
                out.append(cell(descriptor.behavior())).append('\t');
                if (property == null) {
                    out.append("—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t");
                } else {
                    out.append(cell(property.name())).append('\t').append(property.type()).append('\t')
                            .append(property.required()).append('\t').append(cell(property.defaultValue())).append('\t')
                            .append(cell(String.join(",", property.allowedValues()))).append('\t')
                            .append(property.adapterBinding()).append('\t')
                            .append(cell(condition(property.visibleWhen()))).append('\t')
                            .append(cell(condition(property.requiredWhen()))).append('\t')
                            .append(cell(property.minimumValue())).append('\t').append(cell(property.maximumValue())).append('\t')
                            .append(property.maximumUtf8Bytes()).append('\t').append(property.maximumItems()).append('\t')
                            .append(property.maximumItemUtf8Bytes()).append('\t');
                }
                out.append(cell(outcomes.isBlank() ? "—" : outcomes)).append('\n');
            }
        }
        return out.toString();
    }

    private static NodeTypeDescriptor copyWithProperties(NodeTypeDescriptor descriptor,
                                                           List<NodePropertyDescriptor> properties) {
        return new NodeTypeDescriptor(descriptor.behavior(), descriptor.displayName(), descriptor.category(),
                descriptor.description(), descriptor.visualType(), descriptor.agentic(), properties,
                descriptor.capabilities(), descriptor.defaultNature(), descriptor.allowedNatures(),
                descriptor.commands(), descriptor.outcomes(), descriptor.runtimeConcurrency());
    }

    private static String condition(ai.ravenroot.api.catalog.PropertyCondition condition) {
        return condition == null ? "" : condition.property() + ":" + condition.operator() + ":"
                + String.join(",", condition.values());
    }

    private static String graphMl(NodeTypeDescriptor descriptor) {
        Map<String, String> values = descriptor.properties().stream()
                .filter(PublishedNodeContractTest::includeInExample)
                .collect(java.util.stream.Collectors.toMap(
                NodePropertyDescriptor::name, property -> exampleValue(descriptor.behavior(), property),
                (left, right) -> left, java.util.LinkedHashMap::new));
        StringBuilder keys = new StringBuilder();
        StringBuilder data = new StringBuilder();
        for (var entry : values.entrySet()) {
            keys.append("  <key id=\"property-").append(xml(entry.getKey())).append("\" for=\"node\" attr.name=\"")
                    .append(xml(entry.getKey())).append("\" attr.type=\"string\"/>\n");
            data.append("      <data key=\"property-").append(xml(entry.getKey())).append("\">")
                    .append(xml(entry.getValue())).append("</data>\n");
        }
        StringBuilder edges = new StringBuilder();
        int index = 0;
        for (String outcome : exampleOutcomes(descriptor)) {
            edges.append("    <edge id=\"action-end-").append(index++).append("\" source=\"action\" target=\"end\"><data key=\"outcome\">")
                    .append(xml(outcome)).append("</data></edge>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!-- Admission-ready " + xml(descriptor.behavior()) + " graph. Supply an input payload at execution.\n"
                + "     Replace any replace-with-operator-profile value with a configured profile before Run.\n"
                + "     Success reaches end through the documented descriptor outcome; failure reaches error. -->\n"
                + "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n"
                + "  <key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/>\n"
                + "  <key id=\"behavior\" for=\"node\" attr.name=\"behavior\" attr.type=\"string\"/>\n"
                + "  <key id=\"outcome\" for=\"edge\" attr.name=\"outcome\" attr.type=\"string\"/>\n"
                + keys + "  <graph id=\"example-" + xml(descriptor.behavior()) + "\" edgedefault=\"directed\">\n"
                + "    <node id=\"start\"><data key=\"kind\">START</data></node>\n"
                + "    <node id=\"action\">\n      <data key=\"kind\">BEHAVIOR</data>\n"
                + "      <data key=\"behavior\">" + xml(descriptor.behavior()) + "</data>\n" + data + "    </node>\n"
                + "    <node id=\"end\"><data key=\"kind\">END</data></node>\n"
                + "    <node id=\"error\"><data key=\"kind\">ERROR</data></node>\n"
                + "    <edge id=\"start-action\" source=\"start\" target=\"action\"><data key=\"outcome\">continue</data></edge>\n"
                + edges + "    <edge id=\"action-error\" source=\"action\" target=\"error\"/>\n"
                + "  </graph>\n</graphml>\n";
    }

    private static Set<String> exampleOutcomes(NodeTypeDescriptor descriptor) {
        Map<String, String> values = descriptor.properties().stream().collect(java.util.stream.Collectors.toMap(
                NodePropertyDescriptor::name, property -> exampleValue(descriptor.behavior(), property)));
        Set<String> declared = descriptor.resolveOutcomes(values::get);
        return declared.isEmpty() ? Set.of("continue") : declared;
    }

    private static boolean includeInExample(NodePropertyDescriptor property) {
        return property.required();
    }

    private static String exampleValue(String behavior, NodePropertyDescriptor property) {
        if (!property.allowedValues().isEmpty()) return property.allowedValues().get(0);
        if (!property.defaultValue().isBlank()) return property.defaultValue();
        if (!property.minimumValue().isBlank()) return property.minimumValue();
        if (property.adapterBinding() || property.name().equals("profile")
                || property.name().toLowerCase().endsWith("profile")) {
            return "replace-with-operator-profile";
        }
        if (property.name().equals("statement")) return behavior.equals("jdbc.insert")
                ? "INSERT INTO example_table(value) VALUES ('example')" : "SELECT 1";
        if (property.name().equals("path")) return behavior.equals("json-path") ? "$" : "example.txt";
        if (property.name().equals("url")) return "https://example.com/health";
        if (property.name().equals("policyId")) return "example-policy";
        if (property.name().equals("policyVersion")) return "1";
        if (property.name().equals("policyDigest")) return "sha256:replace-with-policy-digest";
        if (property.name().equals("language")) return behavior.equals("ocr.extract") ? "eng" : "python";
        if (property.name().equals("source")) return "def main(payload): return payload";
        if (property.name().equals("operationId")) return "healthCheck";
        if (property.name().equals("expression")) return "payload != null";
        if (property.name().equals("title")) return "Review request";
        if (property.name().equals("prompt")) return "Summarize the supplied payload.";
        if (property.name().equals("instructions")) return "Use only the configured tools.";
        if (property.name().equals("objective")) return "Summarize the supplied payload.";
        return switch (property.type()) {
            case BOOLEAN -> "true";
            case INTEGER -> "1";
            case DECIMAL -> "1.0";
            case URI -> "https://example.com/resource";
            case CEL_EXPRESSION -> "true";
            case SECRET_REFERENCE -> "example-credential";
            case TEXT, STRING -> property.name().equals("template") ? "Hello, {{payload}}" : "example-value";
        };
    }

    private static String fileName(String behavior) {
        return behavior.replaceAll("[^a-zA-Z0-9._-]", "-") + ".graphml";
    }

    private static String cell(Object value) {
        return String.valueOf(value == null ? "" : value).replace("\t", " ").replace("\n", " ");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void update(Path path, String content) throws Exception {
        if (!Boolean.getBoolean(UPDATE_PROPERTY)) return;
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) candidate = candidate.getParent();
        if (candidate == null) throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        return candidate;
    }
}
