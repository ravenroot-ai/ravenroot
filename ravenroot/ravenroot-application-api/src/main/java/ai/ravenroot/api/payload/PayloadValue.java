package ai.ravenroot.api.payload;

import ai.ravenroot.api.execution.NodeFailurePayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The structured payload type model (API-01).
 *
 * <h2>Why a closed type model rather than {@code Object}</h2>
 * <p>Before API-01 a payload was an {@code Object} whose only real inhabitant on the HTTP surface was
 * a {@code String} taken from a query parameter. That is not a contract: it has no schema, no
 * version, no limits, and nothing an adapter can validate before the value reaches a node. This
 * sealed hierarchy is the contract — a payload is exactly one of seven things, and no eighth can be
 * added by a caller because the interface is sealed and every implementation is nested here.</p>
 *
 * <h2>The engine keeps carrying {@code Object}</h2>
 * <p>{@link #toJava()} projects a value onto the {@code String}/{@code Long}/{@code Double}/
 * {@code Boolean}/{@code List}/{@code Map} shapes that {@code NodeMessage.payload()} already carries,
 * and {@link #fromJava(Object, PayloadLimits)} reads them back. The engine, the CEL nodes and the
 * program runtime are therefore untouched by API-01: the type model is a boundary contract, not a new
 * interior representation. That is what keeps the change compatible in both directions.</p>
 *
 * <p>{@link NodeFailurePayload} is the one explicit runtime envelope accepted in addition to those
 * interior shapes. It projects to its four documented members without reflection or
 * {@code toString()}, including when nested in a list or map. Its {@code input} member re-enters this
 * same conversion, so the complete projection remains subject to the caller's {@link PayloadLimits}
 * and an unsupported input is rejected instead of exposing an arbitrary JVM object. An input that is
 * already a {@code PayloadValue} is walked in place rather than trusted: its descendants share the
 * envelope's depth and value-count accounting, just like descendants supplied as Java lists/maps.</p>
 *
 * <h2>Backward compatibility with the textual payload</h2>
 * <p>The legacy textual payload is not a special case, a second content type or a translation: it is
 * a {@link TextValue}, the degenerate scalar. {@code PayloadValue.of("hello").toJava()} is
 * {@code "hello"} — the exact object the pre-API-01 path produced — so an existing client and a
 * structured client hand a node the same thing when they mean the same thing.</p>
 */
public sealed interface PayloadValue {

    /** The absence of a value, distinct from an absent payload and from empty text. */
    record NullValue() implements PayloadValue {
    }

/**
 * JSON-compatible boolean scalar.
 * @param value boolean represented by this scalar
 */
    record BooleanValue(boolean value) implements PayloadValue {
    }

/**
 * A 64-bit signed integer. Integral numbers never silently become floating point.
 * @param value signed integer represented without floating-point conversion
 */
    record IntegerValue(long value) implements PayloadValue {
    }

    /**
     * A 64-bit floating point number.
     *
     * <p>NaN and the infinities are refused at construction. They have no JSON representation, so
     * accepting them would create values that can be built but not transported — a class of bug that
     * only appears at the boundary, under load, in whichever adapter serialises first.</p>
 * @param value finite IEEE-754 number represented by this scalar
     */
    record DecimalValue(double value) implements PayloadValue {
/**
 * Rejects non-finite values because JSON cannot encode them.
 */
        public DecimalValue {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw PayloadRejection.unsupportedType();
            }
        }
    }

/**
 * UTF-16 text scalar; empty text is distinct from {@link #NULL}.
 * @param value non-null text represented by this scalar
 */
    record TextValue(String value) implements PayloadValue {
/**
 * Rejects {@code null}; callers use {@link #NULL} for the JSON null value.
 */
        public TextValue {
            Objects.requireNonNull(value, "value");
        }
    }

/**
 * Ordered payload sequence.
 * @param values non-null elements copied into an immutable list
 */
    record ListValue(List<PayloadValue> values) implements PayloadValue {
/**
 * Defensively copies the sequence to preserve payload immutability.
 */
        public ListValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    /**
     * A map keyed by {@code String} only.
     *
     * <p>Non-string keys are not part of the model. JSON has none, every other transport the product
     * is likely to grow has none, and admitting them would make the encoded form depend on a key's
     * {@code toString()} — which is neither stable nor a contract.</p>
 * @param entries string-keyed values copied into an immutable map
     */
    record MapValue(Map<String, PayloadValue> entries) implements PayloadValue {
/**
 * Defensively copies entries and rejects a {@code null} map.
 */
        public MapValue {
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

/**
 * Singleton representation of JSON {@code null}.
 */
    PayloadValue NULL = new NullValue();

/**
 * Wraps a boolean as a payload scalar.
 * @param value boolean to carry
 * @return immutable boolean payload value
 */
    static PayloadValue of(boolean value) {
        return new BooleanValue(value);
    }

/**
 * Wraps a signed integer as a payload scalar.
 * @param value integer to carry
 * @return immutable integer payload value
 */
    static PayloadValue of(long value) {
        return new IntegerValue(value);
    }

/**
 * Wraps a finite decimal as a payload scalar.
 * @param value decimal to carry
 * @return immutable decimal payload value
 */
    static PayloadValue of(double value) {
        return new DecimalValue(value);
    }

/**
 * The degenerate scalar that the pre-API-01 textual payload maps onto.
 * @param value text to carry; {@code null} maps to {@link #NULL}
 * @return text payload value or the null singleton
 */
    static PayloadValue of(String value) {
        return value == null ? NULL : new TextValue(value);
    }

/**
 * Creates an ordered payload sequence from varargs.
 * @param values values in their intended order
 * @return immutable list payload value
 */
    static PayloadValue list(PayloadValue... values) {
        return new ListValue(List.of(values));
    }

/**
 * Creates an ordered payload sequence from a list.
 * @param values values copied into the payload sequence
 * @return immutable list payload value
 */
    static PayloadValue list(List<PayloadValue> values) {
        return new ListValue(values);
    }

/**
 * Creates a string-keyed payload object while preserving insertion order for engine projection.
 * @param entries object members to carry
 * @return immutable map payload value
 */
    static PayloadValue map(Map<String, PayloadValue> entries) {
        var ordered = new LinkedHashMap<String, PayloadValue>();
        entries.forEach(ordered::put);
        return new MapValue(ordered);
    }

/**
 * The shape this value declares, as the schema names it.
 * @return {@link PayloadKind#SCALAR}, {@link PayloadKind#LIST}, or {@link PayloadKind#MAP}
 */
    default PayloadKind kind() {
        return switch (this) {
            case ListValue ignored -> PayloadKind.LIST;
            case MapValue ignored -> PayloadKind.MAP;
            default -> PayloadKind.SCALAR;
        };
    }

    /**
     * The interior representation the engine already carries.
     *
     * <p>Maps come back insertion-ordered and lists in order, so a node observes the structure the
     * caller described rather than one the boundary reshuffled.</p>
 * @return Java scalar, mutable ordered list, or insertion-ordered map consumed by the engine
     */
    default Object toJava() {
        return switch (this) {
            case NullValue ignored -> null;
            case BooleanValue value -> value.value();
            case IntegerValue value -> value.value();
            case DecimalValue value -> value.value();
            case TextValue value -> value.value();
            case ListValue value -> value.values().stream().map(PayloadValue::toJava)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            case MapValue value -> {
                var result = new LinkedHashMap<String, Object>();
                value.entries().forEach((key, entry) -> result.put(key, entry.toJava()));
                yield result;
            }
        };
    }

    /**
     * Reads an interior value back into the type model, enforcing {@code limits} while it descends.
     *
     * <p>Counting during construction rather than after it is the point: a node that returns a
     * ten-million-element list must be refused before that list is copied into the model, not once a
     * second copy exists. Anything outside the model — an arbitrary JVM object, a NaN, a map with a
     * non-string key — is refused rather than coerced, because a silent coercion at this boundary is
     * how an unrepresentable value becomes a serialisation failure three hops later.</p>
     *
     * @throws PayloadException when the value is outside the model or exceeds {@code limits}
 * @param value Java value received from a node or adapter
 * @param limits depth, count, and encoded-size budgets enforced during conversion
 * @return immutable payload representation of the accepted Java value
     */
    static PayloadValue fromJava(Object value, PayloadLimits limits) {
        Objects.requireNonNull(limits, "limits");
        int[] counted = {0};
        PayloadValue result = convert(value, 1, counted, limits);
        int encoded = PayloadJson.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (encoded > limits.maxEncodedBytes()) {
            throw PayloadRejection.tooLarge(encoded, limits.maxEncodedBytes());
        }
        return result;
    }

    private static PayloadValue convert(Object value, int depth, int[] counted, PayloadLimits limits) {
        if (depth > limits.maxDepth()) {
            throw PayloadRejection.depthExceeded(limits.maxDepth());
        }
        if (++counted[0] > limits.maxValueCount()) {
            throw PayloadRejection.valueCountExceeded(limits.maxValueCount());
        }
        return switch (value) {
            case null -> NULL;
            case PayloadValue already -> validateAlreadyBuilt(already, depth, counted, limits);
            case Boolean flag -> of(flag.booleanValue());
            case Byte number -> of(number.longValue());
            case Short number -> of(number.longValue());
            case Integer number -> of(number.longValue());
            case Long number -> of(number.longValue());
            case Float number -> of(number.doubleValue());
            case Double number -> of(number.doubleValue());
            case NodeFailurePayload failure -> {
                if (4 > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                // "errorClass" is the longest of the four fixed contract keys. Fixed does not mean
                // exempt: a caller that tightens maxKeyLength must still get the same classified
                // rejection it would get for an ordinary map.
                if ("errorClass".length() > limits.maxKeyLength()) {
                    throw PayloadRejection.keyTooLong(limits.maxKeyLength());
                }
                var entries = new LinkedHashMap<String, PayloadValue>();
                entries.put("nodeId", convert(failure.nodeId(), depth + 1, counted, limits));
                entries.put("errorClass", convert(failure.errorClass(), depth + 1, counted, limits));
                entries.put("message", convert(failure.message(), depth + 1, counted, limits));
                entries.put("input", convert(failure.input(), depth + 1, counted, limits));
                yield new MapValue(entries);
            }
            case CharSequence text -> {
                if (text.length() > limits.maxTextLength()) {
                    throw PayloadRejection.textTooLong(limits.maxTextLength());
                }
                yield new TextValue(text.toString());
            }
            case Map<?, ?> map -> {
                if (map.size() > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                var entries = new LinkedHashMap<String, PayloadValue>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw PayloadRejection.unsupportedType();
                    }
                    if (key.length() > limits.maxKeyLength()) {
                        throw PayloadRejection.keyTooLong(limits.maxKeyLength());
                    }
                    entries.put(key, convert(entry.getValue(), depth + 1, counted, limits));
                }
                yield new MapValue(entries);
            }
            case Object[] array -> {
                if (array.length > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                var values = new ArrayList<PayloadValue>(array.length);
                for (Object element : array) {
                    values.add(convert(element, depth + 1, counted, limits));
                }
                yield new ListValue(values);
            }
            case Iterable<?> iterable -> {
                var values = new ArrayList<PayloadValue>();
                for (Object element : iterable) {
                    if (values.size() >= limits.maxCollectionSize()) {
                        throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                    }
                    values.add(convert(element, depth + 1, counted, limits));
                }
                yield new ListValue(values);
            }
            default -> throw PayloadRejection.unsupportedType();
        };
    }

    /**
     * Validates an immutable value that entered the conversion already in wire-model form.
     *
     * <p>The current value has already consumed one depth/value slot in {@link #convert}; descendants
     * deliberately re-enter that same method with the same counter. Calling {@link PayloadLimits#enforce}
     * here would restart both measurements at the nested root and let an envelope plus a separately
     * valid input exceed the whole-tree budgets when combined.</p>
     */
    private static PayloadValue validateAlreadyBuilt(
            PayloadValue value, int depth, int[] counted, PayloadLimits limits) {
        switch (value) {
            case TextValue text -> {
                if (text.value().length() > limits.maxTextLength()) {
                    throw PayloadRejection.textTooLong(limits.maxTextLength());
                }
            }
            case ListValue list -> {
                if (list.values().size() > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                for (PayloadValue element : list.values()) {
                    convert(element, depth + 1, counted, limits);
                }
            }
            case MapValue map -> {
                if (map.entries().size() > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                for (Map.Entry<String, PayloadValue> entry : map.entries().entrySet()) {
                    if (entry.getKey().length() > limits.maxKeyLength()) {
                        throw PayloadRejection.keyTooLong(limits.maxKeyLength());
                    }
                    convert(entry.getValue(), depth + 1, counted, limits);
                }
            }
            default -> {
                // Null, boolean and numeric values have no descendant or variable-width component.
            }
        }
        return value;
    }

    /**
     * Refuses a payload that presents reserved security naming anywhere in its tree (SEC-07).
     *
     * <p>{@code AuthorizedRavenrootApplication} calls this on every payload it starts a traversal
     * with, on both of its {@code startGraphMl} overloads: a tree walk, not a top-level scan.
     * A top-level-only check used to be what the {@code Object} overload had instead, and it was not
     * merely narrower than this method -- it was blind to exactly the case this method exists for.
     * The check has to be a tree walk because identity does not travel under these names, so the harm
     * is not overwriting anything -- it is that a node author, a template or a log line may
     * <em>believe</em> a plausible-looking key. A nested key is no less believable than a top-level
     * one.</p>
     *
     * @throws PayloadException when any key in the tree is reserved
 * @param value payload tree to inspect; {@code null} is not permitted
     */
    static void requireNoReservedKeys(PayloadValue value) {
        switch (value) {
            case MapValue map -> map.entries().forEach((key, entry) -> {
                if (ai.ravenroot.api.security.SecurityContext.isReservedKey(key)) {
                    throw PayloadRejection.reservedKey();
                }
                requireNoReservedKeys(entry);
            });
            case ListValue list -> list.values().forEach(PayloadValue::requireNoReservedKeys);
            default -> {
                // Scalars carry no keys.
            }
        }
    }
}
