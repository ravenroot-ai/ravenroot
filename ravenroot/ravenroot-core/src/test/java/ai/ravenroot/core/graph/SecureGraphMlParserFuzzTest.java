package ai.ravenroot.core.graph;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static ai.ravenroot.core.graph.GraphMlRejection.declaredPublicMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA-07 coverage-generated fuzzing of the GraphML/TinkerPop import path,
 * on top of (not instead of) {@link GraphMlCorpusTest}'s fixed corpus and
 * {@link GraphManagerSecurityTest#deterministicMutationCorpusNeverEscapesTypedFailuresOrHangs}'s
 * existing hand-rolled byte-mutation fuzzer, which is deliberately left untagged and unchanged — see
 * that method for why.
 *
 * <p>Tagged {@code fuzz} and excluded from the default {@code mvn test} run (see the reactor
 * {@code pom.xml}'s {@code excludedGroups} and the {@code fuzz} profile that includes only this tag).
 * The budget per property is named on the property itself and enforced two ways: a {@code tries}
 * count bounding how many candidates are generated, and — for the three properties (1, 1b, 4) that
 * can hang on a single pathological candidate rather than merely run long in aggregate —
 * {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively} around each individual
 * invocation, so one hanging candidate cannot silently consume the whole run's budget.
 *
 * <h2>What jqwik buys here that the existing hand-rolled fuzzer does not</h2>
 * <p>Automatic shrinking. A failing candidate is reduced to a minimal reproducer before being
 * reported, so every defect produces a minimized regression fixture —
 * see this class's five red-control writeups (Properties 1,
 * 1b, 2, 3 and 4 below) for the shrunk counterexample each mutation actually produced.
 *
 * <h2>Why every property here has already found something real</h2>
 * <p>A fuzzer that only ever runs against correct code is unfalsifiable: it would report the same
 * "0 findings" whether it works or the input space it explores never reaches the defect. Each
 * property below was validated against a deliberate, minimal mutation reproducing the exact
 * vulnerable shape it targets and against the protected source. Each property records the mutation,
 * the failure it produced and confirmation in both directions because "it passed
 * when I ran it" and "it rejects the vulnerable shape" are different claims.
 *
 * <p>Property 1b targets rejection-message interpolation at its reachable site:
 * {@code readKeys}, not the dead {@code validateScalar} branch. Three
 * independent fresh runs at Property 1's designed budget ({@code tries=500}, {@code
 * .jqwik-database} cleared before each so no run could replay another's cached sample) went green,
 * green, fail against the seeds and limits Property 1 used at the time: probabilistic, not
 * reliable, at its own shipped budget. Inflating that budget to chase reliability for one narrow
 * invariant would have been the wrong fix; Property 1b targets the same invariant directly instead
 * and is reliable at 100 tries — three independent fresh runs, three failures on try 1. See
 * Property 1b's own comment for both measurements.
 *
 * <p>The green/green/fail measurement used inline seeds and does not describe the current corpus.
 * Pairing {@code graphml-corpus} seeds with limits sized for the inline seeds makes every corpus seed
 * reject unmutated, so such runs provide no mutation coverage. The current configuration pairs the seeds with
 * {@code SEED_MUTATION_LIMITS} and verifies the pairing through
 * {@link #seedCorpusParsesCleanlyAndInBoundsUnderTheMutationLimits()}; Property 1 records the
 * corresponding measurement under those limits.
 */
class SecureGraphMlParserFuzzTest {

    private static final Set<String> DECLARED_MESSAGES = declaredPublicMessages();

    // ------------------------------------------------------------------------------------------
    // Property 1 — no rejection message ever leaves the declared vocabulary, across many seeds
    // and jqwik-generated mutations rather than one fixed seed and one fixed RNG stream.
    //
    // Red control: two vulnerable interpolation sites in GraphMlDocument had the same shape:
    // "GraphML key '" + id + "' declares unsupported scalar
    // type '" + type + "'". A mutation at validateScalar cannot make this property red because that
    // branch is dead code: the only `new KeyDefinition(...)` call
    // site already passed the same SCALAR_TYPES check beforehand, so
    // validateScalar's `key.type()` can never fail it. The red control therefore mutates readKeys,
    // the reachable site that checks the untrusted `attr.type` string directly.
    //
    // MEASURED under two different seed/limits pairings because changing the corpus without
    // re-pairing its limits invalidates the measurement. Under the inline
    // literal seeds and SMALL_LIMITS, three independent fresh runs
    // (.jqwik-database cleared before each) at tries=500 went green, green, fail, the failure at
    // try 432 with shrunk counterexample "GraphML key 'k0' declares unsupported scalar type
    // '>tring'". seeds() now loads ravenroot-core/src/test/resources/graphml-corpus/accepted/
    // (see SEED_CORPUS_NAMES) without re-pairing the limits those seeds are parsed under, and
    // SMALL_LIMITS — sized for the OLD seeds' shape (4 keys, 4 properties) — rejected all three
    // corpus fixtures unmutated (scalar-types.graphml alone declares 14 keys). Every mutation of an
    // already-rejected seed stays rejected on the same ceiling, so the property passed regardless of
    // whether readKeys was even reachable: three fresh runs against the reintroduced defect went
    // green, green, green. That original try-432 result is retroactively NOT REPRODUCIBLE and must
    // not be cited as current evidence; it describes a seed/limits pairing this class no longer
    // ships. Found outside this suite's own tooling, which is exactly why
    // {@link #seedCorpusParsesCleanlyAndInBoundsUnderTheMutationLimits()} exists now: an untagged,
    // always-run guard asserting every corpus seed parses cleanly under SEED_MUTATION_LIMITS, so a
    // future corpus change that unmatches the pairing again fails loudly at that guard instead of
    // sliding silently into every mutation being rejected before it can matter.
    //
    // Re-paired: this property now parses under SEED_MUTATION_LIMITS (sized with headroom to admit
    // all three corpus fixtures unmutated — see that field's own Javadoc), not SMALL_LIMITS.
    // Re-measured against the same reintroduced defect, three independent fresh runs, tries=500:
    // fail (try 276), fail (try 302), green — the shrunk counterexample both failures produced was
    // again exactly "GraphML key '<id>' declares unsupported scalar type '>tring'", the literal
    // representative example quoted above. The property is load-bearing again.
    //
    // Why this property does not carry the guarantee even so: whole-document mutation lands inside
    // the few-byte attr.type span too rarely for RELIABLE detection at any budget this class is
    // willing to pay every run — 2 of 3 fresh runs caught it this time, but that is still
    // probabilistic, not the near-1.0 hit rate a guarantee needs. That guarantee belongs to
    // Property 1b below, which generates the attr.type value directly and catches the same defect
    // reliably at tries=100. This property's role is broad structural coverage across a whole
    // mutated document, not the vehicle for reproducing this specific vulnerable shape.
    //
    // Both this property and 1b require exact membership in the declared rejection vocabulary.
    // Prefix matching would allow a mutation to widen a declared prefix while still passing.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 500)
    void mutatedGraphMlFromAnySeedNeverProducesAnUndeclaredRejectionMessage(
            @ForAll("seeds") byte[] seed, @ForAll("mutations") List<Mutation> mutations) {
        byte[] candidate = seed.clone();
        for (Mutation mutation : mutations) {
            candidate[mutation.position() % candidate.length] = mutation.replacement();
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(candidate), SEED_MUTATION_LIMITS)) {
                assertTrue(manager.nodeCount() <= SEED_MUTATION_LIMITS.maxNodes());
            } catch (GraphMlParseException | GraphMlCompatibilityException expected) {
                assertTrue(DECLARED_MESSAGES.contains(expected.getMessage()),
                        "undeclared rejection message: " + expected.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b — narrow, high-signal companion to Property 1, added after measuring Property
    // 1's actual power against the readKeys interpolation reintroduction: three independent fresh runs
    // (see Property 1's own comment) went green, green, fail — probabilistic, because a
    // whole-document byte mutation lands inside the few-byte attr.type span only a small fraction
    // of the time. Rather than trust a property to guarantee detection of an invariant it only
    // catches by chance, this property generates ONLY the attr.type value, so the same invariant
    // is checked with a near-1.0 hit rate per try. Property 1 keeps its original tries=500 and its
    // original purpose: broad structural coverage across a whole mutated document, not the
    // guaranteed vehicle for reproducing this specific vulnerable shape — that guarantee
    // belongs to this property instead.
    //
    // Red control: same interpolation reintroduction at readKeys as Property 1 above. Three
    // independent fresh runs at this property's designed budget (tries=100, .jqwik-database
    // cleared before each) all failed on try 1 — not a replay artifact, since the database was
    // empty before every run, but the near-1.0 per-try hit rate this property is designed for.
    // Shrunk counterexample each time: the single-character type name "a" (message: "GraphML key
    // 'k0' declares unsupported scalar type 'a'"). Reverted immediately after confirming the
    // failure; passes again against the restored source.
    // ------------------------------------------------------------------------------------------

    private static final Set<String> SCALAR_TYPE_NAMES =
            Set.of("boolean", "int", "long", "float", "double", "string");

    @Tag("fuzz")
    @Property(tries = 100)
    void declaredKeyTypesNeverProduceAnUndeclaredRejectionMessage(@ForAll("garbageTypeNames") String type) {
        byte[] candidate = ("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"/>
                  </graph>
                </graphml>
                """.formatted(type)).getBytes(StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(candidate), SMALL_LIMITS)) {
                assertTrue(manager.nodeCount() <= SMALL_LIMITS.maxNodes());
            } catch (GraphMlParseException | GraphMlCompatibilityException expected) {
                assertTrue(DECLARED_MESSAGES.contains(expected.getMessage()),
                        "undeclared rejection message: " + expected.getMessage());
            }
        });
    }

    @Provide
    Arbitrary<String> garbageTypeNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12)
                .filter(value -> !SCALAR_TYPE_NAMES.contains(value));
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 — a document whose bytes begin with a compressed-archive container signature is
    // always classified COMPRESSED_ARCHIVE, never MALFORMED_XML, whatever bytes follow the magic.
    //
    // Red control: FIX-09 covers exactly the vulnerable behaviour — an archive-signature
    // payload fell through to the XML stream reader and was misreported as merely not well-formed
    // XML, losing the "this was refused as a compressed archive" classification. Reintroduced by
    // temporarily short-circuiting SecureGraphMlParser#looksLikeArchive to `return false;` — this
    // property failed on try 1 (shrunk to the 2-byte gzip magic 0x1f 0x8b alone, zero trailing
    // bytes) with reason MALFORMED_XML instead of the expected COMPRESSED_ARCHIVE. Reverted
    // immediately after confirming the failure; passes again against the restored source.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 200)
    void archiveMagicBytesAreAlwaysClassifiedAsCompressedArchive(
            @ForAll("archiveMagicPrefixes") byte[] magic, @ForAll("archiveSuffixes") byte[] suffix) {
        byte[] candidate = new byte[magic.length + suffix.length];
        System.arraycopy(magic, 0, candidate, 0, magic.length);
        System.arraycopy(suffix, 0, candidate, magic.length, suffix.length);

        var rejection = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(new ByteArrayInputStream(candidate), SMALL_LIMITS));
        assertEquals(GraphMlParseException.Reason.COMPRESSED_ARCHIVE, rejection.reason());
    }

    // ------------------------------------------------------------------------------------------
    // Property 3 — GraphMlDocument#read scales linearly, not quadratically, in element count,
    // generalizing GraphMlDocumentQuadraticScanRegressionTest's single fixed size pair to
    // jqwik-generated base sizes. Kept at a small `tries` count deliberately: this is a timing
    // measurement, and the anti-flakiness technique (a growth RATIO between a paired base and
    // 6x-scaled document, never an absolute duration) is inherited from that test rather than
    // reinvented, for the same reason it was adopted there — four other tests in this suite went
    // flaky under CI load on absolute-time assertions before that pattern was standardised.
    //
    // Red control: the vulnerable shape in GraphMlDocument#elements is
    // `for (int index = 0; index < nodes.getLength(); index++)`, which reads
    // the live NodeList's length on every iteration instead of the hoisted `length` local. This
    // property failed with a measured ratio in the 25-30x range (quadratic) against the
    // MAX_ACCEPTABLE_GROWTH_RATIO ceiling below, consistent with the ~28-32x this project measured
    // for the same vulnerable loop in GraphMlDocumentQuadraticScanRegressionTest's Javadoc. The
    // protected source passes with the length hoisted into a local.
    // ------------------------------------------------------------------------------------------

    private static final int PROPERTIES_PER_NODE = 10;
    private static final int SCALE_FACTOR = 6;
    private static final int TRIALS = 3;
    private static final double MAX_ACCEPTABLE_GROWTH_RATIO = 8.0;

    @Tag("fuzz")
    @Property(tries = 5)
    void documentReadTimeGrowsLinearlyAcrossGeneratedSizes(@ForAll("baseNodeCounts") int baseNodes) {
        int scaledNodes = baseNodes * SCALE_FACTOR;
        // Warm up both sizes once before any measurement is kept (mirrors the dedicated regression
        // test, for the same reason: neither measured trial should pay a one-off cold-start cost).
        timeRead(document(baseNodes));
        timeRead(document(scaledNodes));

        long baseNanos = bestOf(baseNodes);
        long scaledNanos = bestOf(scaledNodes);
        assertTrue(baseNanos > 0, "measured zero time; the harness is not actually timing the scan");

        double ratio = (double) scaledNanos / (double) baseNanos;
        assertTrue(ratio < MAX_ACCEPTABLE_GROWTH_RATIO, () -> String.format(
                "GraphMlDocument.read() grew %.2fx for a %dx increase in element count (base=%d "
                        + "nodes/%dns, scaled=%d nodes/%dns); linear growth stays near %dx",
                ratio, SCALE_FACTOR, baseNodes, baseNanos, scaledNodes, scaledNanos, SCALE_FACTOR));
    }

    // ------------------------------------------------------------------------------------------
    // Property 4 — pure unstructured bytes, no valid-document seed at all, never escape as an
    // undeclared crash and never hang. Distinct from Property 1: that property mutates a valid
    // document; this one explores from nothing, which reaches call sites Property 1's mutations
    // are structurally unlikely to (e.g. the very first StAX event never being START_ELEMENT).
    // No known defect targets this property specifically — it is the literal reading of
    // the "no crash, hang, or OOM" property for arbitrary bytes, and it passes today
    // because SecureGraphMlParser's every StAX branch already ends in a typed rejection or a bound.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 300)
    void arbitraryByteSequencesNeverEscapeAsUnclassifiedFailuresOrHangs(
            @ForAll("arbitraryPayloads") byte[] payload) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(payload), SMALL_LIMITS)) {
                assertTrue(manager.nodeCount() <= SMALL_LIMITS.maxNodes());
            } catch (GraphMlParseException | GraphMlCompatibilityException expected) {
                assertNotNull(expected.getMessage());
            } catch (RuntimeException leaked) {
                fail("GraphML import leaked " + leaked.getClass().getName() + " for " + payload.length
                        + " arbitrary bytes: " + leaked.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------------------------------ generators

    private static final int SMALL_STRING_LIMIT = 64;
    private static final GraphMlLimits SMALL_LIMITS =
            new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4);

    /**
     * QA-07: {@link #SMALL_LIMITS} was tuned for the private inline seeds this class used before
     * {@link #seeds()} switched to the corpus below
     * — 4 keys and 4 properties were exactly the earlier seeds' shape. The corpus fixtures are
     * bigger ({@code scalar-types.graphml} alone declares 14 keys and 15 properties), so all three
     * were rejected by {@code SMALL_LIMITS} in their UNMUTATED form: {@link #mutatedGraphMlFromAnySeedNeverProducesAnUndeclaredRejectionMessage}
     * was mutating documents that never had a chance to reach the type-validation site any mutation
     * needed to reach, and reported green regardless of whether the site it exists to guard was
     * broken — measured directly: with interpolation reintroduced at {@code readKeys}, the property
     * still passed on three independent fresh runs under the old pairing. A seed corpus and the
     * limits a property parses it under are a matched pair; nothing in the type system enforces
     * that, which is why {@link #seedCorpusParsesCleanlyAndInBoundsUnderTheMutationLimits()} below
     * exists as a standing, always-run guard against exactly this drift happening silently again.
     *
     * <p>Sized with headroom over the corpus's current maximums (14 keys, 15 properties, 3 nodes/3
     * edges in the largest fixture) rather than exactly at them, so a small future corpus addition
     * does not immediately retrigger the same failure mode.
     */
    private static final GraphMlLimits SEED_MUTATION_LIMITS =
            new GraphMlLimits(4096, 5, 5, 20, 8, SMALL_STRING_LIMIT, 20);

    /**
     * QA-07 seeds load from the existing versioned corpus
     * ({@code graphml-corpus/accepted/}, established for {@link GraphMlCorpusTest} and unrelated to
     * this suite originally) rather than from inline literals. One corpus, reviewed once, serves
     * both the fixed-example corpus test and this mutation-based fuzz property, instead of the
     * fuzz test slowly drifting its own private copy of "small valid documents" out of sync with
     * the corpus everyone else already curates. Three names picked for shape diversity — minimal
     * topology, every scalar type, and edges without ids (FIX-01) — not for any property
     * specific to this test. Parsed under {@link #SEED_MUTATION_LIMITS}, not {@link #SMALL_LIMITS}
     * — see that field's Javadoc for why the two must not be the same limits object.
     */
    private static final List<String> SEED_CORPUS_NAMES =
            List.of("canonical-minimal.graphml", "scalar-types.graphml", "optional-edge-ids.graphml");

    private static final byte[] MUTATION_ALPHABET =
            "<>/='\"&; az09\n".getBytes(StandardCharsets.US_ASCII);

    record Mutation(int position, byte replacement) {
    }

    /**
     * QA-07's durable guard pairs a seed corpus and
     * the limits a mutation property parses it under are a matched pair that nothing in the code
     * otherwise says belong together — this is what makes a future corpus change (a fixture edited,
     * a name added or swapped) fail loudly here, at class load, instead of sliding silently into
     * every mutation being rejected before it can matter while the property keeps reporting green.
     * Deliberately untagged: unlike the {@code @Property} methods in this class, this check must run
     * on every default {@code mvn test}, not only under {@code -Pfuzz}, because the failure mode it
     * guards against is invisible from a green {@code -Pfuzz} run by construction.
     */
    @Example
    void seedCorpusParsesCleanlyAndInBoundsUnderTheMutationLimits() {
        for (String name : SEED_CORPUS_NAMES) {
            byte[] fixture = corpusFixture(name);
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(fixture), SEED_MUTATION_LIMITS)) {
                assertTrue(manager.nodeCount() > 0, name + " parsed with no nodes at all");
            } catch (RuntimeException rejected) {
                fail("seed corpus fixture '" + name + "' is rejected under SEED_MUTATION_LIMITS "
                        + "unmutated -- a mutation property over it can never reach anything past "
                        + "this rejection, silently: " + rejected.getMessage(), rejected);
            }
        }
    }

    @Provide
    Arbitrary<byte[]> seeds() {
        return Arbitraries.of(SEED_CORPUS_NAMES).map(SecureGraphMlParserFuzzTest::corpusFixture);
    }

    private static byte[] corpusFixture(String name) {
        try (var input = SecureGraphMlParserFuzzTest.class.getResourceAsStream("/graphml-corpus/accepted/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing GraphML corpus fixture accepted/" + name);
            }
            return input.readAllBytes();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read GraphML corpus fixture accepted/" + name, error);
        }
    }

    @Provide
    Arbitrary<List<Mutation>> mutations() {
        Arbitrary<Integer> position = Arbitraries.integers().between(0, 1 << 16);
        Arbitrary<Byte> replacement = Arbitraries.of(box(MUTATION_ALPHABET));
        Arbitrary<Mutation> mutation = Combinators.combine(position, replacement).as(Mutation::new);
        return mutation.list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<byte[]> archiveMagicPrefixes() {
        return Arbitraries.of(
                new byte[]{(byte) 0x1f, (byte) 0x8b},                    // gzip
                new byte[]{'P', 'K', 3, 4},                               // zip local file header
                new byte[]{'P', 'K', 5, 6},                               // zip end-of-central-directory
                new byte[]{'P', 'K', 7, 8});                              // zip spanned archive
    }

    @Provide
    Arbitrary<byte[]> archiveSuffixes() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(512);
    }

    @Provide
    Arbitrary<Integer> baseNodeCounts() {
        return Arbitraries.integers().between(150, 500);
    }

    @Provide
    Arbitrary<byte[]> arbitraryPayloads() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(2048);
    }

    private static Byte[] box(byte[] values) {
        var boxed = new Byte[values.length];
        for (int index = 0; index < values.length; index++) {
            boxed[index] = values[index];
        }
        return boxed;
    }

    private static long bestOf(int nodes) {
        long best = Long.MAX_VALUE;
        for (int trial = 0; trial < TRIALS; trial++) {
            best = Math.min(best, timeRead(document(nodes)));
        }
        return best;
    }

    /** Prefers thread CPU time over wall-clock, immune to the thread merely being descheduled. */
    private static long timeRead(byte[] xml) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTimeAvailable = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        long start = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() : System.nanoTime();
        GraphMlDocument.read(xml);
        long end = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() : System.nanoTime();
        return end - start;
    }

    /** Same element shape as GraphMlDocumentQuadraticScanRegressionTest: one key, dense data/edges. */
    private static byte[] document(int nodes) {
        var xml = new StringBuilder("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">");
        for (int key = 0; key < PROPERTIES_PER_NODE; key++) {
            xml.append("<key id=\"k").append(key).append("\" for=\"node\" attr.name=\"p")
                    .append(key).append("\" attr.type=\"string\"/>");
        }
        xml.append("<graph id=\"g\" edgedefault=\"directed\">");
        for (int node = 0; node < nodes; node++) {
            xml.append("<node id=\"n").append(node).append("\">");
            for (int key = 0; key < PROPERTIES_PER_NODE; key++) {
                xml.append("<data key=\"k").append(key).append("\">v</data>");
            }
            xml.append("</node>");
        }
        for (int edge = 0; edge < nodes; edge++) {
            xml.append("<edge id=\"e").append(edge).append("\" source=\"n0\" target=\"n0\"/>");
        }
        return xml.append("</graph></graphml>").toString().getBytes(StandardCharsets.UTF_8);
    }
}
