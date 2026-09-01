package ai.ravenroot.api.catalog;

import java.util.List;
import java.util.Objects;

/**
 * A condition on a <em>sibling</em> property of the same node type.
 *
 * <h2>Sibling-scoped, single-property, no expressions</h2>
 * <p>A condition names exactly one other property of the same {@link NodeTypeDescriptor} and one
 * {@link PropertyConditionOperator}. It cannot reach another node, another node type, or the
 * execution payload; it cannot compose sub-expressions. Several conditions on one property combine
 * with <b>AND</b>. Disjunction over one sibling is {@link PropertyConditionOperator#ONE_OF};
 * disjunction across different siblings is deliberately not expressible, because a combinator is the
 * first step toward the expression language this design exists to avoid.
 *
 * <h2>Versioned</h2>
 * <p>{@link #CONTRACT} is emitted with every serialized condition, following the precedent
 * {@code SyntheticProvenance} set. A consumer that does not recognise the contract string must treat
 * the condition as unsatisfiable rather than guess — an unknown condition is not an absent one.
 *
 * @param contract the versioned contract marker; see {@link #CONTRACT}
 * @param property the sibling property's {@code name}, within the same node type
 * @param operator the comparison; see {@link PropertyConditionOperator} for why the set is closed
 * @param values   operands. Empty for {@code PRESENT}/{@code BLANK}, exactly one for {@code EQUALS},
 *                 at least one for {@code ONE_OF}
 */
public record PropertyCondition(String contract, String property, PropertyConditionOperator operator,
                                List<String> values) {
    /** The versioned contract marker for this shape. */
    public static final String CONTRACT = "ravenroot.property-condition/1";

/**
 * Validates operand cardinality for the selected closed comparison operator.
 */
    public PropertyCondition {
        contract = contract == null || contract.isBlank() ? CONTRACT : contract;
        Objects.requireNonNull(operator, "operator");
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("A property condition must name a sibling property");
        }
        values = values == null ? List.of() : List.copyOf(values);
        switch (operator) {
            case EQUALS -> {
                if (values.size() != 1) {
                    throw new IllegalArgumentException(
                            "EQUALS takes exactly one value, got " + values.size() + " for '" + property + "'");
                }
            }
            case ONE_OF -> {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("ONE_OF takes at least one value for '" + property + "'");
                }
            }
            case PRESENT, BLANK -> {
                if (!values.isEmpty()) {
                    throw new IllegalArgumentException(
                            operator + " takes no values, got " + values.size() + " for '" + property + "'");
                }
            }
        }
    }

/**
 * Creates an exact-value condition for one sibling property.
 * @param property sibling property name
 * @param value sole value that satisfies the condition
 * @return an {@link PropertyConditionOperator#EQUALS} condition
 */
    public static PropertyCondition equalTo(String property, String value) {
        return new PropertyCondition(CONTRACT, property, PropertyConditionOperator.EQUALS, List.of(value));
    }

/**
 * Creates a finite-set condition for one sibling property.
 * @param property sibling property name
 * @param values one or more permitted values
 * @return an {@link PropertyConditionOperator#ONE_OF} condition
 */
    public static PropertyCondition oneOf(String property, String... values) {
        return new PropertyCondition(CONTRACT, property, PropertyConditionOperator.ONE_OF, List.of(values));
    }

/**
 * Creates a condition requiring a non-blank sibling value.
 * @param property sibling property name
 * @return a {@link PropertyConditionOperator#PRESENT} condition
 */
    public static PropertyCondition present(String property) {
        return new PropertyCondition(CONTRACT, property, PropertyConditionOperator.PRESENT, List.of());
    }

/**
 * Creates a condition requiring an absent or blank sibling value.
 * @param property sibling property name
 * @return a {@link PropertyConditionOperator#BLANK} condition
 */
    public static PropertyCondition blank(String property) {
        return new PropertyCondition(CONTRACT, property, PropertyConditionOperator.BLANK, List.of());
    }

    /**
     * Whether this condition holds for the given sibling value.
     *
     * <p>{@code null} and blank are the same state here, matching {@code BehaviorPropertySchema}'s
     * own treatment of an absent property: a blank value is "not configured", not a value of "".
 * @param siblingValue current sibling value; {@code null} and blank are equivalent
 * @return whether this operator accepts the normalized sibling value
     */
    public boolean holds(String siblingValue) {
        String value = siblingValue == null ? "" : siblingValue.strip();
        return switch (operator) {
            case EQUALS, ONE_OF -> values.contains(value);
            case PRESENT -> !value.isEmpty();
            case BLANK -> value.isEmpty();
        };
    }

/**
 * The satisfying operand set, for load-time checking against the sibling's allowed values.
 * @return immutable operands used by equality and membership operators
 */
    public List<String> operands() {
        return values;
    }
}
