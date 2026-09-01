package ai.ravenroot.core.graph;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The minimal-structure rule is implemented twice, in two languages, and this is what stops the two
 * from drifting apart.
 *
 * <h2>The claim this exists to falsify</h2>
 * <p>Moving the error-terminal cardinality is not a one-line change in {@link GraphDefinition}:
 * moving the floor from one to zero took a line in Java, a line in the editor and a change to the
 * shape of this comparison. The
 * editor enforces the same rule independently in {@code ravenroot-ui/src/graph-document.js}, and
 * nothing connected the two: raising the Java bound would leave the editor refusing documents the
 * runtime accepts, and the divergence would land silently because neither side's tests can see the
 * other's.</p>
 *
 * <h2>Why it lives on this side of the boundary</h2>
 * <p>Only this side can execute both halves. The editor's suite cannot call the Java validator; this
 * one can read the editor's source. So the editor's rule is treated as the <em>declared</em>
 * contract — parsed out of the file — and the Java validator is then <em>run</em> against it. That is
 * deliberately not a comparison of two constants: a constant-to-constant check passes when both are
 * wrong together, while this fails whenever the behaviour on this side stops matching the number
 * written on the other.</p>
 *
 * <h2>Reading a rule, versus finding something that looks like one</h2>
 * <p>The parser below is narrow on purpose, and this is the second shape it has had. The first read
 * only a literal in the comparison; when the editor named the count instead — the right change, and
 * one this test could not have prevented — the parser stopped recognising the {@code ERROR} rule and
 * the suite went <strong>red</strong> rather than quietly checking two rules out of three and
 * reporting success. That behaviour is the asset, so it is preserved deliberately: every shape this
 * parser cannot resolve <em>with certainty</em> is a failure, never a silent omission. A regex loose
 * enough to always find something would be worse than no test at all, because it would stop
 * distinguishing "the rule is there" from "I misread the file".</p>
 *
 * <h2>The {@code ERROR} rule changed form, and this class exposes any mismatch</h2>
 * <p>The obligation fell: a graph without an error terminal is accepted now, the ceiling of one
 * stays, and the editor's comparison changed from {@code !== REQUIRED_ERROR_NODE_COUNT} to
 * {@code > MAX_ERROR_NODE_COUNT}. That is a change of <strong>form</strong>, not of number, and this
 * class is written to refuse to absorb one — the previous {@link #assertJavaHonours} asserted the
 * operator was {@code !==} and failed outright on anything else. It was widened <em>deliberately</em>
 * rather than loosened: {@code DeclaredRule.acceptedMin()} learned exactly one new form, still
 * refuses every other, and the probes were restated against a <em>range</em> so that the floor is
 * checked separately from the ceiling. The reason that matters is arithmetic: "exactly one" and "at
 * most one" agree about every count except zero, so a guard that probed only the declared count and
 * its neighbours would have passed on both forms and noticed nothing.</p>
 *
 * <p>Consequence worth stating plainly, because it is the point: change {@code MIN_ERROR_NODES} or
 * {@code MAX_ERROR_NODES} in {@link GraphDefinition} without touching the editor, or change the
 * editor's count or the shape of its comparison without touching Java, and
 * {@link #theJavaValidatorEnforcesExactlyWhatTheEditorDeclares()} fails. The two directions have
 * <strong>different</strong> evidence:</p>
 * <ul>
 *   <li><strong>Editor moves, Java does not</strong> — pinned automatically, twice over. Its number:
 *       {@link #detectsAnEditorSideChangeThisSideDidNotFollow()}. Its form:
 *       {@link #detectsAnEditorSideFormChangeThisSideDidNotFollow()}, which rewrites
 *       the ceiling back into an equality and asserts the guard notices. Both work on an in-memory
 *       rewrite of the real file.</li>
 *   <li><strong>Java moves, the editor does not</strong> — <em>no</em> automatic pin exists, because
 *       every manipulation available from inside a test moves the declared side rather than the
 *       enforced one. Verified by mutation in both directions and recorded on
 *       {@link #detectsAnEditorSideChangeThisSideDidNotFollow()}, not asserted here.</li>
 * </ul>
 *
 * <p>The distinction prevents a recorded mutation from being mistaken for an automatic guard. Only
 * the editor-to-Java direction is exercised inside this test; the reverse direction remains external
 * mutation evidence.</p>
 */
class MinimalStructureParityTest {

    /**
     * The editor's validator, as source. Read from disk rather than copied into this module's
     * resources: a copy is a second thing to keep in step, which is the failure being prevented.
     */
    private static final Path EDITOR_VALIDATOR =
            repoRoot().resolve("ravenroot/ravenroot-ui/src/graph-document.js");

    /**
     * The kinds whose count {@link GraphDefinition} constrains, and therefore the ones the editor has
     * to agree with.
     *
     * <p>This list and {@link #UNCONSTRAINED_KINDS} are a <em>partition</em> of
     * {@link NodeKind#values()}, checked by
     * {@link #everyNodeKindIsClassifiedAndTheUnconstrainedOnesReallyAre()}. That check is what makes
     * the hand-written membership safe: a kind added to the enum belongs to neither list, the
     * partition fails, and someone has to say which side it is on. Without it this test would have a
     * blind spot the size of every future kind — and this branch is the proof the enum grows, since
     * {@code ERROR} is why it exists.</p>
     *
     * <p>Membership is not merely declared, either: the unconstrained kinds are <em>run</em> through
     * the validator to confirm no rule counts them, so a kind filed on the wrong side fails rather
     * than being quietly exempted.</p>
     */
    private static final List<NodeKind> CARDINALITY_CONSTRAINED_KINDS =
            List.of(NodeKind.START, NodeKind.END, NodeKind.ERROR);

    /** @see #CARDINALITY_CONSTRAINED_KINDS */
    private static final List<NodeKind> UNCONSTRAINED_KINDS =
            List.of(NodeKind.PASSTHROUGH, NodeKind.BEHAVIOR);

    /**
     * {@code if (<collection>.length <op> <operand>)} — the head of a cardinality rule. The operand is
     * captured as written, an integer literal or an identifier, and resolved separately; the operator
     * is captured rather than assumed, because it is what distinguishes an exact count from a bound —
     * which is exactly what changed for {@code ERROR}.
     *
     * <p>{@code .length} is anchored immediately after the open parenthesis, so compound conditions
     * ({@code data && data.children.length > 0}) and nested properties
     * ({@code child.children.length}) do not match. They are not cardinality rules and a parser that
     * accepted them would be guessing.</p>
     */
    private static final Pattern RULE_HEAD = Pattern.compile(
            "if\\s*\\(\\s*(\\w+)\\.length\\s*(!==|===|!=|==|<=|>=|<|>)\\s*([A-Za-z_$][\\w$]*|\\d+)\\s*\\)");

    private record DeclaredRule(String collection, String operator, String operand, int count, String body) {

        /**
         * The smallest count the editor accepts, and the largest, read from the operator rather than
         * assumed. The rule head names the condition under which the editor <em>rejects</em>, so the
         * accepted range is its complement.
         *
         * <h2>Two forms are understood, and everything else is a failure</h2>
         * <p>{@code !== N} and {@code != N} state an exact count: accepted range {@code [N, N]}.
         * {@code > N} and {@code >= N} state a ceiling with no floor: accepted range {@code [0, N]}
         * and {@code [0, N-1]}. Those are the two shapes the editor has used, and each is resolved
         * exactly.</p>
         *
         * <p><strong>Every other operator throws</strong>, including ones that are perfectly sensible
         * JavaScript. {@code < N} and {@code <= N} state a floor with no ceiling, which this test
         * cannot probe from above and would have to assert something weaker about; {@code === N}
         * rejects one specific count and accepts every other, which is not a cardinality rule in any
         * useful sense. Refusing them is the same discipline the parser applies to an operand it
         * cannot resolve: a shape that cannot be checked <em>with certainty</em> is a red suite and a
         * human, never a quieter assertion.</p>
         *
         * <p>The set was widened deliberately when the editor
         * moved from {@code !==} to {@code >}, this method learned {@code >}, and
         * {@link #detectsAnEditorSideFormChangeThisSideDidNotFollow()} was added so that moving
         * <em>back</em> — an editor that requires an error node while Java accepts none — fails
         * automatically rather than being absorbed the next time.</p>
         */
        int acceptedMin() {
            return switch (operator) {
                case "!==", "!=" -> count;
                case ">", ">=" -> 0;
                default -> throw unreadableForm();
            };
        }

        /** @see #acceptedMin() */
        int acceptedMax() {
            return switch (operator) {
                case "!==", "!=" -> count;
                case ">" -> count;
                case ">=" -> count - 1;
                default -> throw unreadableForm();
            };
        }

        private AssertionError unreadableForm() {
            return new AssertionError("The editor states its cardinality rule as '" + collection
                    + ".length " + operator + " " + operand + "', a form this test does not know how to"
                    + " probe: it understands an exact count (!==, !=) and a ceiling (>, >=), and"
                    + " nothing else. That is deliberate -- see DeclaredRule.acceptedMin(). If this is"
                    + " the shape the rule should now have, teach this method that form and mirror it"
                    + " in GraphDefinition's MIN/MAX_ERROR_NODES on purpose, rather than loosening the"
                    + " check until it stops noticing.");
        }
    }

    @TestFactory
    Stream<DynamicTest> theJavaValidatorEnforcesExactlyWhatTheEditorDeclares() {
        Map<NodeKind, DeclaredRule> declared = parseEditorRules(readEditorSource());
        return CARDINALITY_CONSTRAINED_KINDS.stream().map(kind -> DynamicTest.dynamicTest(kind.name(), () -> {
            DeclaredRule rule = declared.get(kind);
            if (rule == null) {
                fail("no cardinality rule for " + kind + " found in " + EDITOR_VALIDATOR
                        + "; either the editor stopped enforcing it, or it is now written in a shape"
                        + " this test cannot read -- both need a human, neither is a pass");
            }
            assertJavaHonours(kind, rule);
        }));
    }

    /**
     * Guards the guard. Every assertion above is conditional on the parser recognising something, so
     * a reformulation it cannot read would otherwise turn the factory into a pass over a short list.
     * Three rules are expected; finding fewer fails here rather than going unnoticed there.
     */
    @Test
    void theEditorSourceIsStillReadableAndStillDeclaresAllThreeRules() {
        Map<NodeKind, DeclaredRule> declared = parseEditorRules(readEditorSource());
        assertEquals(CARDINALITY_CONSTRAINED_KINDS, List.copyOf(declared.keySet()),
                () -> "expected the editor to declare a cardinality rule for each terminal kind; parsed "
                        + declared.keySet() + " from " + EDITOR_VALIDATOR);
    }

    /**
     * Closes this guard's own blind spot: a kind added to {@link NodeKind} that nobody thought to
     * check.
     *
     * <p>The list of kinds the parity check walks is written by hand, and a hand-written list of enum
     * constants goes stale the moment the enum grows — which is not hypothetical here, since
     * {@code ERROR} joining {@link NodeKind} is the entire reason this class exists. Deriving the
     * <em>classification</em> is impossible: nothing in the enum says whether a new kind ought to be
     * counted. Deriving the <em>obligation to classify</em> is not, and that is what this does — the
     * two lists must partition {@link NodeKind#values()} exactly, so a new constant belongs to
     * neither, this fails, and a human decides which side it is on before the suite goes green
     * again.</p>
     *
     * <p>The second half stops the classification from being a claim. Every kind filed as
     * unconstrained is run through the validator at zero and at two, and must be accepted at both: a
     * kind quietly filed on the unconstrained side <em>because</em> nobody wanted to add a rule for it
     * fails here rather than being exempted by assertion.</p>
     */
    @Test
    void everyNodeKindIsClassifiedAndTheUnconstrainedOnesReallyAre() {
        var classified = new LinkedHashSet<NodeKind>(CARDINALITY_CONSTRAINED_KINDS);
        UNCONSTRAINED_KINDS.forEach(kind -> assertTrue(classified.add(kind),
                () -> kind + " is classified both as cardinality-constrained and as unconstrained"));

        assertEquals(Set.of(NodeKind.values()), Set.copyOf(classified),
                "a NodeKind was added or removed without saying whether its count is constrained."
                        + " Decide: add it to CARDINALITY_CONSTRAINED_KINDS if GraphDefinition counts"
                        + " it -- and then the editor must count it too -- or to UNCONSTRAINED_KINDS if"
                        + " it does not. Leaving it unclassified is how this guard would acquire a"
                        + " blind spot exactly the size of the next kind");

        // Control, and it has to come first. Every probe below builds on a base graph made of one
        // node per constrained kind, so if a kind the validator counts has been moved OUT of that
        // list the base is already invalid -- and each probe would then report the refusal against
        // whichever unconstrained kind it happened to be testing, which is the wrong culprit. Ask the
        // base directly, so a misfiled constrained kind is named as one instead of framing a bystander.
        try {
            baseGraph();
        } catch (GraphValidationException refused) {
            fail("the base graph built from CARDINALITY_CONSTRAINED_KINDS -- one node of each -- is"
                    + " itself refused: " + refused.violations() + ". A kind whose count the validator"
                    + " constrains is missing from that list; put it back before reading any failure"
                    + " below, which would otherwise blame whichever kind it was probing");
        }

        for (NodeKind kind : UNCONSTRAINED_KINDS) {
            for (int count : new int[] {0, 2}) {
                try {
                    graphWith(kind, count);
                } catch (GraphValidationException refused) {
                    fail(kind + " is filed as unconstrained, but the validator refused a graph with "
                            + count + " of them: " + refused.violations()
                            + ". Either it is constrained after all -- in which case it belongs in the"
                            + " other list and the editor has to agree about it -- or the refusal is a"
                            + " defect");
                }
            }
        }
    }

    /** One node of each cardinality-constrained kind, and nothing else: the shape every probe starts from. */
    private static GraphDefinition baseGraph() {
        var nodes = new ArrayList<GraphNode>();
        CARDINALITY_CONSTRAINED_KINDS.forEach(kind -> nodes.add(node(kind, 0)));
        return new GraphDefinition(nodes, List.of());
    }

    /**
     * The direction that exposes the asymmetry:
     * the editor's number moves and this side does not. Run against an <em>in-memory</em> rewrite of
     * the real file, so nothing here edits it, not even transiently.
     *
     * <p>The opposite direction — the Java bound moves and the editor does not — is caught by the
     * "refused one above" probe inside {@link #assertJavaHonours}, and cannot be simulated from
     * inside a test, because every manipulation available here moves the <em>declared</em> side
     * rather than the enforced one. It is verified by mutation instead, with a second recorded result
     * because the rule now has a floor as well as a ceiling. Both are
     * recorded mutation results, not something this class asserts:</p>
     * <ul>
     *   <li>{@code GraphDefinition.MAX_ERROR_NODES = Integer.MAX_VALUE} — the apparent one-line
     *       widening — fails the {@code ERROR} case of
     *       {@link #theJavaValidatorEnforcesExactlyWhatTheEditorDeclares()} with "the editor refuses 2
     *       ERROR node(s) and this side accepts them".</li>
     *   <li>{@code GraphDefinition.MIN_ERROR_NODES = 1} while the editor stops requiring the terminal
     *       fails the same case with "the editor accepts 0 ERROR
     *       node(s) and this side refuses that count".</li>
     * </ul>
     */
    @Test
    void detectsAnEditorSideChangeThisSideDidNotFollow() {
        String source = readEditorSource();
        String raised = source.replace("export const MAX_ERROR_NODE_COUNT = 1;",
                "export const MAX_ERROR_NODE_COUNT = 2;");
        assertTrue(!raised.equals(source),
                () -> "the constant this rewrite targets is no longer written that way in "
                        + EDITOR_VALIDATOR + "; this test is rewriting nothing and proving nothing");

        DeclaredRule rule = parseEditorRules(raised).get(NodeKind.ERROR);
        assertEquals(2, rule.count(), "the parser must follow the constant to its value, not to its name");

        var caught = assertThrows(AssertionError.class, () -> assertJavaHonours(NodeKind.ERROR, rule));
        assertTrue(caught.getMessage().contains("refuses that count"), caught.getMessage());
    }

    /**
     * The form-change direction: the editor changes the
     * <em>form</em> of its comparison, not its number, and this side does not follow.
     *
     * <p>The rewrite below turns the ceiling back into an equality — {@code errors.length >
     * MAX_ERROR_NODE_COUNT} into {@code errors.length !== MAX_ERROR_NODE_COUNT} — which is precisely
     * a state where the editor requires a terminal while Java accepts none.
     * The number is identical in both forms, and so is the verdict on every count except zero; an
     * {@link #assertJavaHonours} probing only the declared count and its immediate neighbours passes
     * on both. This test fails because the floor is probed
     * separately from the ceiling.</p>
     *
     * <p>This is the automatic half of the mutation evidence. Its counterpart — the same
     * divergence introduced from the Java side — remains a recorded mutation on
     * {@link #detectsAnEditorSideChangeThisSideDidNotFollow()}, for the reason given there.</p>
     */
    @Test
    void detectsAnEditorSideFormChangeThisSideDidNotFollow() {
        String source = readEditorSource();
        String required = source.replace("if (errors.length > MAX_ERROR_NODE_COUNT) {",
                "if (errors.length !== MAX_ERROR_NODE_COUNT) {");
        assertTrue(!required.equals(source),
                () -> "the comparison this rewrite targets is no longer written that way in "
                        + EDITOR_VALIDATOR + "; this test is rewriting nothing and proving nothing");

        DeclaredRule rule = parseEditorRules(required).get(NodeKind.ERROR);
        assertEquals(1, rule.acceptedMin(),
                "the rewritten rule states an exact count, so its floor is that count and not zero");

        var caught = assertThrows(AssertionError.class, () -> assertJavaHonours(NodeKind.ERROR, rule));
        assertTrue(caught.getMessage().contains("requires at least 1 ERROR node(s) and this side accepts 0"),
                caught.getMessage());
    }

    /**
     * A form this test cannot probe is a failure, not a weaker assertion. The complement of
     * {@link #detectsAnEditorSideFormChangeThisSideDidNotFollow()}: that one covers a form change this
     * class understands and rejects on the merits; this one covers a form change it does
     * <em>not</em> understand, and pins that the answer is a red suite rather than a guess.
     */
    @Test
    void refusesToProbeARuleFormItDoesNotUnderstand() {
        String source = readEditorSource();
        String floorOnly = source.replace("if (errors.length > MAX_ERROR_NODE_COUNT) {",
                "if (errors.length < MAX_ERROR_NODE_COUNT) {");
        assertTrue(!floorOnly.equals(source),
                () -> "the comparison this rewrite targets is no longer written that way in "
                        + EDITOR_VALIDATOR + "; this test is rewriting nothing and proving nothing");

        DeclaredRule rule = parseEditorRules(floorOnly).get(NodeKind.ERROR);
        var refusal = assertThrows(AssertionError.class, () -> assertJavaHonours(NodeKind.ERROR, rule));
        assertTrue(refusal.getMessage().contains("a form this test does not know how to probe"),
                refusal.getMessage());
    }

    /**
     * A bound the parser cannot resolve to a number is a failure, not an omission. Without this, the
     * only thing standing between "unreadable" and "silently absent" would be the count in
     * {@link #theEditorSourceIsStillReadableAndStillDeclaresAllThreeRules()}, and a future rule added
     * in an unreadable shape would keep that count right while being unchecked.
     */
    @Test
    void refusesToReadARuleWhoseBoundItCannotResolve() {
        String opaque = readEditorSource().replace("export const MAX_ERROR_NODE_COUNT = 1;",
                "export const MAX_ERROR_NODE_COUNT = LIMITS.errorNodes;");

        var refusal = assertThrows(IllegalStateException.class, () -> parseEditorRules(opaque));
        assertTrue(refusal.getMessage().contains("MAX_ERROR_NODE_COUNT"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("needs a human"), refusal.getMessage());
    }

    /**
     * Runs the Java validator against the range the editor declares: accepted at both ends of it,
     * refused one above the ceiling, and refused one below the floor where a below exists. Throws
     * {@link AssertionError} on divergence, which is what lets the three sensitivity tests above
     * assert that it is sensitive.
     *
     * <p>The three probes are restated against a range instead of a
     * single count — because a rule that says "at most one" and a rule that says "exactly one" agree
     * about 1 and about 2 and differ only about 0, so a check that only probed the declared count and
     * its neighbours-of-one would have been satisfied by both. The floor probe is where the two forms
     * part company, and it is the load-bearing probe.</p>
     */
    private static void assertJavaHonours(NodeKind kind, DeclaredRule rule) {
        int min = rule.acceptedMin();
        int max = rule.acceptedMax();
        assertTrue(min <= max, () -> "the editor's rule for " + kind + " (" + rule.collection()
                + ".length " + rule.operator() + " " + rule.operand() + ") accepts no count at all,"
                + " which cannot be what it means");

        for (int accepted : new int[] {min, max}) {
            try {
                graphWith(kind, accepted);
            } catch (GraphValidationException refused) {
                fail("the editor accepts " + accepted + " " + kind + " node(s) and this side refuses"
                        + " that count: " + refused.violations());
            }
        }

        var above = assertThrows(GraphValidationException.class, () -> graphWith(kind, max + 1),
                () -> "the editor refuses " + (max + 1) + " " + kind
                        + " node(s) and this side accepts them");
        assertTrue(above.violations().stream().anyMatch(violation -> mentions(violation, kind)),
                () -> "refused, but for some other reason: " + above.violations());

        if (min > 0) {
            var below = assertThrows(GraphValidationException.class, () -> graphWith(kind, min - 1),
                    () -> "the editor requires at least " + min + " " + kind
                            + " node(s) and this side accepts " + (min - 1));
            assertTrue(below.violations().stream().anyMatch(violation -> mentions(violation, kind)),
                    () -> "refused, but for some other reason: " + below.violations());
        }
    }

    private static boolean mentions(String violation, NodeKind kind) {
        return violation.toLowerCase(Locale.ROOT).contains(kind.name().toLowerCase(Locale.ROOT));
    }

    /**
     * A graph carrying {@code count} nodes of {@code kind} and exactly one of every other terminal, so
     * the only rule under test is the one for {@code kind}. Edgeless on purpose: edges have their own
     * violations and would blur which rule refused the graph.
     */
    private static GraphDefinition graphWith(NodeKind kind, int count) {
        var nodes = new ArrayList<GraphNode>();
        for (NodeKind constrained : CARDINALITY_CONSTRAINED_KINDS) {
            int howMany = constrained == kind ? count : 1;
            for (int index = 0; index < howMany; index++) {
                nodes.add(node(constrained, index));
            }
        }
        if (!CARDINALITY_CONSTRAINED_KINDS.contains(kind)) {
            for (int index = 0; index < count; index++) {
                nodes.add(node(kind, index));
            }
        }
        return new GraphDefinition(nodes, List.of());
    }

    /** A {@code BEHAVIOR} node must name a behavior; every other kind must not. */
    private static GraphNode node(NodeKind kind, int index) {
        String id = kind.name().toLowerCase(Locale.ROOT) + "-" + index;
        return new GraphNode(id, kind, kind == NodeKind.BEHAVIOR ? "noop" : null);
    }

    /**
     * Extracts one cardinality rule per terminal kind from the editor's source.
     *
     * <p>A candidate whose body names no terminal kind is skipped — the file has other
     * {@code .length} guards, such as the one-top-level-graph check in {@code serializeGraphML}, and
     * they are not cardinality rules. Everything else is either resolved exactly or refused: a body
     * naming two kinds is ambiguous, and an operand that does not resolve to an integer literal is
     * unreadable. Both throw.</p>
     */
    static Map<NodeKind, DeclaredRule> parseEditorRules(String source) {
        var rules = new LinkedHashMap<NodeKind, DeclaredRule>();
        Matcher matcher = RULE_HEAD.matcher(source);
        while (matcher.find()) {
            String body = bodyAfter(source, matcher.end());
            Set<NodeKind> named = kindsNamedIn(body);
            if (named.isEmpty()) {
                continue;
            }
            if (named.size() > 1) {
                throw new IllegalStateException("A cardinality rule in " + EDITOR_VALIDATOR
                        + " names more than one terminal kind " + named + ", so which one it constrains"
                        + " cannot be read with certainty; this needs a human rather than a guess. Rule: "
                        + matcher.group());
            }
            NodeKind kind = named.iterator().next();
            rules.putIfAbsent(kind, new DeclaredRule(matcher.group(1), matcher.group(2), matcher.group(3),
                    resolveOperand(source, matcher.group(3)), body));
        }
        // Reported in a stable order so a failure message reads the same on every run.
        var ordered = new LinkedHashMap<NodeKind, DeclaredRule>();
        CARDINALITY_CONSTRAINED_KINDS.stream().filter(rules::containsKey).forEach(kind -> ordered.put(kind, rules.get(kind)));
        return ordered;
    }

    /**
     * The operand's numeric value: the literal itself, or the value of the {@code const} the editor
     * named. Naming the count instead of inlining it is the better style and the editor moved to it;
     * following the name to its value is what keeps this a measurement of the number rather than of
     * the spelling.
     */
    private static int resolveOperand(String source, String operand) {
        if (operand.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(operand);
        }
        var definition = Pattern.compile(
                        "(?:export\\s+)?const\\s+" + Pattern.quote(operand) + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(source);
        if (!definition.find()) {
            throw new IllegalStateException("The editor's cardinality rule in " + EDITOR_VALIDATOR
                    + " is bounded by '" + operand + "', which does not resolve to an integer literal"
                    + " declared in the same file. The rule may well still be correct, but this test"
                    + " cannot read it with certainty and will not pretend otherwise: this needs a human"
                    + " rather than a looser pattern.");
        }
        int value = Integer.parseInt(definition.group(1));
        if (definition.find()) {
            throw new IllegalStateException("The editor declares '" + operand + "' more than once in "
                    + EDITOR_VALIDATOR + ", so which declaration bounds the rule cannot be read with"
                    + " certainty; this needs a human rather than the first match.");
        }
        return value;
    }

    /** Whole-word kind names. {@code REQUIRED_ERROR_NODE_COUNT} does not match: {@code _} is a word character. */
    private static Set<NodeKind> kindsNamedIn(String body) {
        var named = new LinkedHashSet<NodeKind>();
        for (NodeKind kind : CARDINALITY_CONSTRAINED_KINDS) {
            if (Pattern.compile("\\b" + kind.name() + "\\b").matcher(body).find()) {
                named.add(kind);
            }
        }
        return named;
    }

    /**
     * The statement or block governed by a rule head, delimited exactly rather than by a window of
     * following characters: a braced body is matched by counting braces, a bare statement ends at its
     * first semicolon. String and template literals are skipped while counting, so a brace or
     * semicolon inside a message is not mistaken for structure.
     */
    private static String bodyAfter(String source, int from) {
        int cursor = from;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= source.length()) {
            throw new IllegalStateException("A cardinality rule in " + EDITOR_VALIDATOR
                    + " has no body; the file is truncated or this test is reading it wrong, and either"
                    + " needs a human.");
        }
        boolean braced = source.charAt(cursor) == '{';
        int start = cursor;
        int depth = 0;
        char quote = 0;
        for (; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (quote != 0) {
                if (current == '\\') {
                    cursor++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (braced && current == '{') {
                depth++;
            } else if (braced && current == '}') {
                if (--depth == 0) {
                    return source.substring(start, cursor + 1);
                }
            } else if (!braced && current == ';') {
                return source.substring(start, cursor + 1);
            }
        }
        throw new IllegalStateException("A cardinality rule in " + EDITOR_VALIDATOR
                + " has an unterminated body, so what it governs cannot be read with certainty;"
                + " this needs a human.");
    }

    private static String readEditorSource() {
        try {
            return Files.readString(EDITOR_VALIDATOR, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read the editor validator at " + EDITOR_VALIDATOR
                    + ". If it moved, this test moves with it: deleting it would remove the only thing"
                    + " holding the two implementations of the minimal-structure rule together.",
                    unreadable);
        }
    }

    private static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int hop = 0; hop < 6 && candidate != null; hop++) {
            if (Files.exists(candidate.resolve("AGENTS.md")) && Files.isDirectory(candidate.resolve("ravenroot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate the repository root above " + System.getProperty("user.dir"));
    }
}
