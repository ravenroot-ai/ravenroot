package ai.ravenroot.api.catalog;

/**
 * The closed operator set for {@link PropertyCondition}.
 *
 * <h2>The set is closed, and its closedness is the contract</h2>
 * <p>A condition in a node descriptor is an evaluation surface reachable by anyone who ships a
 * plugin. An expression language there is an attack surface; a fixed, tiny operator set is not. This
 * enum is therefore the whole grammar, and the absence of anything else is deliberate rather than a
 * starting point — the same way {@code IngressOverflowPolicy} declares one constant and means it.
 *
 * <h2>Why there is no negation, which is the part most likely to be "fixed" later</h2>
 * <p>{@code NOT_EQUALS} and {@code NOT_ONE_OF} are absent on purpose, and not for taste.
 *
 * <p>Every operator here is <b>positive</b>: the set of sibling values that satisfies it is written
 * out in the condition. That is what makes a condition <em>checkable at catalog load</em> — the
 * operands can be verified against the referenced sibling's {@code allowedValues}, so a typo fails
 * the load. A negated operator's satisfying set is open-ended: it is "everything except these", which
 * cannot be checked against anything. A typo'd operand under negation does not fail — it silently
 * <em>widens</em> the set of states in which a field appears or becomes required.
 *
 * <p>So negation would convert a load-time error into a silent change of behaviour, which is the
 * opposite of what fail-closed validation is for. Anything expressible with negation over a bounded
 * enumeration is expressible by enumerating the positive cases, and doing so is what lets the
 * validator check it. Widening this enum means accepting that trade; do not do it without saying so
 * here.
 */
public enum PropertyConditionOperator {
    /** The sibling's value equals exactly one named value. */
    EQUALS,

    /** The sibling's value equals one of several named values. */
    ONE_OF,

    /** The sibling has a non-blank value. Takes no operands. */
    PRESENT,

    /** The sibling is absent or blank. Takes no operands. */
    BLANK
}
