package ai.ravenroot.api.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-closed descriptor validation for conditional properties. */
class NodeTypeDescriptorValidatorTest {
    private static final List<String> MODES = List.of("LONG_POLLING", "WEBHOOK");

    /** Every descriptor authored before conditional properties has no conditions and must stay admissible. */
    @Test
    void acceptsAnUnconditionalDescriptor() {
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(
                NodePropertyDescriptor.required("mode", "Mode", NodePropertyType.STRING, "d"),
                NodePropertyDescriptor.optional("url", "URL", NodePropertyType.URI, "d", ""))));
    }

    @Test
    void acceptsTheMotivatingModeSwitch() {
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(mode(),
                conditional("callbackUrl", PropertyCondition.equalTo("mode", "WEBHOOK"),
                        PropertyCondition.equalTo("mode", "WEBHOOK")))));
    }

    @Test
    void rejectsADanglingReference() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(mode(), conditional("url", PropertyCondition.equalTo("nosuch", "WEBHOOK"), null))));
        assertTrue(failure.getMessage().contains("does not declare"), failure.getMessage());
    }

    /**
     * Backs the class Javadoc's rule 1: a dangling reference is
     * rejected outright rather than evaluated, and that matters because evaluating it would not
     * uniformly fail. A missing sibling reaches {@link PropertyCondition#holds} as {@code null} -- the
     * same value {@code BehaviorPropertySchema.requiredHere} passes when a key is absent from the
     * graph node's property map -- which strips to {@code ""}, the empty string.
     *
     * <p>The first version of this test and of rule 1's javadoc both said "only {@code PRESENT} and a
     * non-blank {@code EQUALS}/{@code ONE_OF} hold false here", reaching for "blank" (this class's
     * other sense, {@link String#isBlank}) where the code means "empty" (literal {@code ""}). Both
     * readings of that sentence are false, proved below: {@code EQUALS(" ")} has a blank-but-non-empty
     * operand and still holds {@code false}, and {@code ONE_OF("", "X")} has a non-blank member
     * ({@code "X"}) alongside the empty one and still holds {@code true}. The exact rule --
     * {@code holds(null)} is {@code true} iff the operator is {@code BLANK}, or it is {@code
     * EQUALS}/{@code ONE_OF} and {@code ""} (not merely something blank) is among the operands -- is
     * what the assertions below pin, including the two cases that killed the first wording.
     */
    @Test
    void pendingReferenceOperatorsDoNotAllHoldFalseOnAMissingSibling() {
        assertTrue(PropertyCondition.blank("sibling").holds(null),
                "BLANK must hold on a missing sibling, or rule 1's corrected reasoning is unfounded");
        assertTrue(PropertyCondition.equalTo("sibling", "").holds(null),
                "EQUALS(\"\") must hold on a missing sibling, same reasoning as BLANK above");
        assertTrue(PropertyCondition.oneOf("sibling", "", "X").holds(null),
                "ONE_OF must hold true on a missing sibling as soon as \"\" is one of its operands, "
                        + "even when another operand (\"X\") is not blank -- falsifies 'only a "
                        + "non-blank EQUALS/ONE_OF holds false'");
        assertFalse(PropertyCondition.present("sibling").holds(null),
                "PRESENT must hold false on a missing sibling");
        assertFalse(PropertyCondition.equalTo("sibling", "X").holds(null),
                "EQUALS on an operand without \"\" must hold false on a missing sibling");
        assertFalse(PropertyCondition.oneOf("sibling", "X", "Y").holds(null),
                "ONE_OF on operands without \"\" must hold false on a missing sibling");
        assertFalse(PropertyCondition.equalTo("sibling", " ").holds(null),
                "EQUALS(\" \") must hold false on a missing sibling: the operand is blank but not the "
                        + "literal empty string, so it does not match the stripped \"\" -- falsifies "
                        + "'only a non-blank operand holds false'");
        assertFalse(PropertyCondition.oneOf("sibling", " ").holds(null),
                "ONE_OF(\" \") must hold false on a missing sibling for the same reason as EQUALS(\" "
                        + "\") above");
    }

    @Test
    void rejectsSelfReference() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(mode(), conditional("url", PropertyCondition.present("url"), null))));
        assertTrue(failure.getMessage().contains("refers to itself"), failure.getMessage());
    }

    /**
     * The operand check the positive-only operator set exists to make possible. A typo here fails the
     * load; under a negated operator it would silently widen visibility instead.
     */
    @Test
    void rejectsAnOperandOutsideTheSiblingsAllowedValues() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(mode(), conditional("url", PropertyCondition.equalTo("mode", "WEBHOKE"), null))));
        assertTrue(failure.getMessage().contains("not one of that property's allowed values"),
                failure.getMessage());
    }

    @Test
    void rejectsATwoNodeCycle() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(conditional("a", PropertyCondition.present("b"), null),
                        conditional("b", PropertyCondition.present("a"), null))));
        assertTrue(failure.getMessage().contains("conditional cycle"), failure.getMessage());
    }

    @Test
    void rejectsAThreeNodeCycle() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(conditional("a", PropertyCondition.present("b"), null),
                        conditional("b", PropertyCondition.present("c"), null),
                        conditional("c", PropertyCondition.present("a"), null))));
        assertTrue(failure.getMessage().contains("conditional cycle"), failure.getMessage());
    }

    /** A chain is not a cycle; rejecting it would forbid the ordinary case. */
    @Test
    void acceptsAnAcyclicChain() {
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(
                type(conditional("a", PropertyCondition.present("b"), null),
                        conditional("b", PropertyCondition.present("c"), null),
                        NodePropertyDescriptor.optional("c", "C", NodePropertyType.STRING, "d", ""))));
    }

    /**
     * Required-but-invisible is a field nobody can fill: with mode == LONG_POLLING, requiredWhen
     * holds and visibleWhen does not, a genuine conflict rather than one of the two cases where
     * satisfyingSetContained rejects a pairing that is actually safe. The message is
     * deliberately hedged for every rejection, not only the provably-safe ones, because the
     * conservative check cannot itself tell which case it is in -- see
     * {@link #rejectsEqualsOnTheBlankStringAgainstBlankButTheTwoConditionsAcceptTheSameValues}.
     */
    @Test
    void rejectsRequiredInAStateWhereItIsInvisible() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(mode(), conditional("url",
                        PropertyCondition.equalTo("mode", "WEBHOOK"),
                        PropertyCondition.oneOf("mode", "WEBHOOK", "LONG_POLLING")))));
        assertTrue(failure.getMessage().contains("mandatory in a state where no editor shows it"),
                failure.getMessage());
    }

    /**
     * {@code satisfyingSetContained} is a conservative, syntax-only comparison of operators
     * and operand lists, not of the real satisfying sets that {@link PropertyCondition#holds}
     * defines. This pins one of the two cases where that gap makes the check reject
     * a pairing whose sets are, by {@code holds}'s own semantics, identical: {@code requiredWhen}
     * tests {@code EQUALS} on the blank string, {@code visibleWhen} tests {@code BLANK}. Both accept
     * exactly the same sibling values, proved below by evaluating {@code holds} over representative
     * inputs rather than by trusting either the predicate or the message's own claim about itself.
     *
     * <p>The predicate stays fail-closed here on purpose; closing this gap requires widening
     * {@code satisfyingSetContained} and is separate, larger work. This test therefore does
     * not assert the descriptor is accepted -- it still is not. What ties the message to the
     * predicate is what the message must no longer claim: the previous wording asserted the conflict
     * as an established fact ("requiredWhen holds in states where visibleWhen does not"), which the
     * {@code holds} comparison below disproves for this pairing. A message assertion beyond that is
     * necessarily a string match on prose; an exact-string assertion tracks wording, not behaviour.
     * This therefore pins only the load-bearing phrase -- that the check could not establish
     * containment -- not the sentence around it.
     */
    @Test
    void rejectsEqualsOnTheBlankStringAgainstBlankButTheTwoConditionsAcceptTheSameValues() {
        var required = PropertyCondition.equalTo("comment", "");
        var visible = PropertyCondition.blank("comment");
        for (String candidate : new String[] {null, "", " ", "\t", "anything", "x"}) {
            assertEquals(visible.holds(candidate), required.holds(candidate),
                    "EQUALS(\"\") and BLANK must accept the same sibling values, or this case proves "
                            + "nothing about the message overstating a conflict");
        }

        var comment = NodePropertyDescriptor.optional("comment", "Comment", NodePropertyType.STRING, "d", "");
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(comment, conditional("url", visible, required))));
        assertFalse(failure.getMessage().contains("holds in states where visibleWhen does not"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("could not establish"), failure.getMessage());
    }

    /**
     * The Javadoc bullet for {@code satisfyingSetContained} said "{@code EQUALS} on the blank string
     * denotes the same real condition as {@code BLANK}" without saying which blank string. Read
     * literally, {@code holds} strips the <em>sibling's</em> value, not
     * the operand, so only the empty-string operand collapses onto {@code BLANK} (pinned above by
     * {@link #rejectsEqualsOnTheBlankStringAgainstBlankButTheTwoConditionsAcceptTheSameValues}).
     * {@code EQUALS(" ")} does not: no stripped sibling value can ever equal the literal string
     * {@code " "}, so this condition holds for nothing at all -- including the blank inputs where
     * {@code BLANK} itself holds, proving the two are not interchangeable.
     */
    @Test
    void equalsOnASpaceOperandIsUnsatisfiableUnlikeEqualsOnTheEmptyString() {
        var equalsSpace = PropertyCondition.equalTo("comment", " ");
        var blank = PropertyCondition.blank("comment");
        for (String candidate : new String[] {null, "", " ", "\t", "anything", "x"}) {
            assertFalse(equalsSpace.holds(candidate),
                    "EQUALS(\" \") must hold for no sibling value: holds() strips the sibling's "
                            + "value before comparing and can never produce the literal string \" \"");
        }
        assertTrue(blank.holds(""), "sanity check: BLANK does hold on a blank sibling value");
    }

    /**
     * The class Javadoc's rule 5 used to claim the required-but-invisible check was "Decidable
     * precisely because the operator set is closed" -- the exact overclaim
     * {@code satisfyingSetContained}'s own javadoc, thirty lines below it in the same file,
     * disproves: it is a conservative, syntax-only comparison that can reject a genuinely safe pair
     * ({@link #rejectsEqualsOnTheBlankStringAgainstBlankButTheTwoConditionsAcceptTheSameValues}). A
     * javadoc claim has no runtime representation to assert against -- it is stripped at compile
     * time -- so this guards the source text directly, the way {@code GraphMlRejectionPolicyTest}
     * guards against a bypassed rejection path in {@code ravenroot-core}: not to track prose, but to
     * keep this specific false claim from being reintroduced.
     */
    @Test
    void classJavadocNoLongerClaimsRuleFiveIsDecidable() throws IOException {
        Path source = Path.of("src/main/java/ai/ravenroot/api/catalog/NodeTypeDescriptorValidator.java");
        assertTrue(Files.isRegularFile(source),
                "the guard cannot pass by failing to find the source at " + source.toAbsolutePath());
        String text = Files.readString(source);
        assertFalse(text.contains("Decidable precisely"),
                "the class javadoc must not claim rule 5 is decidable; satisfyingSetContained is a "
                        + "conservative approximation, not a decision procedure");
    }

    /**
     * Three sites carried the same overclaim (the class Javadoc's rule 5, the
     * {@code requiredImpliesVisible} method javadoc, and the {@code EQUALS}/{@code BLANK} bullet in
     * {@code satisfyingSetContained}'s Javadoc), but only the first had a source-text guard
     * (see {@link #classJavadocNoLongerClaimsRuleFiveIsDecidable} above). The other two were unguarded,
     * along with a third, weaker overclaim ("literally equivalent" for what is actually containment)
     * in rule 5's rewritten text. This test pins all three the same way and
     * carries the same limitation stated for the first: a javadoc claim has no runtime representation
     * to assert against, so this reads the source as text and can only catch the exact prior wording
     * reappearing verbatim, not a paraphrase of it. It is a ratchet against wording that has already
     * regressed once, not a barrier against every way these claims could be overstated again.
     */
    @Test
    void classSourceNoLongerCarriesTheOtherOverclaimsHiddenInsideNumberFourSevenEight() throws IOException {
        Path source = Path.of("src/main/java/ai/ravenroot/api/catalog/NodeTypeDescriptorValidator.java");
        assertTrue(Files.isRegularFile(source),
                "the guard cannot pass by failing to find the source at " + source.toAbsolutePath());
        String text = Files.readString(source);
        assertFalse(text.contains("Conservative and decidable"),
                "requiredImpliesVisible's javadoc must not claim the check is decidable; it is a "
                        + "conservative, syntax-only comparison that can reject a genuinely safe pair");
        assertFalse(text.contains("normalises a blank or absent sibling value to the same state"),
                "satisfyingSetContained's javadoc must not claim EQUALS on an unqualified \"blank "
                        + "string\" collapses onto BLANK; only the empty-string operand does -- "
                        + "EQUALS(\" \") is unsatisfiable and BLANK is not");
        assertFalse(text.contains("literally equivalent"),
                "the class javadoc must not describe satisfyingSetContained's check as equivalence; "
                        + "it verifies containment (required operands within visible ones), and "
                        + "EQUALS(\"A\") required against ONE_OF(\"A\",\"B\") visible is accepted "
                        + "though the two are not equivalent");
    }

    /**
     * Backs the class Javadoc's rule 5: {@code satisfyingSetContained} verifies containment,
     * not equivalence. {@code EQUALS("A")} required against {@code ONE_OF("A","B")} visible is
     * accepted here even though the two conditions are not equivalent -- {@code ONE_OF("A","B")}
     * also holds on {@code "B"}, where {@code EQUALS("A")} does not -- because every value satisfying
     * the required side ({@code "A"}) is contained in the visible side's operand set. A javadoc that
     * called this "literally equivalent" would misdescribe exactly this case as rejected.
     */
    @Test
    void requiredEqualsIsAcceptedAgainstAWiderVisibleOneOfWithoutBeingEquivalentToIt() {
        var conditioned = new NodePropertyDescriptor("url", "URL", NodePropertyType.STRING, false, "d", "",
                List.of(), false, PropertyCondition.oneOf("mode", "LONG_POLLING", "WEBHOOK"),
                PropertyCondition.equalTo("mode", "WEBHOOK"));
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(mode(), conditioned)));
    }

    @Test
    void rejectsRequiredWhenAndVisibleWhenOnDifferentSiblings() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(mode(), NodePropertyDescriptor.optional("other", "O", NodePropertyType.STRING, "d", ""),
                        conditional("url", PropertyCondition.equalTo("mode", "WEBHOOK"),
                                PropertyCondition.present("other")))));
        assertTrue(failure.getMessage().contains("must name the same"), failure.getMessage());
    }

    @Test
    void rejectsAConditionallyRequiredPropertyThatAlsoCarriesADefault() {
        var withDefault = new NodePropertyDescriptor("url", "URL", NodePropertyType.STRING, false, "d",
                "https://example.invalid", List.of(), false, null,
                PropertyCondition.equalTo("mode", "WEBHOOK"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(type(mode(), withDefault)));
        assertTrue(failure.getMessage().contains("defaultValue"), failure.getMessage());
    }

    /**
     * Ties {@code requiredWhenHasNoDefault}'s rejection to the predicate that actually guards it,
     * {@code isBlank()}, rather than to the message's wording: the message said "non-empty"
     * while the code tested {@code isBlank()} — a divergence a string assertion on the message would
     * not have caught, since both wordings satisfy {@code contains("defaultValue")} above). A
     * whitespace-only default is blank, so it must be accepted here exactly as an absent default is.
     * This is the same {@code isBlank()} -> {@code isEmpty()} mutation
     * {@link #acceptsAPureWhitespaceDefaultTheSameWayAsABlankOne} kills for
     * {@code defaultValueIsAdmissible}, but that test's property carries no {@code requiredWhen} and
     * never reaches this method, so this boundary was unguarded until now. Under {@code isEmpty()} a
     * whitespace default would read as "non-empty" and this descriptor would be wrongly REJECTED.
     */
    @Test
    void acceptsRequiredWhenPairedWithAWhitespaceOnlyDefault() {
        var whitespaceDefault = new NodePropertyDescriptor("url", "URL", NodePropertyType.STRING, false, "d",
                " ", List.of(), false, null, PropertyCondition.equalTo("mode", "WEBHOOK"));
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(mode(), whitespaceDefault)));
    }

    /**
     * A default that is not one of the property's own allowedValues can never be the value the field
     * actually holds, the same incoherence as a required-and-defaulted pair.
     */
    @Test
    void rejectsADefaultValueOutsideItsOwnAllowedValues() {
        var stray = new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, false, "d",
                "STRAY", MODES, false, null, null);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(type(stray)));
        assertTrue(failure.getMessage().contains("mode"), failure.getMessage());
        assertTrue(failure.getMessage().contains("STRAY"), failure.getMessage());
        assertTrue(failure.getMessage().contains("not one of its own allowedValues"), failure.getMessage());
        assertTrue(failure.getMessage().contains(MODES.toString()), failure.getMessage());
    }

    /** A blank default means "not declared" and is unaffected by the allowedValues check. */
    @Test
    void acceptsABlankDefaultRegardlessOfAllowedValues() {
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(mode())));
    }

    /**
     * Kills the {@code isBlank()} -> {@code isEmpty()} mutation the gate found surviving: under
     * {@code isEmpty()} a pure-whitespace default is non-empty and outside {@code MODES}, so this
     * descriptor would be REJECTED — reopening a disagreement with the editor, where a whitespace-only
     * default already reads as "not declared".
     * {@code isBlank()} is what makes the two sides agree: this must stay {@code assertDoesNotThrow}.
     */
    @Test
    void acceptsAPureWhitespaceDefaultTheSameWayAsABlankOne() {
        var whitespaceDefault = new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, false, "d",
                " ", MODES, false, null, null);
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(whitespaceDefault)));
    }

    /** A default that is a member of allowedValues is the ordinary, admissible case. */
    @Test
    void acceptsADefaultValueThatIsAMemberOfAllowedValues() {
        var withDefault = new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, false, "d",
                "WEBHOOK", MODES, false, null, null);
        assertDoesNotThrow(() -> NodeTypeDescriptorValidator.validate(type(withDefault)));
    }

    @Test
    void rejectsAnUnknownConditionContract() {
        var alien = new NodePropertyDescriptor("url", "URL", NodePropertyType.STRING, false, "d", "",
                List.of(), false,
                new PropertyCondition("ravenroot.property-condition/99", "mode",
                        PropertyConditionOperator.EQUALS, List.of("WEBHOOK")), null);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(type(mode(), alien)));
        assertTrue(failure.getMessage().contains("unknown contract"), failure.getMessage());
    }

    @Test
    void rejectsDuplicatePropertyNames() {
        var failure = assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(
                type(NodePropertyDescriptor.optional("dup", "D", NodePropertyType.STRING, "d", ""),
                        NodePropertyDescriptor.optional("dup", "D", NodePropertyType.STRING, "d", ""))));
        assertTrue(failure.getMessage().contains("more than once"), failure.getMessage());
    }

    private static NodePropertyDescriptor mode() {
        return new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, true, "d", "",
                MODES, false, null, null);
    }

    private static NodePropertyDescriptor conditional(String name, PropertyCondition visible,
                                                      PropertyCondition required) {
        return new NodePropertyDescriptor(name, name, NodePropertyType.STRING, false, "d", "",
                List.of(), false, visible, required);
    }

    private static NodeTypeDescriptor type(NodePropertyDescriptor... properties) {
        return new NodeTypeDescriptor("test.behavior", "Test", "General", "d", "actor", false,
                List.of(properties), Set.of());
    }
}
