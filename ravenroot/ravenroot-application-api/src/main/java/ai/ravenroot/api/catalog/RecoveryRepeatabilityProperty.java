package ai.ravenroot.api.catalog;

import java.util.List;
import java.util.Map;

/**
 * The well-known node property through which a graph author declares, <strong>per node instance</strong>,
 * whether an effect whose outcome is unknown may be repeated (PERS-04, the attempt-repeatability declaration contract).
 *
 * <h2>Why this is an ordinary property and not a reserved one</h2>
 * <p>An earlier draft placed it in the {@code ravenroot.} namespace. That was a design error rather
 * than a boundary to be relaxed. The reserved namespace protects <em>platform-asserted</em> facts
 * from author forgery, and this value is not one: it is an <em>author assertion of domain
 * knowledge</em> — "this POST is safe to repeat" — which is exactly what the contract requires the author to
 * supply. The author already controls that node's URL, method and payload; declaring it repeatable
 * grants no authority they lack, and misuse harms only the effects of their own workload. Same trust
 * class as the node configuration itself. {@code ReservedGraphProperties} therefore stays closed,
 * with no exception, and the namespace does not become a list that grows.</p>
 *
 * <h2>Anti-collision lives here, at catalog load</h2>
 * <p>Because the name is ordinary, a plugin could declare it. {@link #validateShape(NodeTypeDescriptor)}
 * — invoked by {@link NodeTypeDescriptorValidator} and therefore by every catalog registration —
 * requires that a descriptor declaring this name declares it with exactly the canonical shape. A
 * descriptor that declares {@code recovery.repeatable} with a different type, different allowed
 * values, or a default of {@code repeatable} <strong>fails catalog load, loudly</strong>, rather than
 * silently widening a recovery contract at runtime.</p>
 *
 * <h2>The read is structural, not policed</h2>
 * <p>{@link #read(NodeTypeDescriptor, Map)} is the <em>only</em> way to obtain this value, and it
 * takes the descriptor as its first parameter. There is deliberately no overload that accepts
 * instance values alone. A string lookup on raw node data would quietly succeed against a node whose
 * descriptor never declared anything — reading an author's stray key as a recovery contract the
 * catalog never sanctioned — and the failure would be invisible precisely because it produces a
 * plausible answer. Here, a descriptor that does not declare the property yields
 * {@link AttemptRepeatability#UNDECLARED} no matter what the graph carries, and the caller cannot
 * reach a different answer because it cannot reach a different method.</p>
 */
public final class RecoveryRepeatabilityProperty {

    /** The well-known name. Unreserved, and deliberately so. */
    public static final String NAME = "recovery.repeatable";

    /** The only value that authorises repeating an effect of unknown outcome. */
    public static final String REPEATABLE = "repeatable";

    /** An explicit refusal, distinct from saying nothing. */
    public static final String NOT_REPEATABLE = "not-repeatable";

    /**
     * The only serialized values accepted by the recovery-repeatability declaration.
     */
    public static final List<String> ALLOWED_VALUES = List.of(REPEATABLE, NOT_REPEATABLE);

    private RecoveryRepeatabilityProperty() {
    }

    /**
     * The canonical unconditional declaration, for a type where re-execution is always meaningful.
     *
     * <p>The default is empty rather than {@code repeatable}: a platform type that defaulted it to
     * repeatable would convert the whole posture from fail-closed to fail-open at one descriptor's
     * stroke, invisibly, because the result is indistinguishable from an author who chose it.</p>
     * @param description author-facing explanation; blank input uses the standard recovery guidance
     * @return an optional string property with no default and the canonical allowed values
     */
    public static NodePropertyDescriptor declaration(String description) {
        return declaration(description, null);
    }

    /**
     * The canonical declaration, optionally required only in the states that matter — for example
     * {@code http.request} requiring it when {@code method} is one of POST, PUT, PATCH or DELETE,
     * so the author is forced to decide exactly where the decision has consequences.
     * @param description author-facing explanation; blank input uses the standard recovery guidance
     * @param requiredWhen sibling condition under which graph admission requires the declaration;
     *                     {@code null} makes it unconditionally optional
     * @return an optional string property with no default and the canonical allowed values
     */
    public static NodePropertyDescriptor declaration(String description, PropertyCondition requiredWhen) {
        return new NodePropertyDescriptor(NAME, "Repeatable after an unknown outcome",
                NodePropertyType.STRING, false,
                description == null || description.isBlank()
                        ? "Whether this node's effect may be repeated when a crash left its outcome unknown."
                        : description,
                "", ALLOWED_VALUES, false, null, requiredWhen);
    }

    /**
     * Whether {@code descriptor} declares this property at all.
     *
     * @param descriptor catalog entry to inspect, possibly {@code null}
     * @return {@code true} only when its property schema contains {@link #NAME}
     */
    public static boolean declaredBy(NodeTypeDescriptor descriptor) {
        return declarationOf(descriptor) != null;
    }

    /**
     * The declaration of one node instance, read through its type's descriptor.
     *
     * <p>Fail-closed on every branch, and each branch is a distinct way for the contract to be
     * missing: the descriptor does not declare the property; the instance omits it; the value is
     * null, blank, or a token outside {@link #ALLOWED_VALUES}. All of them are
     * {@link AttemptRepeatability#UNDECLARED}, and only the exact token {@link #REPEATABLE} —
     * case-sensitive, because an approximate match here repeats an effect nobody authorised — yields
     * {@link AttemptRepeatability#REPEATABLE}.</p>
     * @param descriptor catalog entry that must declare {@link #NAME}
     * @param instanceValues configured node properties, possibly {@code null}
     * @return the exact parsed declaration, or {@link AttemptRepeatability#UNDECLARED} when the
     *         descriptor or configured value cannot authorize re-dispatch
     */
    public static AttemptRepeatability read(NodeTypeDescriptor descriptor,
                                            Map<String, Object> instanceValues) {
        if (!declaredBy(descriptor)) {
            // The catalog never sanctioned this contract for this type, so nothing the graph says
            // about it is a declaration. This is the branch a raw string lookup would get wrong.
            return AttemptRepeatability.UNDECLARED;
        }
        if (instanceValues == null) {
            return AttemptRepeatability.UNDECLARED;
        }
        return parse(instanceValues.get(NAME));
    }

    /**
     * Parses a raw configured value without granting authority to near misses.
     *
     * @param rawValue configured value, possibly {@code null}; surrounding whitespace is ignored
     * @return {@link AttemptRepeatability#REPEATABLE} or {@link AttemptRepeatability#NOT_REPEATABLE}
     *         for exact tokens, otherwise {@link AttemptRepeatability#UNDECLARED}
     */
    public static AttemptRepeatability parse(Object rawValue) {
        if (rawValue == null) {
            return AttemptRepeatability.UNDECLARED;
        }
        String value = rawValue.toString().strip();
        if (value.equals(REPEATABLE)) {
            return AttemptRepeatability.REPEATABLE;
        }
        if (value.equals(NOT_REPEATABLE)) {
            return AttemptRepeatability.NOT_REPEATABLE;
        }
        return AttemptRepeatability.UNDECLARED;
    }

    /**
     * Rejects a descriptor that declares the well-known name with anything other than the canonical
     * shape. Called by {@link NodeTypeDescriptorValidator#validate(NodeTypeDescriptor)}, so the
     * rejection happens at catalog registration and is visible there rather than at the first crash.
     *
     * @param descriptor catalog entry whose declaration of {@link #NAME} is checked
     * @throws IllegalArgumentException when the declared shape broadens or defaults the contract open
     */
    public static void validateShape(NodeTypeDescriptor descriptor) {
        NodePropertyDescriptor declared = declarationOf(descriptor);
        if (declared == null) {
            return;
        }
        if (declared.type() != NodePropertyType.STRING) {
            throw reject(descriptor, "must be declared as STRING, not " + declared.type());
        }
        if (!ALLOWED_VALUES.equals(declared.allowedValues())) {
            throw reject(descriptor, "must declare exactly the allowed values " + ALLOWED_VALUES
                    + ", not " + declared.allowedValues());
        }
        if (declared.adapterBinding()) {
            throw reject(descriptor, "is not an adapter binding");
        }
        if (REPEATABLE.equals(declared.defaultValue().strip())) {
            // The single most dangerous shape: it turns fail-closed into fail-open for every instance
            // of the type, and produces exactly the state an author who chose it would produce.
            throw reject(descriptor, "must not default to '" + REPEATABLE
                    + "'; a default that authorises repeating an unknown effect makes the "
                    + "fail-closed contract fail open for every instance of this type");
        }
    }

    private static NodePropertyDescriptor declarationOf(NodeTypeDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        return descriptor.properties().stream()
                .filter(property -> NAME.equals(property.name()))
                .findFirst()
                .orElse(null);
    }

    private static IllegalArgumentException reject(NodeTypeDescriptor descriptor, String detail) {
        return new IllegalArgumentException("Behavior '" + descriptor.behavior() + "' property '"
                + NAME + "' " + detail);
    }
}
