package ai.ravenroot.api.payload;

/**
 * Resource budgets applied to every structured payload, on every surface (API-01).
 *
 * <p>Modelled on {@code GraphMlLimits} deliberately: the repository already has one declared-budget
 * type for untrusted structured input and a second mechanism with different vocabulary would be a
 * parallel thing to keep in step. As there, the defaults bound both the <em>encoded</em> form and the
 * <em>object graph</em> built from it, because either alone leaves a denial-of-service surface — a
 * small document can describe a large tree, and a large document can be shallow.</p>
 *
 * <p>The compact constructor rejects non-positive budgets and budgets above a safety ceiling. The
 * ceiling exists so that "configurable" cannot become "unbounded" through configuration: an operator
 * may tighten these numbers, and may loosen them only within a range the engine can still survive.</p>
 *
 * @param maxEncodedBytes   largest accepted encoded payload, in bytes
 * @param maxDepth          deepest accepted nesting, counting the outermost value as depth 1
 * @param maxCollectionSize most accepted elements in one list, or entries in one map
 * @param maxValueCount     most accepted values in the whole tree, containers included
 * @param maxTextLength     longest accepted text value, in UTF-16 code units
 * @param maxKeyLength      longest accepted map key, in UTF-16 code units
 */
public record PayloadLimits(
        int maxEncodedBytes,
        int maxDepth,
        int maxCollectionSize,
        int maxValueCount,
        int maxTextLength,
        int maxKeyLength) {

    /**
     * The budgets in force when a caller does not supply its own.
     *
     * <p>256 KiB is well above any control-plane payload a workflow start realistically carries and
     * well below the 10 MiB GraphML budget, which is intentional: the graph is the program and the
     * payload is its argument, so the argument should not be able to dwarf the program.</p>
     */
    public static final PayloadLimits DEFAULTS = new PayloadLimits(
            256 * 1024,
            32,
            1_000,
            10_000,
            32 * 1024,
            256);

    /**
     * Rejects non-positive budgets and operator values above hard safety ceilings. The constructor
     * keeps configuration from converting a bounded input contract into an unbounded allocation path.
     */
    public PayloadLimits {
        if (maxEncodedBytes < 1 || maxDepth < 1 || maxCollectionSize < 1 || maxValueCount < 1
                || maxTextLength < 1 || maxKeyLength < 1) {
            throw new IllegalArgumentException("payload limits must all be positive");
        }
        // The text ceiling is above the 10 MiB GraphML budget on purpose: a transport envelope that
        // carries a submitted document as one member is configured through this same type, and a
        // ceiling below the document budget would make that arrangement inexpressible rather than safe.
        if (maxEncodedBytes > 64 * 1024 * 1024 || maxDepth > 256 || maxCollectionSize > 1_000_000
                || maxValueCount > 5_000_000 || maxTextLength > 64 * 1024 * 1024 || maxKeyLength > 4_096) {
            throw new IllegalArgumentException("payload limits exceed the supported safety ceiling");
        }
    }

    /**
     * Checks an already-built value against these budgets, including its encoded size.
     *
     * <p>This is the second of the two enforcement points. {@link PayloadJson} counts while it parses,
     * so a hostile document is refused before its tree exists; this method covers values that entered
     * the process some other way — an embedded caller building one by hand, or a node returning one —
     * where there was never a document to count.</p>
     *
     * @param value existing payload tree to measure; {@code null} is not a valid payload value
     * @throws PayloadException when any structural or encoded-size budget is exceeded
     */
    public void enforce(PayloadValue value) {
        int[] counted = {0};
        walk(value, 1, counted);
        int encoded = PayloadJson.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (encoded > maxEncodedBytes) {
            throw PayloadRejection.tooLarge(encoded, maxEncodedBytes);
        }
    }

    /**
     * Validates a node-produced Java value and returns its canonical encoded size.
     * Conversion is bounded while it copies, so an iterable cannot allocate an unbounded mirror first.
     *
     * @param value node-produced Java value to validate and measure
     * @return canonical JSON-encoded size in bytes
     */
    public int enforceAndMeasure(Object value) {
        PayloadValue bounded = PayloadValue.fromJava(value, this);
        return PayloadJson.write(bounded).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private void walk(PayloadValue value, int depth, int[] counted) {
        if (depth > maxDepth) {
            throw PayloadRejection.depthExceeded(maxDepth);
        }
        if (++counted[0] > maxValueCount) {
            throw PayloadRejection.valueCountExceeded(maxValueCount);
        }
        switch (value) {
            case PayloadValue.TextValue text -> {
                if (text.value().length() > maxTextLength) {
                    throw PayloadRejection.textTooLong(maxTextLength);
                }
            }
            case PayloadValue.ListValue list -> {
                if (list.values().size() > maxCollectionSize) {
                    throw PayloadRejection.collectionExceeded(maxCollectionSize);
                }
                list.values().forEach(element -> walk(element, depth + 1, counted));
            }
            case PayloadValue.MapValue map -> {
                if (map.entries().size() > maxCollectionSize) {
                    throw PayloadRejection.collectionExceeded(maxCollectionSize);
                }
                map.entries().forEach((key, entry) -> {
                    if (key.length() > maxKeyLength) {
                        throw PayloadRejection.keyTooLong(maxKeyLength);
                    }
                    walk(entry, depth + 1, counted);
                });
            }
            default -> {
                // Scalars other than text carry no unbounded component.
            }
        }
    }
}
