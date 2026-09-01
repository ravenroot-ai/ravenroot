package ai.ravenroot.api.provenance;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The machine-readable provenance marker Ravenroot applies to content produced by a node the trusted
 * catalog declares generative (REG-03).
 *
 * <h2>What this is for</h2>
 * <p>EU AI Act art. 50(2) requires providers of generative systems to mark synthetic output in a
 * machine-readable form <em>and</em> to make it detectable as artificially generated. The two
 * elements are cumulative. Ravenroot is an upstream component rather than the obliged party, and
 * this type is compliance with neither element: it does not discharge an integrator's art. 50(2)
 * duty and must not be relied on as an upstream marking solution for that purpose.</p>
 *
 * <p>What it is instead: a runtime-internal record of which node produced which content value,
 * carried as an attribute between nodes so that graph-internal consumers can act on it. It is not
 * a mark in the content, it is not detectable by anything outside this runtime, it is not signed,
 * and it does not outlive the traversal — {@code GraphExecutionResult} carries the payload, not
 * the attributes. An integrator's art. 50(2) marking belongs at its own generation boundary,
 * inside its {@code ModelProvider} or {@code AgentRuntime} adapter. See
 * {@code docs/compliance/eu-ai-act-article-50-integrator-note.md}.</p>
 *
 * <p>It is deliberately defined before any {@code ModelProvider} exists, so the contract is not
 * retrofitted around one provider's shape.</p>
 *
 * <h2>Derived from the catalog, never from a node name</h2>
 * <p>A node is generative when its {@link NodeTypeDescriptor} declares a capability in
 * {@link #GENERATIVE_CAPABILITIES}. The descriptor comes from the registered behavior factory and
 * never from graph content, which is the same authority SEC-09 already relies on: a graph supplies
 * property <em>values</em>, it cannot claim a capability. A factory that declares {@code "ai"} is
 * therefore marked automatically, and renaming a node changes nothing.</p>
 *
 * <p><b>That is the only way a generative node reaches a running Ravenroot.</b> The core
 * ships none: {@code llm-prompt} and {@code agent} — which declared {@code "ai"} and {@code "agentic"}
 * respectively — left the catalog for a plugin bundle (ADR 0029). The rule below is unchanged by that
 * and was deliberately not narrowed with it: a bundle node declaring {@code "ai"} is marked by this
 * class without anything here learning the bundle exists.</p>
 *
 * <h2>Per content, not per execution</h2>
 * <p>This is the part a naive marker gets wrong. Attributes flow from {@code NodeResult} to the next
 * {@code NodeMessage}, and {@code NodeMessage.next(...)} carries them across a <strong>payload
 * substitution</strong>. A flag meaning "a generative node ran in this traversal" would therefore
 * ride the message onto content the model never produced — a false positive, which in a compliance
 * marker is worse than no marker, and which is exactly what a hostile graph would exploit.</p>
 *
 * <p>So the marker does not assert anything about the execution. It asserts provenance over
 * <strong>one specific content value</strong>, identified by {@link #CONTENT_DIGEST_FIELD}. The
 * runtime re-checks that binding at every hop: the marker survives exactly as far as the content it
 * describes survives unchanged, and any node that substitutes or transforms the payload silently
 * drops it. The marker is self-invalidating rather than guarded, so there is no filter to forget.</p>
 *
 * <h2>Under-marking is chosen over over-marking</h2>
 * <p>Content <em>derived</em> from generated content — a template that embeds the model's text, a
 * fan-in that merges a generated branch with a deterministic one — no longer matches the binding and
 * is not marked. That is a knowing trade: Ravenroot cannot verify that a derived value is still
 * substantially the model's output, and asserting that it is would be an unfounded claim. A missing
 * marker is a stated limitation; a wrong marker is a false statement about the world.</p>
 *
 * <h2>Not proposable by a node (SEC-07 / SEC-09)</h2>
 * <p>{@link #KEY_PREFIX} is inside the {@code ravenroot.} namespace that
 * {@code ReservedGraphProperties} already refuses at ingest, so no graph document can declare it. At
 * runtime the {@code GraphRunner} discards whatever a node returned under this key <em>unread</em>
 * and rewrites it from the descriptor, so no behavior implementation can forge, alter or suppress it
 * either. As with identity, non-overridability is a property of where the value is computed rather
 * than of anyone's discipline in maintaining a guard.</p>
 *
 * <h2>Shape</h2>
 * <p>The attribute value is a plain {@link Map} of {@link String} to {@code String}/{@code List} so
 * that it crosses any serializer, log line or transport unchanged without introducing a new
 * serialization contract. Readers must key off {@link #CONTRACT_FIELD} and ignore unknown fields.</p>
 */
public final class SyntheticProvenance {

    /**
     * Namespace owned by the runtime for provenance state. Reserved from graph content by
     * {@code ReservedGraphProperties}, which refuses the whole {@code ravenroot.} prefix at ingest.
     */
    public static final String KEY_PREFIX = "ravenroot.provenance.";

    /** The attribute under which the marker travels on {@code NodeMessage} and {@code NodeResult}. */
    public static final String ATTRIBUTE = KEY_PREFIX + "synthetic";

    /** Version tag of the marker shape. Readers must check it before interpreting other fields. */
    public static final String CONTRACT = "ravenroot.provenance/1";

    /**
     * Catalog capabilities that make a node type generative for marking purposes.
     *
     * <p>This is a list of <em>capabilities</em>, not of node names, which is what keeps the rule
     * open: a behavior that declares one of these is marked whoever wrote it. Note that
     * {@link NodeTypeDescriptor#agentic()} is deliberately not consulted — the departed
     * {@code llm-prompt} declared {@code agentic=false} while being the most generative node the core
     * ever shipped, so that flag describes control style rather than content provenance.</p>
     */
    public static final Set<String> GENERATIVE_CAPABILITIES = Set.of("ai", "agentic");

/** Field name carrying the versioned marker contract identifier. */
    public static final String CONTRACT_FIELD = "contract";
    /** The graph node whose execution produced the marked content. Audit only; never an authority. */
    public static final String NODE_ID_FIELD = "nodeId";
    /** The catalog behavior name of the producing node type. Audit only; never an authority. */
    public static final String BEHAVIOR_FIELD = "behavior";
    /** The generative capabilities that caused marking, sorted. Both the "what" and the "why". */
    public static final String CAPABILITIES_FIELD = "capabilities";
    /** {@code sha256:<hex>} of the marked content, or {@link #UNBOUND}. */
    public static final String CONTENT_DIGEST_FIELD = "contentDigest";

    /**
     * Digest value used when the content has no canonical form this runtime can compute.
     *
     * <p>Such a marker is still emitted — the node <em>was</em> generative and its immediate output
     * <em>is</em> synthetic — but it can never be shown to still describe a downstream payload, so it
     * does not propagate. Failing closed here under-marks; the alternative would be asserting
     * provenance over content the runtime cannot recognise.</p>
     */
    public static final String UNBOUND = "unbound";

    private static final String DIGEST_PREFIX = "sha256:";
    private static final int MAX_DEPTH = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SyntheticProvenance() {
    }

    /**
     * Whether {@code key} belongs to the runtime-owned provenance namespace. Case-insensitive.
     *
     * <p>Matched without allocating: this runs over every attribute of every node result, and
     * {@code trim()} returns the same instance when there is no padding while
     * {@link String#regionMatches(boolean, int, String, int, int)} folds case in place. Case and
     * padding are covered for the reason {@code ReservedGraphProperties} covers them — a key that
     * differs only in casing is a different key to a strict parser and the same key to every human
     * reading it.</p>
 * @param key attribute key to classify; {@code null} is not a provenance key.
 * @return {@code true} when the trimmed key uses the reserved provenance namespace.
     */
    public static boolean isProvenanceKey(String key) {
        if (key == null) {
            return false;
        }
        String trimmed = key.trim();
        return trimmed.regionMatches(true, 0, KEY_PREFIX, 0, KEY_PREFIX.length());
    }

/**
 * Whether the trusted catalog declares this node type generative.
 * @param descriptor catalog descriptor to inspect; {@code null} is not generative.
 * @return whether the descriptor declares at least one provenance-triggering capability.
 */
    public static boolean isGenerative(NodeTypeDescriptor descriptor) {
        return !generativeCapabilities(descriptor).isEmpty();
    }

/**
 * The generative capabilities {@code descriptor} declares, normalised and sorted.
 * @param descriptor catalog descriptor whose declared capabilities are normalized.
 * @return sorted immutable triggering capabilities, or an empty list when none apply.
 */
    public static List<String> generativeCapabilities(NodeTypeDescriptor descriptor) {
        return descriptor == null ? List.of() : generativeCapabilities(descriptor.capabilities());
    }

    /**
     * The generative capabilities among {@code declared}, normalised and sorted.
     *
     * <p>Same rule as {@link #generativeCapabilities(NodeTypeDescriptor)}, applied to capability
     * names that did <em>not</em> come from a live descriptor — the case a build-time or
     * release-time check faces, where the names were read out of a document (a plugin bundle's
     * {@code ravenroot-plugin.json} {@code nodeCapabilities} field) rather than obtained
     * from a registered behavior factory. The descriptor overload delegates here so the
     * normalisation rule — trim, lower-case in {@link Locale#ROOT}, match against {@link
     * #GENERATIVE_CAPABILITIES} — is written once and cannot drift between the runtime marker and
     * the checks that decide what may be shipped.</p>
     *
     * <p><b>This overload asserts nothing about trust.</b> A descriptor comes from the trusted
     * catalog; a list of strings comes from wherever its caller got it. Reading a self-declared
     * document through this method makes the two normalise identically, which is the point; it does
     * not make the document authoritative, and a caller that needs authority must still consult a
     * descriptor.</p>
     *
     * @param declared capability names to classify; {@code null} entries and {@code null} itself
     *                 contribute nothing.
     * @return sorted immutable triggering capabilities, or an empty list when none apply.
     */
    public static List<String> generativeCapabilities(Collection<String> declared) {
        if (declared == null) {
            return List.of();
        }
        var found = new TreeSet<String>();
        for (String capability : declared) {
            if (capability == null) {
                continue;
            }
            String normalised = capability.trim().toLowerCase(Locale.ROOT);
            if (GENERATIVE_CAPABILITIES.contains(normalised)) {
                found.add(normalised);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Mints a marker asserting that {@code content} was produced by {@code descriptor}'s node type.
     *
     * @return empty when {@code descriptor} is not generative, so the caller has no way to mark a
     *         node type the catalog never declared generative
 * @param nodeId graph node that produced the content; retained for audit only.
 * @param descriptor trusted catalog descriptor that authorizes the marker.
 * @param content immediate value produced by that node.
     */
    public static Optional<Map<String, Object>> mint(String nodeId, NodeTypeDescriptor descriptor, Object content) {
        List<String> capabilities = generativeCapabilities(descriptor);
        if (capabilities.isEmpty()) {
            return Optional.empty();
        }
        var marker = new LinkedHashMap<String, Object>();
        marker.put(CONTRACT_FIELD, CONTRACT);
        marker.put(NODE_ID_FIELD, nodeId == null ? "" : nodeId);
        marker.put(BEHAVIOR_FIELD, descriptor.behavior());
        marker.put(CAPABILITIES_FIELD, capabilities);
        marker.put(CONTENT_DIGEST_FIELD, bind(content).orElse(UNBOUND));
        return Optional.of(Map.copyOf(marker));
    }

    /**
     * Reads a well-formed marker out of an attribute map.
     *
     * <p>Anything that is not a map carrying this contract version and a string digest is not a
     * marker. Malformed values are ignored rather than repaired: a half-understood provenance claim
     * is not a provenance claim.</p>
 * @param attributes message attributes that may contain a provenance marker.
 * @return immutable marker fields when the reserved attribute has the current valid shape.
     */
    public static Optional<Map<String, Object>> read(Map<String, Object> attributes) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.get(ATTRIBUTE);
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        if (!CONTRACT.equals(map.get(CONTRACT_FIELD)) || !(map.get(CONTENT_DIGEST_FIELD) instanceof String)) {
            return Optional.empty();
        }
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
        return Optional.of(Map.copyOf(copy));
    }

    /**
     * Whether {@code marker} still describes {@code content}.
     *
     * <p>This is the whole propagation rule. An {@link #UNBOUND} marker describes nothing, and a
     * marker whose digest does not reproduce from {@code content} describes something that content no
     * longer is.</p>
 * @param marker candidate marker read from message attributes.
 * @param content current payload value to compare with the recorded digest.
 * @return whether the marker's bound digest still describes the supplied content.
     */
    public static boolean describes(Map<String, Object> marker, Object content) {
        if (marker == null || !(marker.get(CONTENT_DIGEST_FIELD) instanceof String digest)
                || UNBOUND.equals(digest)) {
            return false;
        }
        return bind(content).filter(digest::equals).isPresent();
    }

    /**
     * The content binding for {@code content}, or empty when it has no canonical form.
     *
     * <p>Canonicalisation covers null, text, numbers, booleans, characters, enums, {@link UUID},
     * byte arrays and arbitrarily nested maps, collections and arrays of those. Lists preserve
     * order, sets do not, and every scalar is tagged with its kind so that {@code "1"}, {@code 1} and
     * {@code 1L} are distinct bindings. Anything else — an application's own object, a stream, a
     * handle — has no value-based identity this runtime can rely on, so it is refused rather than
     * approximated through {@code toString()}, whose default is an identity hash.</p>
 * @param content value for which to construct a canonical, type-sensitive digest.
 * @return SHA-256 binding when the value has a supported canonical form; otherwise empty.
     */
    public static Optional<String> bind(Object content) {
        var canonical = new StringBuilder();
        if (!canonicalise(content, 0, canonical)) {
            return Optional.empty();
        }
        return Optional.of(DIGEST_PREFIX + sha256(canonical.toString()));
    }

    private static boolean canonicalise(Object value, int depth, StringBuilder out) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        switch (value) {
            case null -> {
                out.append("n;");
                return true;
            }
            case CharSequence text -> {
                out.append("s").append(text.length()).append(':').append(text).append(';');
                return true;
            }
            case Boolean flag -> {
                out.append("b").append(flag).append(';');
                return true;
            }
            case Character character -> {
                out.append("c").append((int) character.charValue()).append(';');
                return true;
            }
            case Number number -> {
                // Tagged by class so that an Integer and a Long of equal value are different
                // bindings. Failing closed on a widening is safe; conflating them is not.
                // BigDecimal is rendered plainly so that 1E+2 and 100 do not bind differently.
                out.append('#').append(number.getClass().getName()).append(':')
                        .append(number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString())
                        .append(';');
                return true;
            }
            case UUID id -> {
                out.append("u").append(id).append(';');
                return true;
            }
            case Enum<?> constant -> {
                out.append("e").append(constant.getDeclaringClass().getName()).append(':')
                        .append(constant.name()).append(';');
                return true;
            }
            case byte[] bytes -> {
                out.append("x").append(hex(bytes)).append(';');
                return true;
            }
            case Map<?, ?> map -> {
                return canonicaliseMap(map, depth, out);
            }
            case Set<?> set -> {
                return canonicaliseUnordered(set, depth, out);
            }
            case Object[] array -> {
                return canonicaliseOrdered(List.of(array), depth, out, 'a');
            }
            case Collection<?> collection -> {
                return canonicaliseOrdered(collection, depth, out, 'l');
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean canonicaliseOrdered(Collection<?> values, int depth, StringBuilder out, char tag) {
        out.append(tag).append(values.size()).append('[');
        for (Object element : values) {
            if (!canonicalise(element, depth + 1, out)) {
                return false;
            }
        }
        out.append("];");
        return true;
    }

    private static boolean canonicaliseUnordered(Collection<?> values, int depth, StringBuilder out) {
        var encoded = new ArrayList<String>(values.size());
        for (Object element : values) {
            var slot = new StringBuilder();
            if (!canonicalise(element, depth + 1, slot)) {
                return false;
            }
            encoded.add(slot.toString());
        }
        // A set has no order, so its binding must not acquire one from iteration order.
        encoded.sort(null);
        out.append('t').append(encoded.size()).append('[');
        encoded.forEach(out::append);
        out.append("];");
        return true;
    }

    private static boolean canonicaliseMap(Map<?, ?> map, int depth, StringBuilder out) {
        var encoded = new ArrayList<String>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            var slot = new StringBuilder();
            if (!canonicalise(entry.getKey(), depth + 1, slot)) {
                return false;
            }
            slot.append('=');
            if (!canonicalise(entry.getValue(), depth + 1, slot)) {
                return false;
            }
            encoded.add(slot.toString());
        }
        encoded.sort(null);
        out.append('m').append(encoded.size()).append('{');
        encoded.forEach(out::append);
        out.append("};");
        return true;
    }

    private static String sha256(String canonical) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every conforming Java platform ships SHA-256; there is no sane fallback that is still a
            // binding, so this is a broken runtime rather than a condition to degrade through.
            throw new IllegalStateException("SHA-256 is required to bind provenance to content", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(HEX[(value >> 4) & 0xF]).append(HEX[value & 0xF]);
        }
        return text.toString();
    }
}
