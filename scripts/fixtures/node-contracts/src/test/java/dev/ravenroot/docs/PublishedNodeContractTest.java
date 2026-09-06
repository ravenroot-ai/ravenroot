package dev.ravenroot.docs;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeRuntimeConcurrency;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorPropertySchema;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
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
import ai.ravenroot.server.plugin.EnvironmentNodePackageServiceGrants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-backed publication gate for the public 53-node catalog and its admission-ready examples. */
final class PublishedNodeContractTest {
    private static final String UPDATE_PROPERTY = "ravenroot.docs.update";
    private static final Path REPOSITORY = repositoryRoot();
    private static final Path SNAPSHOT = REPOSITORY.resolve("docs/reference/node-descriptor-contracts.tsv");
    private static final Path EXAMPLES = REPOSITORY.resolve("docs/examples/nodes");
    private static final Path PLUGIN_GUIDE = REPOSITORY.resolve("docs/operator-guide/plugin-bundles.md");

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
    void propertyPresentationMutationsChangeTheComparedContract() {
        List<NodeTypeDescriptor> descriptors = descriptors();
        NodeTypeDescriptor first = descriptors.get(0);
        NodePropertyDescriptor property = first.properties().get(0);

        var labelMutation = replaceProperty(first, copyProperty(property,
                "Documentation label mutation", property.description()));
        assertTrue(!snapshot(descriptors).equals(snapshot(replaceDescriptor(descriptors, first, labelMutation))),
                "a property display-name change must fail the descriptor snapshot gate");

        var helpMutation = replaceProperty(first, copyProperty(property,
                property.displayName(), "Documentation help mutation"));
        assertTrue(!snapshot(descriptors).equals(snapshot(replaceDescriptor(descriptors, first, helpMutation))),
                "a property description change must fail the descriptor snapshot gate");
    }

    @Test
    void nonPropertyAndRuntimeConcurrencyMutationsChangeTheComparedContract() {
        List<NodeTypeDescriptor> descriptors = descriptors();
        NodeTypeDescriptor first = descriptors.get(0);
        var capabilities = new java.util.TreeSet<>(first.capabilities());
        capabilities.add("documentation-mutation");
        var changedCapability = new NodeTypeDescriptor(first.behavior(), first.displayName(), first.category(),
                first.description(), first.visualType(), first.agentic(), first.properties(), capabilities,
                first.defaultNature(), first.allowedNatures(), first.commands(), first.outcomes(),
                first.runtimeConcurrency());
        var capabilityMutation = new ArrayList<>(descriptors);
        capabilityMutation.set(0, changedCapability);
        assertTrue(!snapshot(descriptors).equals(snapshot(capabilityMutation)),
                "a non-property capability change must fail the descriptor snapshot gate");

        var changedConcurrency = new NodeTypeDescriptor(first.behavior(), first.displayName(), first.category(),
                first.description(), first.visualType(), first.agentic(), first.properties(), first.capabilities(),
                first.defaultNature(), first.allowedNatures(), first.commands(), first.outcomes(),
                new NodeRuntimeConcurrency(3, 5));
        var concurrencyMutation = new ArrayList<>(descriptors);
        concurrencyMutation.set(0, changedConcurrency);
        assertTrue(!snapshot(descriptors).equals(snapshot(concurrencyMutation)),
                "a runtime-concurrency change must fail the descriptor snapshot gate");
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

    @Test
    void jdbcExamplesCrossTheBehaviorIdentifierBoundary() throws Exception {
        JdbcNodePackage jdbc = new JdbcNodePackage();
        for (Map.Entry<String, String> expected : Map.of(
                "jdbc.query", "find-user", "jdbc.insert", "add-user").entrySet()) {
            GraphNode node = exampleAction(expected.getKey());
            assertEquals(expected.getValue(), node.properties().get("statement"));
            NodeBehavior behavior = jdbc.behaviors().stream()
                    .filter(candidate -> candidate.descriptor().behavior().equals(expected.getKey()))
                    .findFirst().orElseThrow();
            NodeConfiguration configuration = new NodeConfiguration(node.id(), node.behavior(), node.properties());
            assertDoesNotThrow(() -> behavior.create(configuration),
                    expected.getKey() + " example must pass behavior-level identifier validation");
            RuntimeException rawSql = assertThrows(RuntimeException.class, () -> behavior.create(
                    new NodeConfiguration("raw-sql", expected.getKey(), Map.of(
                            "profile", "operator-profile", "statement", "SELECT 1"))));
            assertEquals("JDBC_PROFILE_UNAVAILABLE", rawSql.getMessage());
        }
    }

    @Test
    void packageServiceGuideMatchesBehaviorRequirementsAndGrantReaderSchema() throws Exception {
        String guide = Files.readString(PLUGIN_GUIDE);
        assertEquals(requiredServicesByPackage(), documentedRequiredServices(guide),
                "required-services table must be derived from every NodeBehavior.requiredServices() declaration");

        Map<String, Set<String>> schema = documentedGrantSchema(guide);
        assertEquals(privateSet("GRANT_KEYS"), schema.keySet(),
                "grant schema table must name every accepted top-level member and no stale member");
        assertEquals(privateSet("ORIGIN_KEYS"), schema.get("origins"));
        assertEquals(privateSet("CREDENTIAL_BINDING_KEYS"), schema.get("credentialBindings"));
        assertEquals(privateSet("SIGV4_BINDING_KEYS"), schema.get("awsSigV4Bindings"));
        assertEquals(privateSet("LIMIT_KEYS"), schema.get("limits"));

        NodePackageEgressPolicy defaults = NodePackageEgressPolicy.builder().build();
        Map<String, String> actualDefaults = Map.of(
                "maxRequestBytes", Long.toString(defaults.maximumRequestBytes()),
                "maxResponseBytes", Long.toString(defaults.maximumResponseBytes()),
                "maxWebSocketMessageBytes", Long.toString(defaults.maximumWebSocketMessageBytes()),
                "maxWebSocketFragments", Integer.toString(defaults.maximumWebSocketFragments()),
                "maxQueuedWebSocketSends", Integer.toString(defaults.maximumQueuedWebSocketSends()),
                "maxConcurrentOperations", Integer.toString(defaults.maximumConcurrentOperations()),
                "maxConcurrentPerTenant", Integer.toString(defaults.maximumConcurrentPerTenant()),
                "maxDeadlineMs", Long.toString(defaults.maximumDeadline().toMillis()),
                "maxWebSocketLifetimeMs", Long.toString(defaults.maximumWebSocketLifetime().toMillis()),
                "maxWebSocketIdleMs", Long.toString(defaults.maximumWebSocketIdle().toMillis()));
        assertEquals(actualDefaults, documentedGrantDefaults(guide));

        String rules = marked(guide, "node-package-egress-rules");
        for (String token : List.of("CONNECT", "TRACE", "https", "wss", "s3",
                "credentialReferences", "maxConcurrentOperations", "maxWebSocketLifetimeMs")) {
            assertTrue(rules.contains("`" + token + "`") || rules.contains(token),
                    "egress rule documentation is missing " + token);
        }
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .concurrencyLimits(1, 2).build());
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .webSocketLimits(1, 1, Duration.ofSeconds(1), Duration.ofSeconds(2)).build());
        assertThrows(IllegalArgumentException.class,
                () -> new NodePackageEgressPolicy.Origin("ftp", "example.com", 21));
        assertThrows(IllegalArgumentException.class,
                () -> new NodePackageEgressPolicy.Origin("https", "example.com", 0));
        assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressPolicy.builder().allowHttpMethod("CONNECT"));
        assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressPolicy.builder().allowHttpMethod("TRACE"));

        assertDoesNotThrow(() -> parseGrant("{\"capabilities\":[\"outbound-http\"]}"));
        assertThrows(RuntimeException.class, () -> parseGrant(
                "{\"capabilities\":[\"outbound-http\"],\"unknown\":[]}"));
        assertThrows(RuntimeException.class, () -> parseGrant(
                "{\"capabilities\":[\"outbound-http\"],\"credentialBindings\":[{"
                        + "\"bindingId\":\"api\",\"origin\":{\"scheme\":\"http\","
                        + "\"host\":\"example.com\",\"port\":80},\"headerName\":\"x-api-key\"}]}"));
        assertThrows(RuntimeException.class, () -> parseGrant(
                "{\"capabilities\":[\"outbound-http\"],\"awsSigV4Bindings\":[{"
                        + "\"bindingId\":\"sign\",\"origin\":{\"scheme\":\"https\","
                        + "\"host\":\"example.com\",\"port\":443},"
                        + "\"credentialReference\":\"key\",\"region\":\"eu-west-1\","
                        + "\"service\":\"execute-api\"}]}"));
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

    private static GraphNode exampleAction(String behavior) throws Exception {
        try (var input = Files.newInputStream(EXAMPLES.resolve(fileName(behavior)));
             var graph = GraphManager.readGraphMl(input)) {
            return graph.definition().node("action");
        }
    }

    private static Map<String, Set<String>> requiredServicesByPackage() {
        Map<String, Set<String>> actual = new java.util.TreeMap<>();
        for (NodePackage nodePackage : packages()) {
            Set<String> required = nodePackage.behaviors().stream()
                    .flatMap(behavior -> behavior.requiredServices().stream())
                    .map(NodePackageCapability::capabilityName)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            actual.put(nodePackage.id(), required);
        }
        return actual;
    }

    private static Map<String, Set<String>> documentedRequiredServices(String guide) {
        String table = marked(guide, "node-package-required-services");
        Pattern row = Pattern.compile("^\\| `([^`]+)` \\| (.*?) \\|$", Pattern.MULTILINE);
        Map<String, Set<String>> documented = new java.util.TreeMap<>();
        Matcher matcher = row.matcher(table);
        while (matcher.find()) {
            Set<String> capabilities = new java.util.TreeSet<>();
            Matcher token = Pattern.compile("`([^`]+)`").matcher(matcher.group(2));
            while (token.find()) capabilities.add(token.group(1));
            documented.put(matcher.group(1), capabilities);
        }
        return documented;
    }

    private static Map<String, Set<String>> documentedGrantSchema(String guide) {
        String table = marked(guide, "node-package-grant-schema");
        Pattern row = Pattern.compile("^\\| `([^`]+)` \\| (.*?) \\| .*? \\|$", Pattern.MULTILINE);
        Map<String, Set<String>> documented = new LinkedHashMap<>();
        Matcher matcher = row.matcher(table);
        while (matcher.find()) {
            Set<String> nested = new java.util.LinkedHashSet<>();
            Matcher token = Pattern.compile("`([^`]+)`").matcher(matcher.group(2));
            while (token.find()) nested.add(token.group(1));
            documented.put(matcher.group(1), Set.copyOf(nested));
        }
        return documented;
    }

    private static Map<String, String> documentedGrantDefaults(String guide) {
        String table = marked(guide, "node-package-grant-defaults");
        Pattern row = Pattern.compile("^\\| `([^`]+)` \\| `([^`]+)` \\| .*? \\|$", Pattern.MULTILINE);
        Map<String, String> documented = new LinkedHashMap<>();
        Matcher matcher = row.matcher(table);
        while (matcher.find()) documented.put(matcher.group(1), matcher.group(2));
        return documented;
    }

    private static void parseGrant(String json) {
        String variable = EnvironmentNodePackageServiceGrants.environmentVariableName(
                "ai.ravenroot.extensions.documentation-test");
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        EnvironmentNodePackageServiceGrants.fromEnvironment(Map.of(variable, encoded),
                (packageId, tenantId, reference) -> Optional.empty());
    }

    private static String marked(String guide, String name) {
        String start = "<!-- " + name + ":start -->";
        String end = "<!-- " + name + ":end -->";
        int from = guide.indexOf(start);
        int to = guide.indexOf(end);
        assertTrue(from >= 0 && to > from, "missing maintained guide markers for " + name);
        return guide.substring(from + start.length(), to);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> privateSet(String fieldName) throws Exception {
        Field field = EnvironmentNodePackageServiceGrants.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return Set.copyOf((Set<String>) field.get(null));
    }

    private static String snapshot(List<NodeTypeDescriptor> descriptors) {
        StringBuilder out = new StringBuilder("behavior\tdisplayName\tcategory\tdescription\tvisualType\tagentic\tcapabilities\tdefaultNature\tallowedNatures\tcommands\truntimeConcurrencyDefault\truntimeConcurrencyCeiling\tproperty\tpropertyDisplayName\tpropertyDescription\ttype\trequired\tdefault\tallowed\tadapterBinding\tvisibleWhen\trequiredWhen\tminimum\tmaximum\tmaximumUtf8Bytes\tmaximumItems\tmaximumItemUtf8Bytes\tdescriptorOutcomes\n");
        for (NodeTypeDescriptor descriptor : descriptors.stream()
                .sorted(Comparator.comparing(NodeTypeDescriptor::behavior)).toList()) {
            String outcomes = descriptor.outcomes().stream()
                    .map(value -> (value.parameterized() ? "$" + value.fromProperty() : value.name())
                            + ": " + value.description())
                    .collect(java.util.stream.Collectors.joining("; "));
            List<NodePropertyDescriptor> properties = descriptor.properties().isEmpty()
                    ? List.of((NodePropertyDescriptor) null) : descriptor.properties();
            for (NodePropertyDescriptor property : properties) {
                out.append(cell(descriptor.behavior())).append('\t')
                        .append(cell(descriptor.displayName())).append('\t')
                        .append(cell(descriptor.category())).append('\t')
                        .append(cell(descriptor.description())).append('\t')
                        .append(cell(descriptor.visualType())).append('\t')
                        .append(descriptor.agentic()).append('\t')
                        .append(cell(sorted(descriptor.capabilities()))).append('\t')
                        .append(cell(descriptor.defaultNature())).append('\t')
                        .append(cell(sorted(descriptor.allowedNatures()))).append('\t')
                        .append(cell(sorted(descriptor.commands()))).append('\t')
                        .append(descriptor.runtimeConcurrency().defaultValue()).append('\t')
                        .append(descriptor.runtimeConcurrency().ceiling()).append('\t');
                if (property == null) {
                    out.append("—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t—\t");
                } else {
                    out.append(cell(property.name())).append('\t')
                            .append(cell(property.displayName())).append('\t')
                            .append(cell(property.description())).append('\t')
                            .append(property.type()).append('\t')
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

    private static String sorted(Set<?> values) {
        return values.stream().map(String::valueOf).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static NodeTypeDescriptor copyWithProperties(NodeTypeDescriptor descriptor,
                                                           List<NodePropertyDescriptor> properties) {
        return new NodeTypeDescriptor(descriptor.behavior(), descriptor.displayName(), descriptor.category(),
                descriptor.description(), descriptor.visualType(), descriptor.agentic(), properties,
                descriptor.capabilities(), descriptor.defaultNature(), descriptor.allowedNatures(),
                descriptor.commands(), descriptor.outcomes(), descriptor.runtimeConcurrency());
    }

    private static NodeTypeDescriptor replaceProperty(NodeTypeDescriptor descriptor,
                                                        NodePropertyDescriptor replacement) {
        var properties = new ArrayList<>(descriptor.properties());
        properties.set(0, replacement);
        return copyWithProperties(descriptor, properties);
    }

    private static List<NodeTypeDescriptor> replaceDescriptor(List<NodeTypeDescriptor> descriptors,
                                                               NodeTypeDescriptor original,
                                                               NodeTypeDescriptor replacement) {
        var changed = new ArrayList<>(descriptors);
        changed.set(changed.indexOf(original), replacement);
        return changed;
    }

    private static NodePropertyDescriptor copyProperty(NodePropertyDescriptor property,
                                                        String displayName,
                                                        String description) {
        return new NodePropertyDescriptor(property.name(), displayName, property.type(), property.required(),
                description, property.defaultValue(), property.allowedValues(), property.adapterBinding(),
                property.visibleWhen(), property.requiredWhen(), property.minimumValue(), property.maximumValue(),
                property.maximumUtf8Bytes(), property.maximumItems(), property.maximumItemUtf8Bytes());
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
                ? "add-user" : "find-user";
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
        String configured = System.getProperty("ravenroot.repository", "");
        Path candidate = configured.isBlank()
                ? Path.of("").toAbsolutePath()
                : Path.of(configured).toAbsolutePath().normalize();
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) candidate = candidate.getParent();
        if (candidate == null) throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        return candidate;
    }
}
