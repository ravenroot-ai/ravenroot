package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The parsed, validated content of one {@code ravenroot-plugin.json} manifest (PLAT-12).
 *
 * <h2>JSON, not YAML</h2>
 * <p>Parsed with {@code ai.ravenroot.api.payload.PayloadJson} against
 * {@link PluginBundleLimits#MANIFEST}: strict RFC 8259, duplicate keys rejected rather than
 * last-wins (the exact hazard for a document a validator and a loader must agree on), canonical
 * output for a reproducible, hashable encoding. See the module's PLAT-12 design notes for why this
 * was chosen over a hand-written YAML subset: the codebase already has two hand-hardened parsers at
 * exactly this kind of trust boundary ({@code SecureGraphMlParser}, {@code PayloadJson} itself), and
 * a third would have been new, YAML-shaped attack surface for no capability a manifest needs.</p>
 *
 * <h2>Closed field set, and no recognised-but-unchecked members</h2>
 * <p>An unrecognised top-level key is refused rather than ignored: a field that is not in the schema
 * cannot later be misused, which is a stronger property than documenting that it is ignored. {@link
 * #REQUIRED_KEYS} must be present; {@link #OPTIONAL_RECOGNISED_KEYS} may be present and are fully
 * structurally validated when they are.</p>
 * <p><strong>{@code description}, {@code license}, {@code ravenrootVersionRange},
 * {@code operatorConfig}, {@code provenance} and {@code signature} are deliberately absent from both
 * sets, not merely unvalidated.</strong> A manifest that carries any of them is refused with {@link
 * PluginBundleException.Reason#UNKNOWN_FIELD}, the same rejection an actually-unknown key gets. A
 * field admitted into a closed set without structural validation is not inert just because nothing
 * reads it today — two of these six, {@code provenance} and {@code signature}, exist specifically so
 * that something eventually will and will draw a trust conclusion from it, and the day that consumer
 * lands it must be reading a field a validator actually checked, never one merely a schema knew the
 * name of. Each field is added to {@link #OPTIONAL_RECOGNISED_KEYS} in the same change that gives it
 * a real check — never before.</p>
 *
 * @param schemaVersion       the manifest schema version; only {@link #SCHEMA_VERSION} is accepted
 * @param id                  the bundle's identifier, matching {@code NodePackage.id()}
 * @param version             the bundle's own version, for diagnostics
 * @param sdkContract         the Node SDK contract this bundle was built against
 * @param nodePackageClasses  the fully-qualified {@code NodePackage} classes this bundle instantiates
 * @param behaviors           the node behavior names this bundle declares, for pre-flight collision
 *                            detection without loading a class
 * @param nodeCapabilities    the union of {@code NodeTypeDescriptor.capabilities()} across this
 *                            bundle's behaviors, when the manifest declares it; {@link
 *                            Optional#empty()} when the key is absent, which is <em>not</em> the
 *                            same as a declared empty list — see {@link #NODE_CAPABILITIES_KEY}
 * @param mainArtifact        the bundle's primary jar
 * @param dependencyArtifacts the bundle's private runtime dependencies, possibly empty
 */
public record PluginManifest(
        String schemaVersion,
        String id,
        String version,
        String sdkContract,
        List<String> nodePackageClasses,
        List<String> behaviors,
        Optional<List<String>> nodeCapabilities,
        PluginArtifact mainArtifact,
        List<PluginArtifact> dependencyArtifacts) {

    /**
     * The only manifest schema version this validator implements.
     *
     * <p><b>The schema version remains unchanged, deliberately.</b> {@link #NODE_CAPABILITIES_KEY} was added to
     * {@link #OPTIONAL_RECOGNISED_KEYS} rather than behind a version bump, which is the evolution
     * step this record's class javadoc already describes ("Each field is added to {@code
     * OPTIONAL_RECOGNISED_KEYS} in the same change that gives it a real check"). Bumping instead
     * would have invalidated every bundle already built — a closed field set makes any newly
     * recognised key forward-incompatible for an older reader either way, and the version number
     * would only change which rejection that older reader reports, at the cost of refusing every
     * current bundle to the current reader.</p>
     */
    public static final String SCHEMA_VERSION = "1";

    /**
     * The optional manifest key carrying node-type capabilities.
     *
     * <h2>Which "capability" this is</h2>
     * <p>The union of {@code NodeTypeDescriptor.capabilities()} over every behavior the bundle
     * declares — the same per-node-type vocabulary {@code SyntheticProvenance.GENERATIVE_CAPABILITIES}
     * reads ({@code "ai"}, {@code "agentic"}). It is deliberately <em>not</em> named
     * {@code capabilities}, because {@code NodePackageCapability} (a package-level permission such
     * as outbound HTTP) is a different axis that will need its own key; two things called
     * "capability" sharing one field name would fuse them by accident.</p>
     *
     * <h2>Absent is not empty</h2>
     * <p>A manifest that omits this key says nothing about its capabilities; one that declares
     * {@code []} says it has none. The distinction is load-bearing for {@link
     * GenerativeCapabilityScan}, which refuses a shipped bundle it cannot conclude about rather
     * than treating silence as a clean bill. This is why the record component is an {@link
     * Optional} and not a list that happens to be empty.</p>
     *
     * <h2>Self-declared, and read only where that is sound</h2>
     * <p>{@code PluginCli generate-manifest} derives the value from the freshly built {@code
     * NodePackage}'s own descriptors, so a bundle produced by this toolchain cannot misdeclare it
     * by accident. A hand-written manifest can. The one consumer today is a release-time check over
     * bundles this project itself builds and ships, where the toolchain is the author — see {@link
     * GenerativeCapabilityScan}'s javadoc for why that consumer is sound on a self-declared field
     * and what it would take for a runtime consumer not to be.</p>
     */
    public static final String NODE_CAPABILITIES_KEY = "nodeCapabilities";

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "schemaVersion", "id", "version", "sdkContract", "nodePackageClasses", "behaviors", "mainArtifact");

    /**
     * Optional top-level keys, each fully structurally validated. See the class javadoc for why
     * {@code description}, {@code license}, {@code ravenrootVersionRange}, {@code operatorConfig},
     * {@code provenance} and {@code signature} are refused rather than listed here unchecked —
     * {@link #NODE_CAPABILITIES_KEY} joins this set in the same change that gives it a consumer that
     * draws a conclusion from it ({@link GenerativeCapabilityScan}), never before.
     */
    private static final Set<String> OPTIONAL_RECOGNISED_KEYS =
            Set.of("dependencyArtifacts", NODE_CAPABILITIES_KEY);

    public PluginManifest {
        nodePackageClasses = List.copyOf(Objects.requireNonNull(nodePackageClasses, "nodePackageClasses"));
        behaviors = List.copyOf(Objects.requireNonNull(behaviors, "behaviors"));
        Objects.requireNonNull(nodeCapabilities, "nodeCapabilities");
        nodeCapabilities = nodeCapabilities.map(List::copyOf);
        dependencyArtifacts = List.copyOf(Objects.requireNonNull(dependencyArtifacts, "dependencyArtifacts"));
        Objects.requireNonNull(mainArtifact, "mainArtifact");
        var artifactNames = new LinkedHashSet<String>();
        artifactNames.add(mainArtifact.fileName());
        for (PluginArtifact dependency : dependencyArtifacts) {
            if (!artifactNames.add(dependency.fileName())) {
                throw PluginBundleRejection.duplicateArtifactName(dependency.fileName());
            }
        }
    }

    /**
     * Parses and validates a manifest document. Never loads a class; this is data inspection only.
     *
     * <p>JSON-level rejections from {@code PayloadJson} (malformed syntax, a duplicate key, a budget
     * exceeded) are re-thrown as {@link PluginBundleException} rather than left as {@link
     * PayloadException}, so this module's public surface exposes exactly one exception type. The
     * underlying payload {@code Reason} name is preserved as diagnostic detail, not discarded.</p>
     */
    public static PluginManifest read(byte[] utf8) {
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(utf8, PluginBundleLimits.MANIFEST);
        } catch (PayloadException jsonRejection) {
            throw PluginBundleRejection.malformedManifest("json", jsonRejection.reason().name());
        }
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw PluginBundleRejection.malformedManifest("root", "expected an object, found " + describe(parsed));
        }
        return fromMembers(root.entries());
    }

    public static PluginManifest read(String json) {
        return read(json.getBytes(StandardCharsets.UTF_8));
    }

    private static PluginManifest fromMembers(Map<String, PayloadValue> members) {
        for (String key : members.keySet()) {
            if (!REQUIRED_KEYS.contains(key) && !OPTIONAL_RECOGNISED_KEYS.contains(key)) {
                throw PluginBundleRejection.unknownField(key);
            }
        }

        String schemaVersion = text(members, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw PluginBundleRejection.unsupportedSchemaVersion(schemaVersion);
        }
        String id = text(members, "id");
        String version = text(members, "version");
        String sdkContract = text(members, "sdkContract");
        if (!NodeSdk.supports(sdkContract)) {
            throw PluginBundleRejection.unsupportedSdkContract(sdkContract);
        }
        List<String> nodePackageClasses = textList(members, "nodePackageClasses");
        if (nodePackageClasses.isEmpty()) {
            throw PluginBundleRejection.missingRequiredField("nodePackageClasses");
        }
        List<String> behaviors = textList(members, "behaviors");
        if (behaviors.isEmpty()) {
            throw PluginBundleRejection.missingRequiredField("behaviors");
        }
        // Absent stays absent: textList(...) would be indistinguishable from a declared [], and the
        // whole point of this key is that "declares no generative capability" and "says nothing"
        // are different answers to a release check.
        Optional<List<String>> nodeCapabilities = members.containsKey(NODE_CAPABILITIES_KEY)
                ? Optional.of(textList(members, NODE_CAPABILITIES_KEY))
                : Optional.empty();
        PluginArtifact mainArtifact = artifact(members, "mainArtifact");
        List<PluginArtifact> dependencyArtifacts = members.containsKey("dependencyArtifacts")
                ? artifactList(members, "dependencyArtifacts")
                : List.of();

        return new PluginManifest(schemaVersion, id, version, sdkContract, nodePackageClasses, behaviors,
                nodeCapabilities, mainArtifact, dependencyArtifacts);
    }

    private static String text(Map<String, PayloadValue> members, String key) {
        PayloadValue value = members.get(key);
        if (value == null) {
            throw PluginBundleRejection.missingRequiredField(key);
        }
        if (!(value instanceof PayloadValue.TextValue text)) {
            throw PluginBundleRejection.malformedManifest(key, "expected text, found " + describe(value));
        }
        if (text.value().isBlank()) {
            throw PluginBundleRejection.malformedManifest(key, "text must not be blank");
        }
        return text.value();
    }

    private static List<String> textList(Map<String, PayloadValue> members, String key) {
        PayloadValue value = members.get(key);
        if (value == null) {
            throw PluginBundleRejection.missingRequiredField(key);
        }
        if (!(value instanceof PayloadValue.ListValue list)) {
            throw PluginBundleRejection.malformedManifest(key, "expected a list, found " + describe(value));
        }
        var result = new ArrayList<String>(list.values().size());
        for (int index = 0; index < list.values().size(); index++) {
            PayloadValue element = list.values().get(index);
            if (!(element instanceof PayloadValue.TextValue text) || text.value().isBlank()) {
                throw PluginBundleRejection.malformedManifest(key,
                        "expected a list of non-blank text; element " + index + " is " + describe(element));
            }
            result.add(text.value());
        }
        return List.copyOf(result);
    }

    private static PluginArtifact artifact(Map<String, PayloadValue> members, String key) {
        PayloadValue value = members.get(key);
        if (value == null) {
            throw PluginBundleRejection.missingRequiredField(key);
        }
        if (!(value instanceof PayloadValue.MapValue map)) {
            throw PluginBundleRejection.malformedManifest(key, "expected an object, found " + describe(value));
        }
        return artifactFromMembers(map.entries());
    }

    private static List<PluginArtifact> artifactList(Map<String, PayloadValue> members, String key) {
        PayloadValue value = members.get(key);
        if (!(value instanceof PayloadValue.ListValue list)) {
            throw PluginBundleRejection.malformedManifest(key, "expected a list, found " + describe(value));
        }
        var result = new ArrayList<PluginArtifact>(list.values().size());
        for (int index = 0; index < list.values().size(); index++) {
            PayloadValue element = list.values().get(index);
            if (!(element instanceof PayloadValue.MapValue map)) {
                throw PluginBundleRejection.malformedManifest(key,
                        "expected a list of objects; element " + index + " is " + describe(element));
            }
            result.add(artifactFromMembers(map.entries()));
        }
        return List.copyOf(result);
    }

    private static PluginArtifact artifactFromMembers(Map<String, PayloadValue> members) {
        for (String key : members.keySet()) {
            if (!Set.of("fileName", "sha256", "sizeBytes").contains(key)) {
                throw PluginBundleRejection.unknownField(key);
            }
        }
        String fileName = text(members, "fileName");
        String sha256 = text(members, "sha256");
        PayloadValue sizeValue = members.get("sizeBytes");
        if (sizeValue == null) {
            throw PluginBundleRejection.missingRequiredField("sizeBytes");
        }
        if (!(sizeValue instanceof PayloadValue.IntegerValue size)) {
            throw PluginBundleRejection.malformedManifest("sizeBytes",
                    "expected an integer, found " + describe(sizeValue));
        }
        return new PluginArtifact(fileName, sha256, size.value());
    }

    /**
     * A short, fixed-vocabulary description of {@code value}'s runtime shape — never its content.
     * {@code PayloadValue.kind()} alone only distinguishes list/map/scalar, which is not specific
     * enough to be actionable (every scalar looks the same); this switches over the sealed hierarchy
     * for the shape a manifest author actually needs to hear (text vs. integer vs. boolean vs. null,
     * not just "scalar").
     */
    private static String describe(PayloadValue value) {
        return switch (value) {
            case PayloadValue.NullValue ignored -> "null";
            case PayloadValue.BooleanValue ignored -> "a boolean";
            case PayloadValue.IntegerValue ignored -> "an integer";
            case PayloadValue.DecimalValue ignored -> "a decimal number";
            case PayloadValue.TextValue ignored -> "text";
            case PayloadValue.ListValue ignored -> "a list";
            case PayloadValue.MapValue ignored -> "an object";
        };
    }
}
