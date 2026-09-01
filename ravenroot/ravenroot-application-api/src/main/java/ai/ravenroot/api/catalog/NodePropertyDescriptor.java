package ai.ravenroot.api.catalog;

import java.util.List;

/**
 * Describes one configurable property without tying the API to a UI framework.
 * @param name stable schema key; blank names are rejected
 * @param displayName editor-facing label; blank values fall back to {@code name}
 * @param type value representation; {@code null} defaults to {@link NodePropertyType#STRING}
 * @param required baseline admission requirement, subject only to declared conditional rules
 * @param description editor-facing help text; {@code null} becomes empty
 * @param defaultValue value used by an editor or factory when no graph value is present
 * @param allowedValues immutable enumerated choices, or empty when the type is not enumerated
 * @param adapterBinding whether this required value selects deployment-owned adapter configuration
 * @param visibleWhen condition controlling editor visibility; {@code null} is unconditional
 * @param requiredWhen condition controlling requiredness; {@code null} retains {@code required}
 */
public record NodePropertyDescriptor(
        String name,
        String displayName,
        NodePropertyType type,
        boolean required,
        String description,
        String defaultValue,
        List<String> allowedValues,
        boolean adapterBinding,
        PropertyCondition visibleWhen,
        PropertyCondition requiredWhen) {

    /**
     * Normalizes presentation fields and takes immutable allowed-value snapshots while rejecting the
     * invalid claim that an optional property is a required adapter binding.
     */
    public NodePropertyDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property name cannot be blank");
        }
        if (adapterBinding && !required) {
            // An adapter binding that is not required would suspend nothing, because nothing would
            // have been enforced in the first place. Rejecting the combination keeps the flag from
            // being read as "optional", which is precisely what it does not mean.
            throw new IllegalArgumentException(
                    "An adapter binding must also be required: " + name);
        }
        displayName = displayName == null || displayName.isBlank() ? name : displayName;
        type = type == null ? NodePropertyType.STRING : type;
        description = description == null ? "" : description;
        defaultValue = defaultValue == null ? "" : defaultValue;
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        // Absent means UNCONDITIONAL -- always visible, required exactly as declared. Every catalog
        // authored before conditional properties existed lands here, which makes the addition compatible without a
        // migration. Absent is not "a condition that always holds": the two are different statements
        // and only one of them is what an older descriptor meant.
    }

    /**
     * The pre-CORE-07 canonical constructor, retained so that existing callers and SDK node packages
     * compile unchanged. Declares no adapter binding, which is the correct default: a property is an
     * ordinary required-or-optional property unless a behavior deliberately says otherwise.
     */
    /**
     * Compatibility constructor preserving the canonical shape before {@code visibleWhen} and
     * {@code requiredWhen}. Both default to {@code null} — unconditional.
     *
     * <p>Additive, not breaking: adding a record component changes the canonical constructor's
     * descriptor, which is a binary break for any caller already compiled against the older class
     * file and not only a source break for one being recompiled. Unlike the CORE-07 overload below,
     * this one is proved rather than asserted — see {@code NodePropertyDescriptorBinaryCompatibilityTest},
     * which also retro-validates CORE-07's own claim.
     * @param name stable schema key; blank names are rejected
     * @param displayName editor-facing label, defaulting to {@code name}
     * @param type declared scalar representation, defaulting to string
     * @param required baseline graph-admission requirement
     * @param description editor-facing help text
     * @param defaultValue fallback textual value
     * @param allowedValues immutable enumerated choices
     * @param adapterBinding whether absence defers requiredness until traversal reaches the node
     */
    public NodePropertyDescriptor(String name, String displayName, NodePropertyType type, boolean required,
                                  String description, String defaultValue, List<String> allowedValues,
                                  boolean adapterBinding) {
        this(name, displayName, type, required, description, defaultValue, allowedValues, adapterBinding,
                null, null);
    }

    /**
     * Creates an unconditional property with no deployment adapter binding.
     *
     * @param name stable schema key; blank names are rejected
     * @param displayName editor-facing label, defaulting to {@code name}
     * @param type declared scalar representation, defaulting to string
     * @param required baseline graph-admission requirement
     * @param description editor-facing help text
     * @param defaultValue fallback textual value
     * @param allowedValues immutable enumerated choices
     */
    public NodePropertyDescriptor(String name, String displayName, NodePropertyType type, boolean required,
                                   String description, String defaultValue, List<String> allowedValues) {
        this(name, displayName, type, required, description, defaultValue, allowedValues, false);
    }

    /**
     * Declares an unconditional required property without an enumerated value set.
     *
     * @param name stable schema key
     * @param displayName editor-facing label
     * @param type expected value representation
     * @param description editor-facing help text
     * @return descriptor that requires a graph value during ordinary admission
     */
    public static NodePropertyDescriptor required(String name, String displayName, NodePropertyType type,
                                                   String description) {
        return new NodePropertyDescriptor(name, displayName, type, true, description, "", List.of(), false);
    }

    /**
     * Declares an unconditional optional property with a textual fallback.
     *
     * @param name stable schema key
     * @param displayName editor-facing label
     * @param type expected value representation
     * @param description editor-facing help text
     * @param defaultValue fallback when the graph omits the property
     * @return descriptor whose absence does not fail ordinary admission
     */
    public static NodePropertyDescriptor optional(String name, String displayName, NodePropertyType type,
                                                   String description, String defaultValue) {
        return new NodePropertyDescriptor(name, displayName, type, false, description, defaultValue, List.of(), false);
    }

    /**
     * A required property naming the server-configured adapter that arms this node (CORE-07).
     *
     * <h4>What the flag means</h4>
     * <p>The property is required, exactly like {@link #required}. What differs is the meaning of its
     * <em>absence</em>: a node that does not name its adapter is an <strong>unconfigured</strong>
     * node, not a defective graph. The graph is admitted, and the node refuses when a traversal
     * actually reaches it.</p>
     *
     * <h4>Why the distinction exists</h4>
     * <p>CORE-05 already made a node whose adapter id resolves to nothing build-and-refuse rather
     * than fail admission, because whether an adapter is installed is a property of the deployment,
     * not of the graph. But required-ness was still enforced at composition, so the two halves
     * disagreed: naming a provider that did not exist produced a graph you could open, run and
     * observe, while leaving the same field blank refused the whole submission. Writing a made-up id
     * worked strictly better than leaving it blank, which is the wrong way round — the blank field is
     * the honest statement that the node is not configured yet.</p>
     *
     * <h4>What it does not do</h4>
     * <p>It suspends <em>required-ness</em>, and only for the node that carries it. It does not
     * suspend type checking, allowed-value checking, or the reserved-namespace refusal, and it does
     * not travel to any other behavior. A behavior that declares no adapter binding is validated
     * exactly as before, so a missing required property remains a graph defect for every node kind
     * that is not one of these deployment-configured extension points.</p>
     *
     * <h4>Why this is a catalog concept rather than a rule about two behavior names</h4>
     * <p>The alternative was to name {@code llm-prompt} and {@code agent} inside the validator. That
     * would have put the schema's behaviour in the validator instead of the catalog, and SEC-09's
     * guarantee is precisely that the catalog — not graph content and not incidental code — decides
     * what a behavior's properties are. Declaring the binding here keeps the authority in the same
     * place as {@code required}, {@code type} and {@code allowedValues}, and lets any node package
     * that genuinely has a deployment-configured adapter say so, instead of the property being a
     * privilege of two built-ins.</p>
     * @param name stable schema key
     * @param displayName editor-facing label
     * @param type expected identifier representation
     * @param description editor-facing help text
     * @return required adapter-binding descriptor whose blank value is handled at traversal time
     */
    public static NodePropertyDescriptor adapterId(String name, String displayName, NodePropertyType type,
                                                    String description) {
        return new NodePropertyDescriptor(name, displayName, type, true, description, "", List.of(), true);
    }

    /**
     * The canonical adapter id carried by {@code rawValue}: stripped, and empty exactly when the
     * value names no adapter.
     *
     * <h4>This is the single definition, and it must stay single</h4>
     * <p>Two parties have to agree about what "names no adapter" means, and they sit on opposite
     * sides of a security-relevant carve-out: the validator decides whether to <em>grant the
     * exemption</em> from required-ness, and the behavior factory decides whether to
     * <em>refuse</em>. If they disagree for any value, that value is admitted by the validator and
     * not refused by the factory, and the node then neither validates nor refuses.</p>
     *
     * <p>That is not hypothetical — it is the defect this method exists to close. The first CORE-07
     * implementation used {@code isBlank()} in the validator and {@code trim().isEmpty()} in the
     * factories. {@code trim()} strips only codepoints {@code <= U+0020} while {@code isBlank()} uses
     * {@code Character.isWhitespace}, so the two disagree on 38 codepoints. For the 15 that
     * {@code isBlank()} calls blank but {@code trim()} does not remove — U+1680, U+2000..U+2006,
     * U+2008..U+200A, U+2028, U+2029, U+205F, U+3000, all legal XML that survives GraphML ingest — a
     * graph naming an adapter of {@code "　"} was admitted, hashed and recorded, and then threw
     * synchronously out of {@code create(GraphNode)} inside the runner's spawn loop, which leaves the
     * traversal non-terminal with no failure event recorded at all.</p>
     *
     * <p>So do not reimplement this check at a call site, and do not "simplify" a caller to
     * {@code isBlank()}, {@code trim()} or {@code strip()} inline. Those spellings are not
     * interchangeable, the difference is hard to see because every ASCII test value agrees,
     * and the value that separates them is chosen by whoever writes the graph. Returning the
     * normalised id rather than a boolean is deliberate: a caller that needs the id for a registry
     * lookup gets it from the same call that decided it was present, so there is no second
     * normalisation to drift.</p>
     *
     * <p><strong>This warning has a third audience.</strong> The Ravenroot UI editor
     * ({@code ravenroot/ravenroot-ui/src/app.js}, {@code catalogPropertyFieldsHtml}) asks this exact
     * question to decide whether to show its "not configured yet" hint, and it cannot call this Java
     * method directly. It instead carries a JavaScript port, {@code adapterIdOf} in
     * {@code ravenroot/ravenroot-ui/src/adapter-binding.js}, which must classify every code point the
     * same way {@link Character#isWhitespace(int)} does. It was ported once, ECMAScript's
     * {@code trim()} is a different character set (differs on U+00A0, U+2007, U+202F, U+FEFF, and
     * U+001C–U+001F), and the same rule applies there: no ad hoc respelling at that call site either.
     * If this method's notion of blank ever changes, that file must be re-verified against it.</p>
     * @param rawValue graph-supplied value or {@code null}
     * @return stripped identifier, or the empty string when no adapter is named
     */
    public static String adapterIdOf(Object rawValue) {
        return rawValue == null ? "" : rawValue.toString().strip();
    }
}
